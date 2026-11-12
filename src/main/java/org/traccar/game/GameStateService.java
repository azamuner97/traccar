/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The typed game state of a session (plan §1): reads over catches, runs and pings, breaks and live uploads, and the
 * typed operations that change them ({@link GameOperation}). Effects deliveries apply the same operations together
 * with positions, notifications and request outcomes ({@link GameEffectsService}).
 *
 * <p>Counts and statistics are queries over runs and pings; hunter live shares are derived from the active live
 * uploads that reach hunters. Nothing here is a status document.
 */
@Singleton
public class GameStateService {

    /** Operations applied in one transaction, and the session aggregate revision after it. */
    public record Applied(List<GameOperations.Result> results, List<GameOperations.FakeOutcome> fakes,
                          long sessionRevision) {
    }

    /** The current state: active catches, running speedhunts, current breaks and active live uploads. */
    public record Snapshot(List<GameState.Catch> catches, List<GameState.Run> runs, List<GameState.Break> breaks,
                           List<GameState.LiveUpload> liveUploads, Map<Long, Long> revisions) {
    }

    public record RunHistory(GameState.Run run, List<GameState.Ping> pings) {
    }

    /**
     * A live share hunters see: a member whose positions an active live upload sends to hunters. {@code source} is
     * {@code manual}, {@code geofence} or {@code speedhunt}; {@code reason} is the operator's text.
     */
    public record LiveShare(long memberId, String source, Instant endsAt, String reason) {
    }

    private final GameTransactions transactions;
    private final ObjectMapper objectMapper;
    private final GamePublisher publisher;

    @Inject
    public GameStateService(GameTransactions transactions, ObjectMapper objectMapper, GamePublisher publisher) {
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.publisher = publisher;
    }

    // Operations --------------------------------------------------------------------------------------------

