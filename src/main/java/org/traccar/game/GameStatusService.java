/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ForbiddenException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The computed member status of data model 2 (plan §2.1, §3.3): role projections over the typed tables, which
 * replace the status document the Tool published in model 1. Nothing writes status; each read is one transaction.
 *
 * <p>The projections port model 1's {@code GameRequestService.status}, {@code filter}, {@code participantOverview},
 * {@code speedhuntHistory} and {@code speedhuntStatus} and keep its visibility matrix (see {@link GameStatus}). The
 * "current" speedhunt that anonymous progress describes is the latest speedhunt's target while it still runs,
 * otherwise the newest running speedhunt, otherwise a live speedhunt ping that has not reached anyone yet.
 */
@Singleton
public class GameStatusService {

    /** Model 1's limit on an operator's live share reason. */
    static final int MAX_REASON = 500;

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private static final Comparator<GameMember> ROSTER_ORDER = Comparator
            .comparing((GameMember member) -> GameMember.PLAYER.equals(member.role()) ? 0 : 1)
            .thenComparingInt(GameMember::number);

    private final GameTransactions transactions;

    @Inject
    public GameStatusService(GameTransactions transactions) {
        this.transactions = transactions;
    }

    private static GameMember caller(GameTransaction transaction, long userId) throws SQLException {
        return GameMemberRepository.byUser(transaction, userId)
                .orElseThrow(() -> new ForbiddenException("This account does not take part in a game"));
    }

    // Reads -------------------------------------------------------------------------------------------------

    /** The status of the account's member, in one transaction; 403 when the account takes part in no game. */
    public GameStatus.Status status(long userId) throws Exception {
        return transactions.read(transaction -> status(transaction, caller(transaction, userId)));
    }

    /** The session's speedhunt history for a member of it: the same anonymous projection for every member. */
    public GameStatus.History speedhuntHistory(long userId) throws Exception {
        return transactions.read(transaction -> {
            long sessionId = caller(transaction, userId).gameSessionId();
            List<GameStatus.HistoryRun> runs = new ArrayList<>();
            for (GameState.Run run : GameStateRepository.runs(transaction, sessionId, false)) {
                List<GameStatus.HistoryPing> pings = new ArrayList<>();
                for (GameState.Ping ping : GameStateRepository.pings(transaction, sessionId, run.id())) {
                    pings.add(new GameStatus.HistoryPing(ping.id(), ping.number(), GameTime.text(ping.sentAt()),
                            ping.mode()));
                }
                runs.add(new GameStatus.HistoryRun(run.id(), GameTime.text(run.startedAt()),
                        run.active() ? "inProgress" : "finished", pings));
            }
            return new GameStatus.History(sessionId, runs);
        });
    }

    /**
     * Anonymous progress of the speedhunt in the executing session, without ping history: of the given session
     * (a member's), or of whichever session executes when it is null (administrators). Inactive while no Tool
     * executes that session.
     */
    public GameStatus.SpeedhuntProgress speedhunt(Long sessionId) throws Exception {
        return transactions.read(transaction -> {
            Optional<Long> executing = GameStateRepository.executingSession(transaction);
            if (executing.isEmpty() || sessionId != null && !sessionId.equals(executing.get())) {
                return GameStatus.SpeedhuntProgress.INACTIVE;
            }
            return new View(transaction, executing.get()).progress(false);
        });
    }

    /** The status of a member inside a transaction. */
    static GameStatus.Status status(GameTransaction transaction, GameMember member) throws Exception {
        View view = new View(transaction, member.gameSessionId());
        GameStatus.Projection projection = switch (member.role()) {
            case GameMember.PLAYER, GameMember.SUPPORTER -> view.player(member);
            case GameMember.HUNTER -> view.hunter();
            case GameMember.SPECTATOR -> view.spectator();
            default -> throw new ForbiddenException("This account does not take part in a game");
        };
        return new GameStatus.Status(member.gameSessionId(), member.id(), member.role(), member.number(),
                member.displayName(), view.active, transaction.now().toString(),
                hash(member.role(), view.active, projection), projection);
    }

