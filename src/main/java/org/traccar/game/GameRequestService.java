/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ForbiddenException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Self-service game requests of data model 2 (model-1 Stage 3 rules, with members and typed state).
 *
 * <p>Members submit requests while the controlling Tool executes their session. The server authorizes them from
 * membership and validates them against the typed tables: "a speedhunt is running" means an active run or an active
 * live speedhunt ping, a member is caught while a catch is open, and on a break while an active break has not
 * reached its deadline. A participant has at most one open request per joker type; the hunters' speedhunt actions
 * on one participant are serialized and carry the participant's member state revision. Requests awaiting approval
 * or execution expire after two minutes. Whether a request needs the operator's approval comes from the session
 * settings ({@code request_approvals}); the controlling Tool decides, claims and completes requests, and their
 * outcomes commit inside effects deliveries together with joker accounting.
 *
 * <p>Answers never reveal whom a speedhunt targets: a break request during any speedhunt gets the same message for
 * every participant.
 */
@Singleton
public class GameRequestService {

    public static final int EXPIRY_SECONDS = 120;
    /** The same answer for every participant, so it never reveals whom a speedhunt targets. */
    public static final String BREAK_UNAVAILABLE = GameOperations.BREAK_UNAVAILABLE;
    public static final String BREAK_OVERLAP = "This break would overlap another break of yours";
    public static final String NO_SPEEDHUNT_JOKER = "No speedhunt joker is left; ask the operator for more";
    public static final String REPEAT_SPEEDHUNT = "The previous speedhunt was already on this participant";
    public static final String LIVE_DURATION_REQUIRED = "Set a positive live ping duration in the game settings";
    public static final String NOT_RUNNING = "The game is not running right now; try again later";
    public static final String STALE = "The speedhunt changed meanwhile; refresh and try again";

    public record Submission(String type, Long targetMemberId, Long baseRevision, JsonNode parameters) {
    }

    public record Completion(Long id, String status, String message, String detail) {
    }

    /** A running speedhunt that members may plan a fake on, from {@code firstPing} to {@code lastPing}. */
    public record RunningWindow(long runId, long targetMemberId, String runToken, int firstPing, int lastPing) {
    }

    private final GameTransactions transactions;
    private final ObjectMapper objectMapper;
    private final GamePublisher publisher;

    @Inject
    public GameRequestService(GameTransactions transactions, ObjectMapper objectMapper, GamePublisher publisher) {
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.publisher = publisher;
    }

    // Running speedhunts ------------------------------------------------------------------------------------