    /**
     * Applies operations in one transaction, under the controller {@code epoch} that fenced the call. Positions,
     * notifications and request outcomes travel in effects deliveries instead.
     */
    public Applied apply(long sessionId, long epoch, List<GameOperation> operations) throws Exception {
        if (operations == null || operations.isEmpty()
                || operations.stream().anyMatch(java.util.Objects::isNull)) {
            throw GameErrors.invalid("Invalid game operations");
        }
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            transaction.lockMemberStates(GameOperations.members(transaction, sessionId, operations));
            GameOperations applied = new GameOperations(transaction, sessionId, epoch, "Operator", objectMapper);
            for (int i = 0; i < operations.size(); i++) {
                applied.apply(i, operations.get(i));
            }
            if (!applied.immediateBreaks().isEmpty()) {
                throw GameErrors.invalid("An immediate break starts with its request's outcome");
            }
            long revision = transaction.bumpSession();
            publish(transaction, publisher, sessionId, applied);
            return new Applied(applied.results(), applied.fakes(), revision);
        });
    }

    /**
     * Registers what applied operations publish after commit. Status, history and request changes are announced to
     * every member alike; joker changes only to their owners (and the hunters for their pool).
     */
    static void publish(GameTransaction transaction, GamePublisher publisher, long sessionId, GameOperations applied)
            throws SQLException {
        List<GamePublisher.Invalidation> invalidations = new ArrayList<>();
        if (applied.statusChanged()) {
            invalidations.add(new GamePublisher.Invalidation(sessionId, GamePublisher.STATUS, null));
        }
        if (applied.historyChanged()) {
            invalidations.add(new GamePublisher.Invalidation(sessionId, GamePublisher.SPEEDHUNT_HISTORY, null));
        }
        if (applied.requestsChanged()) {
            invalidations.add(new GamePublisher.Invalidation(sessionId, GamePublisher.REQUESTS, null));
        }
        Set<Long> owners = new HashSet<>(applied.jokerMembers());
        if (applied.poolChanged()) {
            owners.addAll(hunters(transaction, sessionId));
        }
        if (!owners.isEmpty()) {
            invalidations.add(new GamePublisher.Invalidation(sessionId, GamePublisher.JOKERS, Set.copyOf(owners)));
        }
        for (var invalidation : invalidations) {
            transaction.afterCommit(() -> publisher.invalidate(invalidation));
        }
    }

    /** The active hunters of a session, who share the speedhunt pool. */
    static List<Long> hunters(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.list("SELECT id FROM tc_game_session_members WHERE gamesessionid = ? AND role = ?"
                + " AND state = ?", rows -> rows.getLong(1), sessionId, GameMember.HUNTER, GameMember.ACTIVE);
    }

    // Reads -------------------------------------------------------------------------------------------------

    private <T> T read(long sessionId, GameTransactions.Work<T> work) throws Exception {
        return transactions.read(transaction -> {
            GameDocumentService.sessionExists(transaction, sessionId);
            return work.run(transaction);
        });
    }

    public Snapshot snapshot(long sessionId) throws Exception {
        return read(sessionId, transaction -> new Snapshot(
                GameStateRepository.catches(transaction, sessionId, true),
                GameStateRepository.runs(transaction, sessionId, true),
                GameStateRepository.currentBreaks(transaction, sessionId, null),
                GameStateRepository.activeUploads(transaction, sessionId),
                revisions(transaction, sessionId)));
    }

    /** Member state revisions of the session's players and supporters: operator and hunter data (plan §2.4). */
    static Map<Long, Long> revisions(GameTransaction transaction, long sessionId) throws SQLException {
        Map<Long, Long> result = new LinkedHashMap<>();
        transaction.list("SELECT memberid, revision FROM tc_game_member_state WHERE gamesessionid = ?"
                + " ORDER BY memberid", rows -> result.put(rows.getLong(1), rows.getLong(2)), sessionId);
        return result;
    }

    /** Every catch interval of a session, oldest first. */
    public List<GameState.Catch> catches(long sessionId) throws Exception {
        return read(sessionId, transaction -> GameStateRepository.catches(transaction, sessionId, false));
    }

    /** Every run with its delivered pings, newest first. */
    public List<RunHistory> runs(long sessionId) throws Exception {
        return read(sessionId, transaction -> {
            List<RunHistory> result = new ArrayList<>();
            for (GameState.Run run : GameStateRepository.runs(transaction, sessionId, false)) {
                result.add(new RunHistory(run, GameStateRepository.pings(transaction, sessionId, run.id())));
            }
            return result;
        });
    }

    public List<GameState.Break> breaks(long sessionId) throws Exception {
        return read(sessionId, transaction -> GameStateRepository.breaks(transaction, sessionId));
    }

    public List<GameState.LiveUpload> liveUploads(long sessionId) throws Exception {
        return read(sessionId, transaction -> GameStateRepository.uploads(transaction, sessionId));
    }

    /** Per member: speedhunts, delivered pings, live pings and the start of the latest speedhunt. */
    public List<GameStateRepository.Statistics> statistics(long sessionId) throws Exception {
        return read(sessionId, transaction -> GameStateRepository.statistics(transaction, sessionId));
    }

    /** The target of the most recently started speedhunt, if any. */
    public Long lastSpeedhuntTarget(long sessionId) throws Exception {
        return read(sessionId, transaction -> GameStateRepository.lastRun(transaction, sessionId)
                .map(GameState.Run::targetMemberId).orElse(null));
    }

    /** Whether any speedhunt runs: an active run or an active live speedhunt ping. */
    public boolean speedhuntRunning(long sessionId) throws Exception {
        return read(sessionId, transaction -> GameStateRepository.speedhuntRunning(transaction, sessionId));
    }

    /**
     * Hunter live shares, derived from the active live uploads that reach hunters, while the controller executes
     * this session. Uploads of an earlier controller epoch are stale and not shown; nor are members who are caught
     * or protected by a break, or uploads past their deadline.
     */
    public List<LiveShare> liveShares(long sessionId) throws Exception {
        return read(sessionId, transaction -> liveShares(transaction, sessionId));
    }

    static List<LiveShare> liveShares(GameTransaction transaction, long sessionId) throws SQLException {
        Long epoch = GameStateRepository.executingEpoch(transaction, sessionId);
        List<LiveShare> result = new ArrayList<>();
        if (epoch == null) {
            return result;
        }
        Instant now = transaction.now();
        Set<Long> included = new HashSet<>();
        for (GameState.LiveUpload upload : GameStateRepository.activeUploads(transaction, sessionId)) {
            if (!upload.toHunters() || GameState.HUNTER_RELAY.equals(upload.purpose())
                    || upload.controllerEpoch() != epoch
                    || upload.deadline() != null && !upload.deadline().isAfter(now)) {
                continue;
            }
            long member = upload.sourceMemberId();
            boolean caught = GameStateRepository.activeCatch(transaction, sessionId, member).isPresent();
            boolean onBreak = GameStateRepository.activeBreak(transaction, sessionId, member)
                    .map(item -> item.protects(now)).orElse(false);
            if (caught || onBreak || !included.add(member)) {
                continue;
            }
            String source = switch (upload.purpose()) {
                case GameState.MANUAL -> "manual";
                case GameState.GEOFENCE_AUTO -> "geofence";
                default -> "speedhunt";
            };
            result.add(new LiveShare(member, source, upload.deadline(), upload.reason()));
        }
        return result;
    }

}