    /** A hash of what the member observes: role, control and projection; no identity, time or revision. */
    static String hash(String role, boolean controllerActive, GameStatus.Projection projection) throws Exception {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", role);
        content.put("controllerActive", controllerActive);
        content.put("status", projection);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                CANONICAL.writeValueAsString(content).getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest).substring(0, 32);
    }

    // Projections -------------------------------------------------------------------------------------------

    /** The typed state of one session, read once for all projections. */
    private static final class View {

        private final GameTransaction transaction;
        private final long session;
        private final Instant now;
        private final boolean active;
        private final GameSettings settings;
        private final List<GameMember> participants = new ArrayList<>();
        private final Set<Long> caught = new HashSet<>();
        private final Map<Long, GameState.Run> runs = new LinkedHashMap<>();
        private final Map<Long, List<GameState.Ping>> pings = new HashMap<>();
        private final Map<Long, GameState.LiveUpload> livePings = new LinkedHashMap<>();
        private final Map<Long, GameState.Break> breaks = new HashMap<>();
        private final GameState.Run lastRun;

        View(GameTransaction transaction, long session) throws SQLException {
            this.transaction = transaction;
            this.session = session;
            this.now = transaction.now();
            this.active = GameStateRepository.executingEpoch(transaction, session) != null;
            this.settings = GameSettings.read(transaction, session);
            for (GameMember member : GameMemberRepository.list(transaction, session)) {
                // A participant is an active player or supporter with a tracker, like model 1's published list.
                if (member.active() && GameMember.hasState(member.role()) && member.trackerId() != null) {
                    participants.add(member);
                }
            }
            participants.sort(ROSTER_ORDER);
            for (GameState.Catch item : GameStateRepository.catches(transaction, session, true)) {
                caught.add(item.memberId());
            }
            for (GameState.Run run : GameStateRepository.runs(transaction, session, true)) {
                runs.put(run.targetMemberId(), run);
                pings.put(run.id(), GameStateRepository.pings(transaction, session, run.id()));
            }
            for (GameState.LiveUpload upload : GameStateRepository.activeUploads(transaction, session)) {
                if (GameState.SPEEDHUNT_PING.equals(upload.purpose())) {
                    livePings.putIfAbsent(upload.sourceMemberId(), upload);
                }
            }
            for (GameState.Break pause : GameStateRepository.currentBreaks(transaction, session, null)) {
                if (pause.active()) {
                    breaks.put(pause.memberId(), pause);
                }
            }
            this.lastRun = GameStateRepository.lastRun(transaction, session).orElse(null);
        }

        private List<GameStatus.PingTime> pingTimes(GameState.Run run) {
            List<GameStatus.PingTime> result = new ArrayList<>();
            if (run != null) {
                for (GameState.Ping ping : pings.get(run.id())) {
                    result.add(new GameStatus.PingTime(ping.number(), GameTime.text(ping.sentAt())));
                }
            }
            return result;
        }

        /** The member whose speedhunt anonymous progress describes, or null when none runs. */
        private Long current() {
            if (lastRun != null && (runs.containsKey(lastRun.targetMemberId())
                    || livePings.containsKey(lastRun.targetMemberId()))) {
                return lastRun.targetMemberId();
            }
            if (!runs.isEmpty()) {
                return runs.keySet().iterator().next();
            }
            return livePings.isEmpty() ? null : livePings.keySet().iterator().next();
        }

        /** Anonymous progress, the same for every member; inactive while no Tool controls the game. */
        GameStatus.SpeedhuntProgress progress(boolean history) {
            Long target = active ? current() : null;
            if (target == null) {
                return GameStatus.SpeedhuntProgress.INACTIVE;
            }
            List<GameStatus.PingTime> times = pingTimes(runs.get(target));
            return new GameStatus.SpeedhuntProgress(true, times.isEmpty() ? null : times.size(),
                    settings.pingsPerSpeedhunt(), history ? times : null);
        }

        private GameStatus.Break pause(long memberId) {
            GameState.Break pause = breaks.get(memberId);
            return pause == null ? GameStatus.Break.NONE
                    : new GameStatus.Break(true, GameTime.text(pause.startedAt()), GameTime.text(pause.deadline()));
        }

        private GameStatus.Limits limits() {
            return new GameStatus.Limits(settings.pingsPerSpeedhunt(), settings.fixedLiveMinutes(),
                    settings.allowConsecutiveSpeedhunts(), settings.limitEnforcement(),
                    new GameStatus.RequestApprovals(
                            settings.approvalRequired(GameRequest.APPROVAL_SETTINGS.get(GameRequest.STATIC_PING)),
                            settings.approvalRequired(GameRequest.APPROVAL_SETTINGS.get(GameRequest.LIVE_PING))));
        }

        private List<GameStatus.Participant> participants(Map<Long, String> labels, boolean trackers) {
            List<GameStatus.Participant> result = new ArrayList<>();
            for (GameMember member : participants) {
                GameState.Run run = runs.get(member.id());
                GameState.LiveUpload live = livePings.get(member.id());
                List<GameStatus.PingTime> times = pingTimes(run);
                GameStatus.ParticipantSpeedhunt speedhunt = new GameStatus.ParticipantSpeedhunt(run != null,
                        times.size(), times, run == null, live != null,
                        live == null ? null : GameTime.text(live.deadline()), member.stateRevision());
                result.add(new GameStatus.Participant(member.id(), member.role(), member.number(),
                        labels == null ? member.displayName() : labels.get(member.id()),
                        trackers ? member.trackerId() : null, caught.contains(member.id()), speedhunt,
                        pause(member.id())));
            }
            return result;
        }

        GameStatus.HunterView hunter() throws SQLException {
            Map<Long, String> labels = GameRequestService.rosterLabels(transaction, session);
            Set<Long> listed = new HashSet<>();
            participants.forEach(member -> listed.add(member.id()));
            List<GameStatus.LiveShare> shares = new ArrayList<>();
            for (GameStateService.LiveShare share : GameStateService.liveShares(transaction, session)) {
                if (!listed.contains(share.memberId())) {
                    continue;
                }
                String reason = share.reason() == null ? "" : share.reason().strip();
                shares.add(new GameStatus.LiveShare(share.memberId(), labels.get(share.memberId()),
                        GameTime.text(share.endsAt()), share.source(),
                        reason.isEmpty() || reason.codePointCount(0, reason.length()) > MAX_REASON ? null : reason));
            }
            return new GameStatus.HunterView(progress(true), participants(labels, false), limits(),
                    lastRun == null ? null : lastRun.targetMemberId(), shares);
        }

        GameStatus.SpectatorView spectator() {
            return new GameStatus.SpectatorView(progress(true), participants(null, true), limits());
        }

        GameStatus.PlayerView player(GameMember member) throws SQLException {
            GameStatus.RunningFake running = null;
            if (active) {
                for (GameRequestService.RunningWindow window : GameRequestService.runningWindows(transaction,
                        session)) {
                    if (window.firstPing() > 1 && window.firstPing() <= window.lastPing()) {
                        running = new GameStatus.RunningFake(window.runToken(), window.firstPing(),
                                window.lastPing());
                        break;
                    }
                }
            }
            List<GameStatus.RosterEntry> roster = new ArrayList<>();
            for (GameMember participant : participants) {
                roster.add(new GameStatus.RosterEntry(participant.id(), participant.role(), participant.number(),
                        participant.displayName(), caught.contains(participant.id())));
            }
            return new GameStatus.PlayerView(progress(true), pause(member.id()), running, roster,
                    fakes(member.id()), plannedBreaks(member.id()));
        }

        /** The owner's armed fakes; a fake on a running speedhunt names the run token the owner chose. */
        private List<GameStatus.OwnFake> fakes(long memberId) throws SQLException {
            List<GameStatus.OwnFake> result = new ArrayList<>();
            for (var item : GameJokerRepository.reserved(transaction, session, memberId, false)) {
                if (!item.armed() || !GameJokerRepository.FAKE_PING.equals(item.type())) {
                    continue;
                }
                GameJokerService.Reservation view = GameJokerService.view(transaction, session, item, true);
                GameJokerService.FakePlan plan = view.fake();
                if (plan == null) {
                    continue;
                }
                String token = null;
                if (GameJokerService.USE_RUNNING_SPEEDHUNT.equals(view.use()) && plan.boundRunId() != null) {
                    token = GameStateRepository.run(transaction, session, plan.boundRunId())
                            .map(run -> GameRequestService.runToken(session, run)).orElse(null);
                }
                result.add(new GameStatus.OwnFake(item.id(), view.use(), plan.latitude(), plan.longitude(),
                        plan.entryId(), GameTime.text(plan.scheduledAt()), plan.pingNumber(), token));
            }
            return result;
        }

        private List<GameStatus.PlannedBreak> plannedBreaks(long memberId) throws SQLException {
            List<GameStatus.PlannedBreak> result = new ArrayList<>();
            for (GameState.Break pause : GameStateRepository.currentBreaks(transaction, session, memberId)) {
                if (pause.pending()) {
                    result.add(new GameStatus.PlannedBreak(pause.id(), pause.reservationId(),
                            GameTime.text(pause.plannedStart()), pause.state(), pause.plannedBy()));
                }
            }
            return result;
        }

    }

}
