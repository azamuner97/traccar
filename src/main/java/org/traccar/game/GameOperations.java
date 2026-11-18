/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Applies typed game operations (plan §3.2) inside one write transaction that holds the session lock, validating
 * each against the current state of its row. It is shared by {@link GameStateService} and the effects delivery,
 * and collects what the transaction must publish after commit.
 *
 * <p>Execution needs a running game: {@code pingDelivered} and {@code liveUploadStarted} answer 409
 * ({@value GameExecutionService#NOT_RUNNING}) on a stopped session, while ending and cleanup operations
 * ({@code liveUploadEnded}, {@code pingFailed}, {@code runEnded}, breaks, catches) stay allowed.
 *
 * <p>Member state revisions advance only with changes that hunters and spectators can observe: catches, runs and
 * pings, breaks that start or end, and live uploads that reach hunters or belong to a speedhunt. Private changes
 * (planned, waiting, cancelled and missed breaks, fakes, joker uses, hunter relays) leave them unchanged, because
 * hunters see member revisions (plan §2.4).
 */
public final class GameOperations {

    /** The result of one operation, operator data: it may name joker fakes. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(int index, String operation, Long id, Long runId, Integer number, String source,
                         String status) {
    }

    /** What happened to a joker fake or a pool joker with the operations. */
    public record FakeOutcome(long reservationId, String status) {
    }

    /** A joker fake the server applies to the target's hunter mirror positions of this delivery. */
    record AppliedFake(long memberId, double latitude, double longitude, boolean live) {
    }

    static final String BOUND = "bound";
    /** The longest interval of a live upload: one hour. */
    static final int MAX_INTERVAL_SECONDS = 3600;
    static final String BREAK_UNAVAILABLE = "A break cannot start right now; try again later";

    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();

    private final GameTransaction transaction;
    private final long session;
    private final String actor;
    private final ObjectMapper objectMapper;
    private final GamePublisher publisher;
    private GameSettings settings;
    private Long epoch;

    private final List<Result> results = new ArrayList<>();
    private final List<FakeOutcome> fakes = new ArrayList<>();
    private final Set<Long> jokerMembers = new LinkedHashSet<>();
    private final Map<Long, AppliedFake> appliedFakes = new HashMap<>();
    private final Set<Long> immediateBreaks = new LinkedHashSet<>();
    private final Set<Long> succeededBreaks = new LinkedHashSet<>();
    private boolean statusChanged;
    private boolean historyChanged;
    private boolean requestsChanged;
    private boolean poolChanged;

    /**
     * Operations of one transaction that holds the session lock. {@code publisher} receives the execution events
     * of live uploads (written in the transaction, pushed after commit).
     */
    GameOperations(GameTransaction transaction, long session, String actor, ObjectMapper objectMapper,
                   GamePublisher publisher) {
        this.transaction = transaction;
        this.session = session;
        this.actor = actor;
        this.objectMapper = objectMapper == null ? DEFAULT_MAPPER : objectMapper;
        this.publisher = publisher;
    }

    /** The controller epoch that fenced these operations (an effects delivery); null for the server's own. */
    Long epoch() {
        return epoch;
    }

    GameOperations fencedBy(long value) {
        epoch = value;
        return this;
    }

    /** Who applies these operations: {@code Tool} for a delivery, {@code Server} for its own execution. */
    String actor() {
        return actor;
    }

    // What the operations changed ---------------------------------------------------------------------------

    List<Result> results() {
        return results;
    }

    List<FakeOutcome> fakes() {
        return fakes;
    }

    /** Members whose jokers changed: only they (and the operators) are told. */
    Set<Long> jokerMembers() {
        return jokerMembers;
    }

    /** The joker fakes to apply to the targets' hunter mirror positions of this delivery, by member. */
    Map<Long, AppliedFake> appliedFakes() {
        return appliedFakes;
    }

    /** Immediate break requests whose break started with these operations. */
    Set<Long> immediateBreaks() {
        return immediateBreaks;
    }

    /** Immediate break requests that succeeded with this delivery; each must start its break with it. */
    Set<Long> succeededBreaks() {
        return succeededBreaks;
    }

    boolean statusChanged() {
        return statusChanged;
    }

    boolean historyChanged() {
        return historyChanged;
    }

    boolean requestsChanged() {
        return requestsChanged;
    }

    boolean poolChanged() {
        return poolChanged;
    }

    void markRequestsChanged() {
        requestsChanged = true;
    }

    void markPoolChanged() {
        poolChanged = true;
    }

    private GameSettings settings() throws SQLException {
        if (settings == null) {
            settings = GameSettings.read(transaction, session);
        }
        return settings;
    }

    /**
     * Refuses what only the server applies: a live ping is delivered by its upload's first sample, and samples are
     * the server's own ticks ({@link GameLiveUploadRunner}). Checked for Tool-sent operations.
     */
    static void checkSent(List<GameOperation> operations) {
        for (GameOperation operation : operations) {
            if (operation instanceof GameOperation.PingDelivered ping && GameState.LIVE.equals(ping.mode())) {
                throw GameErrors.invalid("A live ping is delivered by the server with its live upload's first sample");
            }
            if (operation instanceof GameOperation.LiveUploadSample) {
                throw GameErrors.invalid("Live upload samples are the server's own ticks");
            }
        }
    }

    // Locking -----------------------------------------------------------------------------------------------

    /** The members whose state the operations touch, to lock in ascending order before applying them. */
    static Set<Long> members(GameTransaction transaction, long session, List<GameOperation> operations)
            throws SQLException {
        Set<Long> result = new TreeSet<>();
        for (GameOperation operation : operations) {
            switch (operation) {
                case GameOperation.Catch item -> result.add(item.memberId());
                case GameOperation.Release item -> result.add(item.memberId());
                case GameOperation.PingDelivered item -> result.add(item.targetMemberId());
                case GameOperation.PingFailed item -> result.add(item.targetMemberId());
                case GameOperation.RunEnded item -> result.add(item.targetMemberId());
                case GameOperation.LiveUploadStarted item -> {
                    if (item.sourceMemberId() != null) {
                        result.add(item.sourceMemberId());
                    }
                    if (item.recipientMemberId() != null) {
                        result.add(item.recipientMemberId());
                    }
                }
                case GameOperation.LiveUploadSample item -> GameStateRepository.upload(transaction, session,
                        item.liveUploadId()).ifPresent(upload -> result.add(upload.memberId()));
                case GameOperation.LiveUploadEnded item -> GameStateRepository.upload(transaction, session,
                        item.liveUploadId()).ifPresent(upload -> result.add(upload.memberId()));
                case GameOperation.BreakPlanned item -> result.add(item.memberId());
                case GameOperation.BreakStarted item -> {
                    if (item.requestId() != null) {
                        GameRequestRepository.find(transaction, session, item.requestId())
                                .map(GameRequest::targetMemberId).ifPresent(result::add);
                    }
                }
                case GameOperation.BreakCancelled item -> breakMember(transaction, session, item.breakId(), result);
                case GameOperation.Checkpoint item -> {
                }
            }
        }
        result.removeIf(id -> id <= 0);
        return result;
    }

    private static void breakMember(GameTransaction transaction, long session, long breakId, Set<Long> result)
            throws SQLException {
        GameStateRepository.pause(transaction, session, breakId).ifPresent(item -> result.add(item.memberId()));
    }

    // Helpers -----------------------------------------------------------------------------------------------

    /** An active player or supporter of the session. */
    private GameMember participant(long memberId) throws SQLException {
        GameMember member = GameMemberRepository.find(transaction, session, memberId)
                .orElseThrow(() -> GameErrors.invalid("Member " + memberId + " not found in this game session"));
        if (!GameMember.hasState(member.role())) {
            throw GameErrors.invalid("The " + member.label() + " is not a player or supporter");
        }
        if (!member.active()) {
            throw GameErrors.conflict("The " + member.label() + " is retired");
        }
        return member;
    }

    private boolean caught(long memberId) throws SQLException {
        return GameStateRepository.activeCatch(transaction, session, memberId).isPresent();
    }

    private boolean onBreak(long memberId) throws SQLException {
        return GameStateRepository.activeBreak(transaction, session, memberId)
                .map(item -> item.protects(transaction.now())).orElse(false);
    }

    /** Advances the member state revision for a change hunters can observe, and marks the status changed. */
    private void visible(long memberId) throws SQLException {
        transaction.bumpMemberState(memberId);
        statusChanged = true;
    }

    private void result(int index, GameOperation operation, Long id, Long runId, Integer number, String source,
                        String status) {
        results.add(new Result(index, operation.name(), id, runId, number, source, status));
    }

    private GameState.Break pendingOrActiveBreak(long breakId) throws SQLException {
        return GameStateRepository.pause(transaction, session, breakId)
                .orElseThrow(() -> GameErrors.invalid("Break " + breakId + " not found in this game session"));
    }

    private GameState.LiveUpload upload(long id) throws SQLException {
        GameState.LiveUpload upload = GameStateRepository.upload(transaction, session, id)
                .orElseThrow(() -> GameErrors.invalid("Live upload " + id + " not found in this game session"));
        if (!upload.active()) {
            throw GameErrors.conflict("Live upload " + id + " has ended");
        }
        return upload;
    }

    private Instant deadline(String value, String label) {
        Instant deadline = GameTime.parseOptional(value, label);
        if (deadline != null && !deadline.isAfter(transaction.now())) {
            throw GameErrors.conflict("The " + label + " has passed");
        }
        return deadline;
    }

    // Applying ----------------------------------------------------------------------------------------------

    void apply(int index, GameOperation operation) throws Exception {
        switch (operation) {
            case GameOperation.Catch item -> catchMember(index, item);
            case GameOperation.Release item -> release(index, item);
            case GameOperation.PingDelivered item -> pingDelivered(index, item);
            case GameOperation.PingFailed item -> pingFailed(index, item);
            case GameOperation.RunEnded item -> runEnded(index, item);
            case GameOperation.LiveUploadStarted item -> liveUploadStarted(index, item);
            case GameOperation.LiveUploadSample item -> liveUploadSample(index, item);
            case GameOperation.LiveUploadEnded item -> liveUploadEnded(index, item);
            case GameOperation.BreakPlanned item -> breakPlanned(index, item);
            case GameOperation.BreakStarted item -> breakStarted(index, item);
            case GameOperation.BreakCancelled item -> breakCancelled(index, item);
            case GameOperation.Checkpoint item -> {
                var checkpoint = GameDocumentService.putCheckpoint(transaction, objectMapper, session,
                        item.component(), item.expectedRevision(), item.schemaVersion(), item.document());
                result(index, item, checkpoint.revision(), null, null, null, null);
            }
        }
    }

    // Catches -----------------------------------------------------------------------------------------------

    private void catchMember(int index, GameOperation.Catch item) throws Exception {
        GameMember member = participant(item.memberId());
        if (caught(member.id())) {
            throw GameErrors.conflict("The " + member.label() + " is already caught");
        }
        long id = GameStateRepository.insertCatch(transaction, session, member.id(),
                item.actor() == null ? actor : item.actor());
        visible(member.id());
        memberOut(member.id());
        result(index, item, id, null, null, null, null);
    }

    /**
     * A caught member's open requests end and return their jokers, their armed plans (fakes not bound yet,
     * planned breaks) are released, and an active break ends; its joker stays used.
     */
    private void memberOut(long memberId) throws Exception {
        var settled = GameRequestRepository.cancelOpen(transaction, session, memberId, GameRequest.CANCELLED,
                "You are out of the game");
        if (!settled.members().isEmpty() || settled.pool()) {
            requestsChanged = true;
        }
        jokerMembers.addAll(settled.members());
        poolChanged |= settled.pool();
        if (GameJokerService.releasePlans(transaction, session, memberId, "Its owner is out of the game")) {
            jokerMembers.add(memberId);
        }
        Optional<GameState.Break> active = GameStateRepository.activeBreak(transaction, session, memberId);
        if (active.isPresent()) {
            GameStateRepository.finishBreak(transaction, session, active.get().id(), GameState.ENDED);
        }
        // Their live uploads end, and so do the live relays to them.
        for (GameState.LiveUpload upload : GameStateRepository.activeUploads(transaction, session)) {
            if (upload.memberId() == memberId) {
                endUpload(upload, GameLiveEvents.CAUGHT);
            }
        }
    }

    private void release(int index, GameOperation.Release item) throws Exception {
        GameMember member = participant(item.memberId());
        GameState.Catch active = GameStateRepository.activeCatch(transaction, session, member.id())
                .orElseThrow(() -> GameErrors.conflict("The " + member.label() + " is not caught"));
        GameStateRepository.release(transaction, session, active.id());
        visible(member.id());
        result(index, item, active.id(), null, null, null, null);
    }

    // Speedhunts --------------------------------------------------------------------------------------------

    private void pingDelivered(int index, GameOperation.PingDelivered item) throws Exception {
        boolean live = GameState.LIVE.equals(item.mode());
        if (!live && !GameState.STATIC.equals(item.mode())) {
            throw GameErrors.invalid("A ping is static or live");
        }
        if (!GameState.REAL.equals(item.source()) && !GameState.OPERATOR_FAKE.equals(item.source())) {
            throw GameErrors.invalid("A ping's source is real or operatorFake");
        }
        GameExecutionService.requireRunning(transaction, session);
        GameMember target = participant(item.targetMemberId());
        if (caught(target.id())) {
            throw GameErrors.conflict("The " + target.label() + " is out of the game");
        }
        if (onBreak(target.id())) {
            throw GameErrors.conflict("The " + target.label() + " is on a break");
        }
        if (item.requestId() != null
                && GameRequestRepository.find(transaction, session, item.requestId()).isEmpty()) {
            throw GameErrors.invalid("Request " + item.requestId() + " not found in this game session");
        }
        GameState.LiveUpload upload = null;
        if (live) {
            if (item.liveUploadId() == null) {
                throw GameErrors.invalid("A live ping names its live upload");
            }
            upload = upload(item.liveUploadId());
            if (!GameState.SPEEDHUNT_PING.equals(upload.purpose()) || upload.sourceMemberId() != target.id()) {
                throw GameErrors.invalid("Live upload " + upload.id() + " is not a live ping of the "
                        + target.label());
            }
            if (upload.pingId() != null) {
                throw GameErrors.conflict("Live upload " + upload.id() + " already delivered its ping");
            }
        } else if (item.liveUploadId() != null) {
            throw GameErrors.invalid("A static ping has no live upload");
        }

        Optional<GameState.Run> active = GameStateRepository.activeRun(transaction, session, target.id());
        long runId;
        if (active.isEmpty()) {
            if (item.number() != 1) {
                throw GameErrors.conflict("The " + target.label() + " has no running speedhunt; ping "
                        + item.number() + " cannot be delivered");
            }
            if (item.toHunters() == null || item.toPlayers() == null || item.toSupporters() == null) {
                throw GameErrors.invalid("The first ping of a speedhunt names its audiences");
            }
            runId = startRun(target, item);
        } else {
            runId = active.get().id();
            int next = GameStateRepository.lastPingNumber(transaction, session, runId) + 1;
            if (item.number() != next) {
                throw GameErrors.conflict("Ping " + item.number() + " is not the next ping (" + next
                        + ") of this speedhunt");
            }
        }

        // Armed fakes planned on this ping of a running speedhunt are used for every owner; only the target's own
        // applies. A request for one that is still open fails when its ping has passed.
        GameJokerRepository.Plan own = null;
        for (var plan : GameJokerRepository.plans(transaction, session,
                "p.boundrunid = ? AND p.pingnumber = ? AND " + GameJokerRepository.ARMED, runId, item.number())) {
            GameJokerRepository.consume(transaction,
                    GameJokerRepository.reservation(transaction, session, plan.reservationId()).orElseThrow(),
                    "Tool", "Used on the planned speedhunt ping");
            fakes.add(new FakeOutcome(plan.reservationId(), GameJokerRepository.CONSUMED));
            jokerMembers.add(plan.memberId());
            if (plan.memberId() == target.id()) {
                own = plan;
            }
        }
        if (own == null) {
            own = GameJokerRepository.plans(transaction, session, "p.boundrunid = ? AND p.pingnumber = ?"
                    + " AND p.memberid = ? AND r.status = 'consumed'", runId, item.number(), target.id())
                    .stream().findFirst().orElse(null);
        }
        String source = item.source();
        Long fakePlan = null;
        if (own != null && GameState.REAL.equals(source)) {
            // An operator fake on the same ping wins; the joker is still used.
            source = GameState.JOKER_FAKE;
            fakePlan = own.reservationId();
            appliedFakes.put(target.id(), new AppliedFake(target.id(), own.latitude(), own.longitude(), live));
        }
        long pingId = GameStateRepository.insertPing(transaction, session, runId, item.number(), item.mode(), source,
                item.requestId(), fakePlan);
        if (upload != null) {
            GameStateRepository.linkPing(transaction, session, upload.id(), pingId);
            GameStateRepository.sample(transaction, session, upload.id());
        }
        visible(target.id());
        historyChanged = true;
        result(index, item, pingId, runId, item.number(), source, null);
    }

    /**
     * Creates the run of a first delivered ping: uses the pool joker held for the target (by a hunter's request,
     * open or armed) or one more pool joker, and binds every armed speedhunt fake to the run.
     */
    private long startRun(GameMember target, GameOperation.PingDelivered item) throws SQLException {
        GameJokerRepository.Reservation held = poolReservation(target.id(), false);
        GameJokerRepository.Reservation used;
        if (held != null) {
            GameJokerRepository.consume(transaction, held, item.startedBy() == null ? actor : item.startedBy(),
                    "Speedhunt started");
            used = held;
        } else {
            used = GameJokerRepository.useDirect(transaction, session, null, GameJokerRepository.SPEEDHUNT,
                    Boolean.TRUE.equals(item.overAllocate()), item.startedBy() == null ? actor : item.startedBy(),
                    "Speedhunt started");
        }
        poolChanged = true;
        Long requestId = item.requestId() != null ? item.requestId() : used.requestId();
        long runId = GameStateRepository.insertRun(transaction, session, target.id(), item.toHunters(),
                item.toPlayers(), item.toSupporters(), item.startedBy() == null ? actor : item.startedBy(),
                requestId, used.id());
        for (var plan : GameJokerRepository.plans(transaction, session,
                "p.unboundmemberid IS NOT NULL AND " + GameJokerRepository.ARMED)) {
            GameJokerRepository.bind(transaction, session, plan.reservationId(), runId);
            GameJokerRepository.consume(transaction,
                    GameJokerRepository.reservation(transaction, session, plan.reservationId()).orElseThrow(),
                    "Tool", "Bound to a speedhunt");
            fakes.add(new FakeOutcome(plan.reservationId(), BOUND));
            jokerMembers.add(plan.memberId());
        }
        return runId;
    }

    /** The pool reservation held for a target: by an open or armed hunter's request (armed only, if asked). */
    private GameJokerRepository.Reservation poolReservation(long targetMemberId, boolean armedOnly)
            throws SQLException {
        for (var reservation : GameJokerRepository.reserved(transaction, session, null, true)) {
            if (reservation.requestId() == null || armedOnly && !reservation.armed()) {
                continue;
            }
            var request = GameRequestRepository.find(transaction, session, reservation.requestId());
            if (request.isPresent() && request.get().targetMemberId() != null
                    && request.get().targetMemberId() == targetMemberId) {
                return reservation;
            }
        }
        return null;
    }

    private void pingFailed(int index, GameOperation.PingFailed item) throws Exception {
        GameMember target = participant(item.targetMemberId());
        Long released = null;
        if (GameStateRepository.activeRun(transaction, session, target.id()).isEmpty()) {
            GameJokerRepository.Reservation armed = poolReservation(target.id(), true);
            if (armed != null) {
                GameJokerRepository.release(transaction, armed, "Tool",
                        item.reason() == null ? "The ping reached nobody" : item.reason());
                fakes.add(new FakeOutcome(armed.id(), GameJokerRepository.RELEASED));
                poolChanged = true;
                released = armed.id();
            }
        }
        result(index, item, released, null, null, null, null);
    }

    private void runEnded(int index, GameOperation.RunEnded item) throws Exception {
        GameMember target = participant(item.targetMemberId());
        if (!GameState.COMPLETED.equals(item.outcome()) && !GameState.TERMINATED.equals(item.outcome())) {
            throw GameErrors.invalid("A speedhunt is completed or terminated");
        }
        GameState.Run run = GameStateRepository.activeRun(transaction, session, target.id())
                .orElseThrow(() -> GameErrors.conflict("The " + target.label() + " has no running speedhunt"));
        endRun(run, item.outcome(), item.reason());
        // The run's live ping ends with it.
        for (GameState.LiveUpload upload : GameStateRepository.activeUploads(transaction, session)) {
            if (GameState.SPEEDHUNT_PING.equals(upload.purpose()) && upload.memberId() == target.id()) {
                endUpload(upload, GameLiveEvents.RUN_ENDED);
            }
        }
        result(index, item, run.id(), run.id(), null, null, item.outcome());
    }

    /** Ends a run; armed fakes planned on pings it did not reach are returned (open requests for them fail later). */
    private void endRun(GameState.Run run, String outcome, String reason) throws SQLException {
        GameStateRepository.endRun(transaction, session, run.id(), outcome, reason);
        for (var plan : GameJokerRepository.plans(transaction, session, "p.boundrunid = ? AND "
                + GameJokerRepository.ARMED, run.id())) {
            GameJokerRepository.release(transaction,
                    GameJokerRepository.reservation(transaction, session, plan.reservationId()).orElseThrow(),
                    "Tool", "The speedhunt ended before its ping");
            fakes.add(new FakeOutcome(plan.reservationId(), GameJokerRepository.RELEASED));
            jokerMembers.add(plan.memberId());
        }
        visible(run.targetMemberId());
        historyChanged = true;
    }

    // Live uploads ------------------------------------------------------------------------------------------

    private void liveUploadStarted(int index, GameOperation.LiveUploadStarted item) throws Exception {
        if (!GameState.PURPOSES.contains(item.purpose())) {
            throw GameErrors.invalid("Invalid live upload purpose");
        }
        boolean relay = GameState.HUNTER_RELAY.equals(item.purpose());
        boolean speedhunt = GameState.SPEEDHUNT_PING.equals(item.purpose());
        if (relay != (item.sourceMemberId() == null) || relay != (item.recipientMemberId() != null)) {
            throw GameErrors.invalid(relay ? "A hunter relay names its recipient" : "A live upload names its source");
        }
        if (item.intervalSeconds() < 1 || item.intervalSeconds() > MAX_INTERVAL_SECONDS) {
            throw GameErrors.invalid("Invalid live upload interval");
        }
        if (Boolean.TRUE.equals(item.useJoker()) && !relay) {
            throw GameErrors.invalid("Only a hunter relay uses a joker");
        }
        if (!speedhunt && (item.pingNumber() != null || item.startedBy() != null)) {
            throw GameErrors.invalid("Only a live speedhunt ping has a ping number");
        }
        if (relay && (item.toHunters() || item.toPlayers() || item.toSupporters())) {
            throw GameErrors.invalid("A hunter relay has no audiences");
        }
        if (!relay && !item.toHunters() && !item.toPlayers() && !item.toSupporters()) {
            throw GameErrors.invalid("A live upload names its audiences");
        }
        GameExecutionService.requireRunning(transaction, session);
        GameMember member = participant(relay ? item.recipientMemberId() : item.sourceMemberId());
        if (caught(member.id())) {
            throw GameErrors.conflict("The " + member.label() + " is out of the game");
        }
        if (speedhunt && onBreak(member.id())) {
            throw GameErrors.conflict("The " + member.label() + " is on a break");
        }
        for (GameState.LiveUpload other : GameStateRepository.activeUploads(transaction, session)) {
            if (other.purpose().equals(item.purpose()) && other.memberId() == member.id()) {
                throw GameErrors.conflict("The " + member.label() + " already has an active " + item.purpose()
                        + " live upload");
            }
        }
        if (item.requestId() != null
                && GameRequestRepository.find(transaction, session, item.requestId()).isEmpty()) {
            throw GameErrors.invalid("Request " + item.requestId() + " not found in this game session");
        }
        boolean overAllocate = Boolean.TRUE.equals(item.overAllocate());
        Integer number = null;
        if (speedhunt) {
            number = nextPing(member, item.pingNumber(), overAllocate);
        }
        if (Boolean.TRUE.equals(item.useJoker()) && !overAllocate && GameJokerRepository.available(transaction,
                session, member.id(), GameJokerRepository.HUNTER_POSITIONS) <= 0) {
            throw GameErrors.conflict("No Get hunter positions joker is left for the " + member.label()
                    + "; confirm a use beyond the allocation");
        }
        long id = GameStateRepository.insertUpload(transaction, session, item.purpose(),
                relay ? null : member.id(), relay ? member.id() : null, item.toHunters(), item.toPlayers(),
                item.toSupporters(), new GameStateRepository.UploadOptions(item.intervalSeconds(),
                        deadline(item.deadline(), "live upload deadline"), item.reason(), item.requestId(),
                        Boolean.TRUE.equals(item.useJoker()), overAllocate, number,
                        speedhunt ? (item.startedBy() == null ? actor : item.startedBy()) : null));
        GameState.LiveUpload upload = GameStateRepository.upload(transaction, session, id).orElseThrow();
        if (upload.visibleToHunters()) {
            visible(member.id());
        }
        GameLiveEvents.started(transaction, publisher, session, upload);
        result(index, item, id, null, number, null, GameState.ACTIVE);
    }

    /**
     * The ping a live speedhunt ping delivers with its first sample: the next of the target's running speedhunt,
     * or ping 1 of a new one, whose pool joker must be held for the target, available, or allowed beyond the
     * allocation.
     */
    private int nextPing(GameMember target, Integer requested, boolean overAllocate) throws SQLException {
        Optional<GameState.Run> run = GameStateRepository.activeRun(transaction, session, target.id());
        int next = run.isEmpty() ? 1 : GameStateRepository.lastPingNumber(transaction, session, run.get().id()) + 1;
        if (requested != null && requested != next) {
            throw GameErrors.conflict("Ping " + requested + " is not the next ping (" + next + ") of this speedhunt");
        }
        Integer limit = settings().pingsPerSpeedhunt();
        if (limit != null && next > limit) {
            throw GameErrors.conflict("The speedhunt of the " + target.label() + " reached its ping limit");
        }
        if (run.isEmpty() && !overAllocate && poolReservation(target.id(), false) == null
                && GameJokerRepository.available(transaction, session, null, GameJokerRepository.SPEEDHUNT) <= 0) {
            throw GameErrors.conflict("No speedhunt joker is left in the hunters' pool; confirm a use beyond the"
                    + " allocation");
        }
        return next;
    }

    /** A live relay uses one "Get hunter positions" joker per activation, with its first tick that delivers. */
    private void relayJoker(GameState.LiveUpload upload, Boolean overAllocate) throws SQLException {
        if (!GameState.HUNTER_RELAY.equals(upload.purpose())) {
            throw GameErrors.invalid("Only a hunter relay uses a joker");
        }
        if (upload.reservationId() != null) {
            return;
        }
        var used = GameJokerRepository.useDirect(transaction, session, upload.recipientMemberId(),
                GameJokerRepository.HUNTER_POSITIONS, Boolean.TRUE.equals(overAllocate), actor, "Live hunter relay");
        GameStateRepository.linkReservation(transaction, session, upload.id(), used.id());
        jokerMembers.add(upload.recipientMemberId());
    }

    /** A delivering tick of a server-run upload (server-internal). */
    private void liveUploadSample(int index, GameOperation.LiveUploadSample item) throws Exception {
        GameState.LiveUpload upload = upload(item.liveUploadId());
        GameStateRepository.sample(transaction, session, upload.id());
        if (Boolean.TRUE.equals(item.useJoker())) {
            relayJoker(upload, item.overAllocate());
        }
        if (upload.pingId() != null) {
            var ping = GameStateRepository.ping(transaction, session, upload.pingId()).orElseThrow();
            if (GameState.JOKER_FAKE.equals(ping.source())) {
                var plan = GameJokerRepository.plan(transaction, session, ping.fakePlanId()).orElseThrow();
                appliedFakes.put(plan.memberId(), new AppliedFake(plan.memberId(), plan.latitude(), plan.longitude(),
                        true));
            }
        }
        result(index, item, upload.id(), null, null, null, GameState.ACTIVE);
    }

    private void liveUploadEnded(int index, GameOperation.LiveUploadEnded item) throws Exception {
        GameState.LiveUpload upload = upload(item.liveUploadId());
        String reason = item.endReason() == null || item.endReason().isBlank() ? "stopped" : item.endReason().strip();
        endUpload(upload, reason);
        result(index, item, upload.id(), null, null, null, GameState.ENDED);
    }

    /**
     * Ends an active live upload with its {@code ended} event, whatever ends it: a stop, its deadline, a catch, the
     * end of its speedhunt, the game stopping, the member retiring. A live speedhunt ping that never reached anyone
     * returns the pool joker armed for the speedhunt it would have started ({@code pingFailed}); one whose ping was
     * the last of its speedhunt completes that speedhunt ({@code runEnded completed}), as the Tool did when such a
     * Live Ping stopped. A catch, a retirement, the end of the speedhunt and a replacement do neither.
     */
    void endUpload(GameState.LiveUpload upload, String reason) throws Exception {
        endUpload(upload, reason, null);
    }

    /** Ends an active live upload; {@code detail} explains a refusal that ended it. */
    void endUpload(GameState.LiveUpload upload, String reason, String detail) throws Exception {
        GameStateRepository.endUpload(transaction, session, upload.id(), reason);
        if (upload.visibleToHunters()) {
            visible(upload.memberId());
        }
        GameLiveEvents.Ended effects = GameLiveEvents.Ended.NONE;
        if (GameState.SPEEDHUNT_PING.equals(upload.purpose())) {
            historyChanged = true;
            if (!GameLiveEvents.QUIET_ENDS.contains(reason)) {
                effects = finishLivePing(upload);
            }
        }
        GameState.LiveUpload ended = GameStateRepository.upload(transaction, session, upload.id()).orElseThrow();
        GameLiveEvents.ended(transaction, publisher, session, ended, reason, detail, effects);
    }

    private GameLiveEvents.Ended finishLivePing(GameState.LiveUpload upload) throws Exception {
        long target = upload.memberId();
        Optional<GameState.Run> run = GameStateRepository.activeRun(transaction, session, target);
        if (upload.pingId() == null) {
            if (run.isPresent()) {
                return GameLiveEvents.Ended.NONE;
            }
            GameJokerRepository.Reservation armed = poolReservation(target, true);
            if (armed == null) {
                return GameLiveEvents.Ended.NONE;
            }
            GameJokerRepository.release(transaction, armed, "Server", "The Live Ping reached nobody");
            fakes.add(new FakeOutcome(armed.id(), GameJokerRepository.RELEASED));
            poolChanged = true;
            return new GameLiveEvents.Ended(armed.id(), null);
        }
        var ping = GameStateRepository.ping(transaction, session, upload.pingId()).orElseThrow();
        Integer limit = settings().pingsPerSpeedhunt();
        if (run.isEmpty() || run.get().id() != ping.runId() || limit == null
                || GameStateRepository.lastPingNumber(transaction, session, run.get().id()) < limit) {
            return GameLiveEvents.Ended.NONE;
        }
        endRun(run.get(), GameState.COMPLETED, "ping limit");
        return new GameLiveEvents.Ended(null, run.get().id());
    }

    // Breaks ------------------------------------------------------------------------------------------------

    /** The configured break duration in whole seconds; 409 while breaks are not set up. */
    long breakSeconds() throws SQLException {
        Double minutes = settings().breakMinutes();
        if (minutes == null) {
            throw GameErrors.conflict("Set a positive break duration in the game settings first");
        }
        return GameSettings.breakSeconds(minutes);
    }

    private void breakPlanned(int index, GameOperation.BreakPlanned item) throws Exception {
        long id = GameJokerService.planBreak(transaction, session, item.memberId(),
                GameTime.parseOptional(item.plannedStart(), "break start"), Boolean.TRUE.equals(item.overAllocate()),
                actor);
        jokerMembers.add(item.memberId());
        result(index, item, id, null, null, null, GameState.PLANNED);
    }

    /**
     * Why the member's break cannot start now, or null; an active break of the member that is past its deadline (it
     * no longer protects) ends first, as the deadline ends it ({@link #endBreak}).
     */
    String breakBlocked(GameMember member) throws Exception {
        if (caught(member.id())) {
            return "The " + member.label() + " is out of the game";
        }
        Optional<GameState.Break> active = GameStateRepository.activeBreak(transaction, session, member.id());
        if (active.isPresent() && !active.get().protects(transaction.now())) {
            endBreak(active.get());
            active = Optional.empty();
        }
        if (active.isPresent()) {
            return "The " + member.label() + " is already on a break";
        }
        if (GameStateRepository.speedhuntRunning(transaction, session)) {
            return BREAK_UNAVAILABLE;
        }
        return null;
    }

    private void checkBreakCanStart(GameMember member) throws Exception {
        String blocked = breakBlocked(member);
        if (blocked != null) {
            throw GameErrors.conflict(blocked);
        }
        // A break protects only while hunters see the participant through mirrors.
        GameAccessRule.checkBreakStart(transaction, session, member.id());
    }

    /**
     * What every break start does besides its row: armed timetable fakes of the member inside the break are released,
     * and the member's jokers and status change (hunters see it).
     *
     * @return the number of timetable fakes released
     */
    private int breakBegins(long memberId, Instant deadline) throws SQLException {
        int released = 0;
        for (var plan : GameJokerRepository.plans(transaction, session, "p.memberid = ? AND p.timetableentryid IS NOT"
                + " NULL AND " + GameJokerRepository.ARMED, memberId)) {
            var entry = GameTimetableRepository.find(transaction, session, plan.timetableEntryId());
            if (entry.isPresent() && !entry.get().scheduledAt().isAfter(deadline)) {
                GameJokerRepository.release(transaction,
                        GameJokerRepository.reservation(transaction, session, plan.reservationId()).orElseThrow(),
                        actor, "Its timetable ping falls inside a break");
                fakes.add(new FakeOutcome(plan.reservationId(), GameJokerRepository.RELEASED));
                released++;
            }
        }
        jokerMembers.add(memberId);
        visible(memberId);
        return released;
    }

    /** The immediate break of a {@code jokerBreak} request that succeeds with this delivery. */
    private void breakStarted(int index, GameOperation.BreakStarted item) throws Exception {
        if (item.requestId() == null) {
            throw GameErrors.invalid("A break start names the break request it starts");
        }
        Instant deadline = deadline(item.deadline(), "break deadline");
        GameRequest request = GameRequestRepository.find(transaction, session, item.requestId())
                .orElseThrow(() -> GameErrors.invalid("Request " + item.requestId() + " not found"));
        if (!GameRequest.BREAK.equals(request.type()) || request.plannedBreak()) {
            throw GameErrors.invalid("Request " + request.id() + " is not a break that starts now");
        }
        if (!GameRequest.SUCCEEDED.equals(request.status())) {
            throw GameErrors.conflict("Request " + request.id() + " did not succeed");
        }
        if (GameStateRepository.breaks(transaction, session).stream()
                .anyMatch(other -> java.util.Objects.equals(other.requestId(), request.id()))) {
            throw GameErrors.conflict("The break of request " + request.id() + " already started");
        }
        GameMember member = participant(request.targetMemberId());
        checkBreakCanStart(member);
        if (deadline == null) {
            deadline = transaction.now().plusSeconds(breakSeconds());
        }
        Long reservationId = GameJokerRepository.ofRequest(transaction, session, request.id()).stream()
                .map(GameJokerRepository.Reservation::id).findFirst().orElse(null);
        long breakId = GameStateRepository.insertActiveBreak(transaction, session, member.id(), deadline,
                GameState.BY_PARTICIPANT, request.id(), reservationId);
        immediateBreaks.add(request.id());
        breakBegins(member.id(), deadline);
        result(index, item, breakId, null, null, null, GameState.ACTIVE);
    }

    private void breakCancelled(int index, GameOperation.BreakCancelled item) throws Exception {
        GameState.Break pause = pendingOrActiveBreak(item.breakId());
        if (!pause.pending()) {
            throw GameErrors.conflict("Break " + pause.id() + " is " + pause.state() + " and cannot become "
                    + GameState.CANCELLED);
        }
        returnBreak(pause, GameState.CANCELLED, item.detail() == null ? "Cancelled by an operator" : item.detail());
        result(index, item, pause.id(), null, null, null, GameState.CANCELLED);
    }

    /** A planned or waiting break that never started ends ({@code cancelled} or {@code missed}); its joker returns. */
    private void returnBreak(GameState.Break pause, String state, String detail) throws SQLException {
        GameStateRepository.finishBreak(transaction, session, pause.id(), state);
        var reservation = GameJokerRepository.reservation(transaction, session, pause.reservationId()).orElseThrow();
        if (reservation.reserved()) {
            GameJokerRepository.release(transaction, reservation, actor, detail);
        }
        jokerMembers.add(pause.memberId());
    }

    // Breaks the server runs ({@link GameBreakRunner}) ------------------------------------------------------------

    /**
     * An active break ends ({@code ended}): at its deadline, or before a new break of its member once the deadline
     * has passed. Hunters see it; an {@code ended} event is written.
     */
    void endBreak(GameState.Break pause) throws SQLException {
        GameStateRepository.finishBreak(transaction, session, pause.id(), GameState.ENDED);
        visible(pause.memberId());
        GameBreakEvents.ended(transaction, publisher, session, pause);
    }

    /** A planned break waits at its start: it becomes {@code waiting} (members see that it waits). */
    void waitBreak(GameState.Break pause) throws SQLException {
        if (GameState.PLANNED.equals(pause.state())) {
            GameStateRepository.waitBreak(transaction, session, pause.id());
            jokerMembers.add(pause.memberId());
        }
    }

    /** A planned or waiting break whose planned end passed before it could start is missed; its joker returns. */
    void missBreak(GameState.Break pause) throws SQLException {
        returnBreak(pause, GameState.MISSED, "The planned break was missed");
    }

    /**
     * A planned or waiting break starts and lasts until {@code deadline}: its joker is used, and it takes effect like
     * every break start ({@link #breakBegins}). The caller checked that it can start ({@link #breakBlocked} and the
     * access rule).
     *
     * @return the number of armed timetable fakes it released
     */
    int startPlannedBreak(GameState.Break pause, Instant deadline) throws SQLException {
        var reservation = GameJokerRepository.reservation(transaction, session, pause.reservationId()).orElseThrow();
        GameJokerRepository.consume(transaction, reservation, actor, "The planned break started");
        GameStateRepository.startBreak(transaction, session, pause, deadline);
        return breakBegins(pause.memberId(), deadline);
    }

}