    /** An opaque token for a run, the reference members use for a running speedhunt; it names no target. */
    static String runToken(long sessionId, GameState.Run run) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(("speedhunt-run:" + sessionId + ":"
                    + run.id() + ":" + run.startedAt().toEpochMilli()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    /**
     * The running speedhunts, newest first, with the ping slots a fake may still be planned on: from the next ping
     * to the ping limit (at most {@value GameSettings#MAX_PING_NUMBER}).
     */
    static List<RunningWindow> runningWindows(GameTransaction transaction, long sessionId) throws SQLException {
        int last = GameSettings.read(transaction, sessionId).lastFakePing();
        List<RunningWindow> result = new ArrayList<>();
        for (GameState.Run run : GameStateRepository.runs(transaction, sessionId, true)) {
            int first = GameStateRepository.lastPingNumber(transaction, sessionId, run.id()) + 1;
            result.add(new RunningWindow(run.id(), run.targetMemberId(), runToken(sessionId, run), first, last));
        }
        return result;
    }

    // Views -------------------------------------------------------------------------------------------------

    /** Roster labels for hunters: the role group's name (or the role) and the member number. */
    static Map<Long, String> rosterLabels(GameTransaction transaction, long sessionId) throws SQLException {
        Map<String, String> groups = new java.util.HashMap<>();
        transaction.list("SELECT r.purpose, g.name FROM tc_game_session_groups r JOIN tc_groups g ON g.id = r.groupid"
                + " WHERE r.gamesessionid = ? AND r.memberid IS NULL AND r.purpose IN (?, ?, ?)",
                rows -> groups.put(rows.getString(1), rows.getString(2)), sessionId, GameMember.PLAYER,
                GameMember.SUPPORTER, GameMember.HUNTER);
        Map<Long, String> result = new java.util.HashMap<>();
        for (GameMember member : GameMemberRepository.list(transaction, sessionId)) {
            String group = groups.getOrDefault(member.role(), "").strip();
            String role = member.role().substring(0, 1).toUpperCase() + member.role().substring(1);
            result.put(member.id(), (group.isEmpty() ? role : group) + " " + member.number());
        }
        return result;
    }

    /** What a member sees of a request: hunters get roster labels and no texts; nobody gets the detail. */
    private static GameRequest memberView(GameRequest request, Map<Long, String> labels) {
        return new GameRequest(request.id(), request.gameSessionId(), request.type(), request.requesterMemberId(),
                labels == null ? request.requesterLabel() : labels.get(request.requesterMemberId()),
                request.requesterRole(), request.targetMemberId(),
                labels == null ? request.targetLabel() : labels.get(request.targetMemberId()),
                request.baseRevision(), request.parameters(), request.status(), request.approvalRequired(),
                labels == null ? request.message() : null, null, null, request.createdAt(), request.expiresAt(),
                request.decidedAt(), request.completedAt(), request.updatedAt());
    }

    private static Map<Long, String> labelsFor(GameTransaction transaction, GameMember viewer) throws SQLException {
        return GameMember.HUNTER.equals(viewer.role()) ? rosterLabels(transaction, viewer.gameSessionId()) : null;
    }

    private void publishRequests(GameTransaction transaction, long sessionId, GameRequestRepository.Settled settled)
            throws SQLException {
        transaction.afterCommit(() -> publisher.invalidate(
                new GamePublisher.Invalidation(sessionId, GamePublisher.REQUESTS, null)));
        java.util.Set<Long> owners = new java.util.HashSet<>(settled.members());
        if (settled.pool()) {
            owners.addAll(GameStateService.hunters(transaction, sessionId));
        }
        if (!owners.isEmpty()) {
            Set<Long> recipients = Set.copyOf(owners);
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(sessionId, GamePublisher.JOKERS, recipients)));
        }
    }

    // Submission --------------------------------------------------------------------------------------------

    private static double coordinate(JsonNode input, String name, double limit) {
        JsonNode value = input == null ? null : input.get(name);
        if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())
                || Math.abs(value.asDouble()) > limit) {
            throw GameErrors.invalid("A fake needs a valid " + name);
        }
        return value.asDouble();
    }

    /** Validated, canonical parameters of a submission. */
    private static ObjectNode parameters(Submission submission, Instant now) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        JsonNode input = submission.parameters();
        boolean fake = GameRequest.FAKE_PING.equals(submission.type());
        boolean timetable = fake && input != null && input.hasNonNull("entryId");
        boolean speedhunt = fake && input != null && input.hasNonNull("pingNumber");
        if (fake && timetable == speedhunt) {
            throw GameErrors.invalid("Choose either one of your timetable pings or a ping of the next speedhunt");
        }
        if (timetable) {
            if (input.has("runToken")) {
                throw GameErrors.invalid("A timetable fake cannot name a speedhunt");
            }
            JsonNode entry = input.get("entryId");
            if (!entry.isIntegralNumber() || !entry.canConvertToLong() || entry.asLong() <= 0) {
                throw GameErrors.invalid("Choose one of your upcoming timetable pings");
            }
            result.put("entryId", entry.asLong());
        } else if (speedhunt) {
            JsonNode ping = input.get("pingNumber");
            if (!ping.isIntegralNumber() || !ping.canConvertToInt() || ping.asInt() < 1
                    || ping.asInt() > GameSettings.MAX_PING_NUMBER) {
                throw GameErrors.invalid("Choose the ping of the next speedhunt the fake replaces");
            }
            result.put("pingNumber", ping.asInt());
            if (input.has("runToken")) {
                JsonNode token = input.get("runToken");
                if (!token.isTextual() || !token.asText().matches("[a-f0-9]{32}")) {
                    throw GameErrors.invalid("Choose a running speedhunt");
                }
                result.put("runToken", token.asText());
            }
        }
        if (fake) {
            result.put("latitude", coordinate(input, "latitude", 90));
            result.put("longitude", coordinate(input, "longitude", 180));
            return result;
        }
        if (GameRequest.BREAK.equals(submission.type()) && input != null && input.hasNonNull("startsAt")) {
            // A planned start; one that has already passed is a break that starts now.
            Instant start;
            try {
                start = Instant.parse(input.get("startsAt").asText());
            } catch (java.time.format.DateTimeParseException error) {
                throw GameErrors.invalid("The start of a break must be a time such as 2026-11-09T14:00:00Z");
            }
            if (start.isAfter(now)) {
                result.put("startsAt", start.truncatedTo(ChronoUnit.SECONDS).toString());
            }
            return result;
        }
        if (GameRequest.SPEEDHUNT_PINGS.contains(submission.type()) && input != null
                && input.has("confirmConsecutive")) {
            if (!input.get("confirmConsecutive").isBoolean()) {
                throw GameErrors.invalid("The consecutive speedhunt confirmation must be true or false");
            }
            result.put("confirmConsecutive", input.get("confirmConsecutive").asBoolean());
        }
        return result;
    }

    private static boolean onBreak(GameTransaction transaction, long sessionId, long memberId) throws SQLException {
        return GameStateRepository.activeBreak(transaction, sessionId, memberId)
                .map(item -> item.protects(transaction.now())).orElse(false);
    }

    /** Submits a request of a member; the member comes from the caller's account (its membership). */
    public GameRequest submit(GameMember caller, Submission submission) throws Exception {
        if (submission == null || submission.type() == null) {
            throw GameErrors.invalid("Request type required");
        }
        boolean joker = GameRequest.JOKER_TYPES.containsKey(submission.type());
        if (!joker && !GameRequest.HUNTER_TYPES.contains(submission.type())) {
            throw GameErrors.invalid("Unknown request type");
        }
        long sessionId = caller.gameSessionId();
        GameRequest created = transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            GameMember requester = GameMemberRepository.caller(transaction, caller);
            Long targetId = submission.targetMemberId();
            if (joker) {
                if (!GameMember.hasState(requester.role()) || requester.trackerId() == null) {
                    throw new ForbiddenException("Only players and supporters use jokers");
                }
                targetId = requester.id();
            } else {
                if (!GameMember.HUNTER.equals(requester.role())) {
                    throw new ForbiddenException("Only hunters control speedhunts");
                }
                if (targetId == null || submission.baseRevision() == null) {
                    throw GameErrors.invalid("Participant and speedhunt revision required");
                }
            }
            if (GameStateRepository.executingEpoch(transaction, sessionId) == null) {
                throw GameErrors.conflict(NOT_RUNNING);
            }
            Instant now = transaction.now();
            ObjectNode parameters = parameters(submission, now);
            GameMember target = GameMemberRepository.find(transaction, sessionId, targetId)
                    .filter(member -> member.active() && GameMember.hasState(member.role()))
                    .orElseThrow(() -> GameErrors.invalid("Unknown participant"));
            if (GameStateRepository.activeCatch(transaction, sessionId, target.id()).isPresent()) {
                throw GameErrors.conflict(joker ? "You are out of the game" : "This participant is out of the game");
            }
            if (!joker && !submission.baseRevision().equals(target.stateRevision())) {
                throw GameErrors.conflict(STALE);
            }
            GameSettings settings = GameSettings.read(transaction, sessionId);
            boolean startsRun = GameRequest.SPEEDHUNT_PINGS.contains(submission.type())
                    && GameStateRepository.activeRun(transaction, sessionId, target.id()).isEmpty();
            boolean consecutive = false;
            if (GameRequest.SPEEDHUNT_PINGS.contains(submission.type())) {
                consecutive = checkSpeedhuntPing(transaction, sessionId, submission.type(), target, settings,
                        parameters, startsRun);
            }
            if (GameRequest.BREAK.equals(submission.type())) {
                checkBreak(transaction, sessionId, target, settings, parameters);
            }
            if (GameRequest.FAKE_PING.equals(submission.type())) {
                checkFake(transaction, sessionId, target, settings, parameters);
            }
            checkOpen(transaction, sessionId, submission.type(), joker, target);

            boolean approval = settings.approvalRequired(GameRequest.APPROVAL_SETTINGS.get(submission.type()))
                    || consecutive;
            long id = GameRequestRepository.insert(transaction, sessionId, submission.type(), requester, target,
                    joker ? null : submission.baseRevision(), objectMapper.writeValueAsString(parameters), approval,
                    now.plusSeconds(EXPIRY_SECONDS));
            GameRequestRepository.Settled settled = GameRequestRepository.Settled.none();
            boolean pool = false;
            if (joker) {
                String type = GameRequest.JOKER_TYPES.get(submission.type());
                long reservation = GameJokerRepository.reserve(transaction, sessionId, target.id(), type, id, false,
                        requester.displayName(), null);
                if (GameRequest.FAKE_PING.equals(submission.type())) {
                    Long runId = parameters.has("runToken") ? runningWindows(transaction, sessionId).stream()
                            .filter(window -> window.runToken().equals(parameters.get("runToken").asText()))
                            .findFirst().orElseThrow().runId() : null;
                    GameJokerRepository.insertPlan(transaction, sessionId, reservation, target.id(),
                            parameters.get("latitude").asDouble(), parameters.get("longitude").asDouble(),
                            parameters.has("entryId") ? parameters.get("entryId").asLong() : null,
                            parameters.has("pingNumber") ? parameters.get("pingNumber").asInt() : null, runId);
                }
                settled.members().add(target.id());
            } else if (startsRun) {
                // The pool joker is used by the run's first ping, whichever delivery sends it.
                GameJokerRepository.reserve(transaction, sessionId, null, GameJokerRepository.SPEEDHUNT, id, false,
                        requester.displayName(), null);
                pool = true;
            }
            transaction.bumpSession();
            publishRequests(transaction, sessionId, new GameRequestRepository.Settled(settled.members(), pool));
            return memberView(GameRequestRepository.find(transaction, sessionId, id).orElseThrow(),
                    labelsFor(transaction, requester));
        });
        return created;
    }

    /** @return whether the request needs approval because it would repeat the previous speedhunt's target */
    private static boolean checkSpeedhuntPing(GameTransaction transaction, long sessionId, String type,
                                              GameMember target, GameSettings settings, ObjectNode parameters,
                                              boolean startsRun) throws SQLException {
        if (onBreak(transaction, sessionId, target.id())) {
            throw GameErrors.conflict("This participant is on a break");
        }
        boolean live = GameRequest.LIVE_PING.equals(type);
        if (live && settings.liveDurationSeconds() == null) {
            throw GameErrors.conflict(LIVE_DURATION_REQUIRED);
        }
        boolean consecutive = false;
        if (startsRun && !settings.allowConsecutiveSpeedhunts() && GameStateRepository.lastRun(transaction, sessionId)
                .map(run -> run.targetMemberId() == target.id()).orElse(false)) {
            if (settings.limitsBlock() || !settings.approvalRequired(GameRequest.APPROVAL_SETTINGS.get(type))) {
                throw GameErrors.conflict(REPEAT_SPEEDHUNT);
            }
            if (!parameters.path("confirmConsecutive").asBoolean(false)) {
                throw GameErrors.conflict(REPEAT_SPEEDHUNT + "; confirm that you want to request approval");
            }
            consecutive = true;
        }
        // A ping that would start a speedhunt needs a joker of the hunters' pool, reserved with the request.
        if (startsRun && GameJokerRepository.available(transaction, sessionId, null,
                GameJokerRepository.SPEEDHUNT) <= 0) {
            throw GameErrors.conflict(NO_SPEEDHUNT_JOKER);
        }
        return consecutive;
    }

    private static void checkBreak(GameTransaction transaction, long sessionId, GameMember target,
                                   GameSettings settings, ObjectNode parameters) throws SQLException {
        Double minutes = settings.breakMinutes();
        if (minutes == null) {
            throw GameErrors.conflict("Breaks are not set up for this game");
        }
        boolean planned = parameters.has("startsAt");
        // A planned break waits at its start while any speedhunt runs; only a break that starts now is refused.
        if (!planned && onBreak(transaction, sessionId, target.id())) {
            throw GameErrors.conflict("You are already on a break");
        }
        if (!planned && GameStateRepository.speedhuntRunning(transaction, sessionId)) {
            throw GameErrors.conflict(BREAK_UNAVAILABLE);
        }
        Instant start = planned ? Instant.parse(parameters.get("startsAt").asText()) : transaction.now();
        if (GameJokerService.breakOverlaps(transaction, sessionId, target.id(), start,
                GameSettings.breakSeconds(minutes))) {
            throw GameErrors.conflict(BREAK_OVERLAP);
        }
    }

    private static void checkFake(GameTransaction transaction, long sessionId, GameMember target,
                                  GameSettings settings, ObjectNode parameters) throws SQLException {
        if (parameters.has("entryId")) {
            Instant scheduled = fakeEntry(transaction, sessionId, target.id(), parameters.get("entryId").asLong());
            Optional<GameState.Break> pause = GameStateRepository.activeBreak(transaction, sessionId, target.id());
            if (pause.isPresent() && pause.get().protects(transaction.now())
                    && !pause.get().deadline().isBefore(scheduled)) {
                throw GameErrors.conflict("This ping falls inside your break");
            }
            if (GameJokerRepository.entryTaken(transaction, sessionId, parameters.get("entryId").asLong())) {
                throw GameErrors.conflict("This ping already has a fake");
            }
            parameters.put("scheduledAt", scheduled.toString());
            return;
        }
        Integer limit = settings.pingsPerSpeedhunt();
        int number = parameters.get("pingNumber").asInt();
        if (limit != null && number > limit) {
            throw GameErrors.conflict("A speedhunt has " + limit + " pings; choose one of them");
        }
        if (parameters.has("runToken")) {
            RunningWindow window = openWindow(transaction, sessionId, parameters);
            if (window == null) {
                throw GameErrors.conflict("That speedhunt ping is no longer available; choose another ping");
            }
            if (GameJokerRepository.runningHeld(transaction, sessionId, target.id(), window.runId())) {
                throw GameErrors.conflict("You already have a fake for this speedhunt");
            }
        } else if (GameJokerRepository.unboundHeld(transaction, sessionId, target.id())) {
            // One unbound speedhunt fake per participant; it waits for the next speedhunt, whoever it targets.
            throw GameErrors.conflict("You already have a fake for the next speedhunt");
        }
    }

    /** The running speedhunt a fake names, while its ping is still open; null otherwise. */
    private static RunningWindow openWindow(GameTransaction transaction, long sessionId, JsonNode parameters)
            throws SQLException {
        String token = parameters.path("runToken").asText();
        int number = parameters.path("pingNumber").asInt();
        for (RunningWindow window : runningWindows(transaction, sessionId)) {
            if (window.runToken().equals(token) && number >= window.firstPing() && number <= window.lastPing()) {
                return window;
            }
        }
        return null;
    }

    /**
     * The scheduled time of a timetable entry a fake may replace: the member's own active entry that reaches hunters
     * (blank or a target code with hunters) and is at least the cutoff ahead.
     */
    private static Instant fakeEntry(GameTransaction transaction, long sessionId, long memberId, long entryId)
            throws SQLException {
        var entry = GameTimetableRepository.find(transaction, sessionId, entryId)
                .filter(row -> row.active() && row.memberId() == memberId)
                .orElseThrow(() -> GameErrors.invalid("Choose one of your upcoming timetable pings"));
        if (!GameTimetableService.fakeEligible(entry.content())) {
            throw GameErrors.conflict("This ping does not reach the hunters, so it cannot carry a fake");
        }
        if (!entry.scheduledAt().isAfter(transaction.now().plusSeconds(GameTimetableService.FAKE_CUTOFF_SECONDS))) {
            throw GameErrors.conflict("This ping is too close to carry a fake");
        }
        return entry.scheduledAt();
    }

    /** A participant has one open request per joker type; hunters' actions on one participant are serialized. */
    private static void checkOpen(GameTransaction transaction, long sessionId, String type, boolean joker,
                                  GameMember target) throws SQLException {
        for (GameRequest request : GameRequestRepository.open(transaction, sessionId)) {
            if (request.targetMemberId() == null || request.targetMemberId() != target.id()) {
                continue;
            }
            if (joker ? request.type().equals(type) : GameRequest.HUNTER_TYPES.contains(request.type())) {
                throw GameErrors.conflict(joker ? "You already have an open request for this joker"
                        : "Another request for this participant is still open");
            }
        }
    }

    // Members -----------------------------------------------------------------------------------------------

    /** Hunters and spectators see every speedhunt request of their session; others only their own. */
    public List<GameRequest> list(GameMember viewer, int requestedLimit) throws Exception {
        int limit = requestedLimit <= 0 ? 50 : Math.min(requestedLimit, 200);
        boolean shared = GameMember.HUNTER.equals(viewer.role()) || GameMember.SPECTATOR.equals(viewer.role());
        return transactions.read(transaction -> {
            Map<Long, String> labels = labelsFor(transaction, viewer);
            String condition = shared ? "(requestermemberid = ? OR type IN ('" + String.join("', '",
                    GameRequest.HUNTER_TYPES.stream().sorted().toList()) + "'))" : "requestermemberid = ?";
            List<GameRequest> result = new ArrayList<>();
            for (GameRequest request : GameRequestRepository.list(transaction, viewer.gameSessionId(), condition,
                    limit, viewer.id())) {
                result.add(memberView(request, labels));
            }
            return result;
        });
    }

    /** The requester cancels a request that waits for approval or execution; its joker returns. */
    public GameRequest cancel(GameMember requester, long id) throws Exception {
        long sessionId = requester.gameSessionId();
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            GameMemberRepository.caller(transaction, requester);
            GameRequest request = GameRequestRepository.find(transaction, sessionId, id)
                    .orElseThrow(() -> GameErrors.notFound("Game request not found"));
            if (request.requesterMemberId() == null || request.requesterMemberId() != requester.id()) {
                throw new ForbiddenException("Only the requester can cancel this request");
            }
            if (!GameRequest.PENDING.equals(request.status()) && !GameRequest.APPROVED.equals(request.status())) {
                throw GameErrors.conflict("The request can no longer be cancelled");
            }
            var settled = GameRequestRepository.finish(transaction, request, GameRequest.CANCELLED,
                    "Cancelled by the requester", null, false);
            transaction.bumpSession();
            publishRequests(transaction, sessionId, settled);
            return memberView(GameRequestRepository.find(transaction, sessionId, id).orElseThrow(),
                    labelsFor(transaction, requester));
        });
    }

    // Controller --------------------------------------------------------------------------------------------

    public List<GameRequest> operatorList(long sessionId, boolean openOnly, int requestedLimit) throws Exception {
        int limit = requestedLimit <= 0 ? 100 : Math.min(requestedLimit, 500);
        return transactions.read(transaction -> {
            GameDocumentService.sessionExists(transaction, sessionId);
            if (openOnly) {
                List<GameRequest> open = GameRequestRepository.open(transaction, sessionId);
                return open.size() > limit ? open.subList(0, limit) : open;
            }
            return GameRequestRepository.list(transaction, sessionId, null, limit);
        });
    }

    private static GameRequest load(GameTransaction transaction, long sessionId, long id) throws SQLException {
        return GameRequestRepository.find(transaction, sessionId, id)
                .orElseThrow(() -> GameErrors.notFound("Game request not found"));
    }

    /**
     * The controller's decision on a request: {@code await} (operator approval needed), {@code approve} or
     * {@code reject}.
     */
    public GameRequest decide(long sessionId, long id, String decision, String message, String detail)
            throws Exception {
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            GameRequest request = load(transaction, sessionId, id);
            var settled = GameRequestRepository.Settled.none();
            switch (decision == null ? "" : decision) {
                case "await" -> {
                    if (!GameRequest.PENDING.equals(request.status())) {
                        throw GameErrors.conflict("Only pending requests can wait for approval");
                    }
                    transaction.update("UPDATE tc_game_requests SET approvalrequired = ?, message = ?, updatedat = ?"
                            + " WHERE gamesessionid = ? AND id = ?", true, GameJokerRepository.clip(message, 512),
                            transaction.now(), sessionId, id);
                }
                case "approve" -> {
                    if (!GameRequest.PENDING.equals(request.status())) {
                        throw GameErrors.conflict("Only pending requests can be approved");
                    }
                    GameRequestRepository.finish(transaction, request, GameRequest.APPROVED, message, detail, true);
                }
                case "reject" -> {
                    if (!GameRequest.PENDING.equals(request.status())
                            && !GameRequest.APPROVED.equals(request.status())) {
                        throw GameErrors.conflict("The request is no longer open");
                    }
                    settled = GameRequestRepository.finish(transaction, request, GameRequest.REJECTED, message,
                            detail, true);
                }
                default -> throw GameErrors.invalid("Decision must be await, approve or reject");
            }
            transaction.bumpSession();
            publishRequests(transaction, sessionId, settled);
            return load(transaction, sessionId, id);
        });
    }

    /** Claims a pending or approved request for execution; an expired request can no longer be claimed. */
    public GameRequest claim(long sessionId, long id, long epoch) throws Exception {
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            GameRequest request = load(transaction, sessionId, id);
            if (!GameRequest.PENDING.equals(request.status()) && !GameRequest.APPROVED.equals(request.status())) {
                throw GameErrors.conflict("The request is no longer open");
            }
            if (!request.expiresAt().isAfter(transaction.now())) {
                var settled = GameRequestRepository.finish(transaction, request, GameRequest.EXPIRED,
                        "No one handled this request in time", null, false);
                transaction.bumpSession();
                publishRequests(transaction, sessionId, settled);
                return load(transaction, sessionId, id);
            }
            transaction.update("UPDATE tc_game_requests SET status = ?, epoch = ?, updatedat = ?"
                    + " WHERE gamesessionid = ? AND id = ?", GameRequest.EXECUTING, epoch, transaction.now(),
                    sessionId, id);
            transaction.bumpSession();
            publishRequests(transaction, sessionId, GameRequestRepository.Settled.none());
            return load(transaction, sessionId, id);
        });
    }

    /**
     * Records request outcomes inside an effects transaction: {@code succeeded} after execution, {@code failed} or
     * {@code rejected} while open. Repeating the outcome a request already has changes nothing. A fake is checked
     * again before it is armed; a refusal is a 400, so the Tool records a failure and the joker returns.
     */
    static List<GameRequest> complete(GameTransaction transaction, long sessionId, List<Completion> completions,
                                      GameOperations applied) throws SQLException {
        List<GameRequest> result = new ArrayList<>();
        for (Completion completion : completions) {
            if (completion == null || completion.id() == null || completion.status() == null) {
                throw GameErrors.invalid("Invalid request outcome");
            }
            GameRequest request = load(transaction, sessionId, completion.id());
            boolean allowed = switch (completion.status()) {
                case GameRequest.SUCCEEDED -> GameRequest.EXECUTING.equals(request.status());
                case GameRequest.FAILED -> request.open();
                case GameRequest.REJECTED -> GameRequest.PENDING.equals(request.status())
                        || GameRequest.APPROVED.equals(request.status());
                default -> false;
            };
            if (!allowed) {
                if (request.status().equals(completion.status())) {
                    result.add(request);
                    continue;
                }
                throw GameErrors.conflict("Request " + completion.id() + " cannot become " + completion.status()
                        + " from " + request.status());
            }
            if (GameRequest.SUCCEEDED.equals(completion.status()) && GameRequest.FAKE_PING.equals(request.type())) {
                if (request.parameters().hasNonNull("entryId")) {
                    try {
                        fakeEntry(transaction, sessionId, request.targetMemberId(),
                                request.parameters().path("entryId").asLong(0));
                    } catch (jakarta.ws.rs.WebApplicationException error) {
                        throw GameErrors.invalid("The fake cannot be armed: " + error.getMessage());
                    }
                }
                if (request.parameters().hasNonNull("runToken")
                        && openWindow(transaction, sessionId, request.parameters()) == null) {
                    throw GameErrors.invalid("That speedhunt ping is no longer available; choose another ping");
                }
            }
            var outcome = GameRequestRepository.finish(transaction, request, completion.status(),
                    completion.message(), completion.detail(), GameRequest.REJECTED.equals(completion.status()));
            applied.markRequestsChanged();
            if (GameRequest.SUCCEEDED.equals(completion.status()) && GameRequest.BREAK.equals(request.type())
                    && !request.plannedBreak()) {
                applied.succeededBreaks().add(request.id());
            }
            applied.jokerMembers().addAll(outcome.members());
            if (outcome.pool()) {
                applied.markPoolChanged();
            }
            result.add(load(transaction, sessionId, completion.id()));
        }
        return result;
    }

    // Expiry and change of control --------------------------------------------------------------------------

    /** Ends open requests of every session in one transaction per session, with their jokers. */
    private Set<Long> endOpen(String condition, Instant before, String message) throws Exception {
        List<Long> sessions = transactions.read(transaction -> transaction.list(
                "SELECT DISTINCT gamesessionid FROM tc_game_requests WHERE " + condition + " ORDER BY gamesessionid",
                rows -> rows.getLong(1), before == null ? new Object[0] : new Object[] {before}));
        Set<Long> affected = new LinkedHashSet<>();
        for (long sessionId : sessions) {
            try {
                boolean changed = transactions.write(transaction -> {
                    transaction.lockSession(sessionId);
                    var settled = GameRequestRepository.Settled.none();
                    boolean pool = false;
                    boolean any = false;
                    for (GameRequest request : GameRequestRepository.open(transaction, sessionId)) {
                        boolean expiring = GameRequest.PENDING.equals(request.status())
                                || GameRequest.APPROVED.equals(request.status());
                        if (before != null && (!expiring || request.expiresAt().isAfter(before))) {
                            continue;
                        }
                        String status = expiring ? GameRequest.EXPIRED : GameRequest.FAILED;
                        var outcome = GameRequestRepository.finish(transaction, request, status, message, null,
                                false);
                        settled.add(outcome);
                        pool |= outcome.pool();
                        any = true;
                    }
                    if (any) {
                        transaction.bumpSession();
                        publishRequests(transaction, sessionId,
                                new GameRequestRepository.Settled(settled.members(), pool));
                    }
                    return any;
                });
                if (changed) {
                    affected.add(sessionId);
                }
            } catch (jakarta.ws.rs.NotFoundException deletedMeanwhile) {
                // The session was deleted with its requests after the scan.
            }
        }
        return affected;
    }

    /** Expires requests awaiting approval or execution past their deadline; returns the affected sessions. */
    public Set<Long> expire() throws Exception {
        Instant now = transactions.now();
        return endOpen("status IN ('pending', 'approved') AND expiresat <= ?", now,
                "No one handled this request in time");
    }

    /**
     * Ends every open request when control of the game changes: waiting ones expire and executing ones fail. Their
     * history is kept.
     */
    public Set<Long> invalidateOpen(String reason) throws Exception {
        return endOpen("status IN ('pending', 'approved', 'executing')", null, reason);
    }

}
