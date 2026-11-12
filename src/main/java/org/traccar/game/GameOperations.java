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
    static final String SKIPPED = "skipped";
    static final String BREAK_UNAVAILABLE = "A break cannot start right now; try again later";

    private final GameTransaction transaction;
    private final long session;
    private final long epoch;
    private final String actor;
    private final ObjectMapper objectMapper;
    private GameSettings settings;

    private final List<Result> results = new ArrayList<>();
    private final List<FakeOutcome> fakes = new ArrayList<>();
    private final Set<Long> jokerMembers = new LinkedHashSet<>();
    private final Map<Long, AppliedFake> appliedFakes = new HashMap<>();
    private final Set<Long> consumedFakes = new LinkedHashSet<>();
    private final Set<Long> immediateBreaks = new LinkedHashSet<>();
    private final Set<Long> succeededBreaks = new LinkedHashSet<>();
    private boolean statusChanged;
    private boolean historyChanged;
    private boolean requestsChanged;
    private boolean poolChanged;

    GameOperations(GameTransaction transaction, long session, long epoch, String actor, ObjectMapper objectMapper) {
        this.transaction = transaction;
        this.session = session;
        this.epoch = epoch;
        this.actor = actor;
        this.objectMapper = objectMapper;
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

    /** Timetable fakes used with this delivery. */
    Set<Long> consumedFakes() {
        return consumedFakes;
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
                case GameOperation.BreakWaiting item -> breakMember(transaction, session, item.breakId(), result);
                case GameOperation.BreakStarted item -> {
                    if (item.breakId() != null) {
                        breakMember(transaction, session, item.breakId(), result);
                    } else if (item.requestId() != null) {
                        GameRequestRepository.find(transaction, session, item.requestId())
                                .map(GameRequest::targetMemberId).ifPresent(result::add);
                    }
                }
                case GameOperation.BreakEnded item -> breakMember(transaction, session, item.breakId(), result);
                case GameOperation.BreakCancelled item -> breakMember(transaction, session, item.breakId(), result);
                case GameOperation.BreakMissed item -> breakMember(transaction, session, item.breakId(), result);
                case GameOperation.FakeUsed item -> GameJokerRepository.reservation(transaction, session,
                        item.reservationId()).map(GameJokerRepository.Reservation::memberId)
                        .ifPresent(result::add);
                case GameOperation.FakeReleased item -> GameJokerRepository.reservation(transaction, session,
                        item.reservationId()).map(GameJokerRepository.Reservation::memberId)
                        .ifPresent(result::add);
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
            case GameOperation.BreakWaiting item -> breakWaiting(index, item);
            case GameOperation.BreakStarted item -> breakStarted(index, item);
            case GameOperation.BreakEnded item -> breakEnded(index, item);
            case GameOperation.BreakCancelled item -> breakReturned(index, item, item.breakId(),
                    GameState.CANCELLED, item.detail() == null ? "Cancelled by an operator" : item.detail());
            case GameOperation.BreakMissed item -> breakReturned(index, item, item.breakId(), GameState.MISSED,
                    "The planned break was missed");
            case GameOperation.FakeUsed item -> fakeSettled(index, item, item.reservationId(), true, null);
            case GameOperation.FakeReleased item -> fakeSettled(index, item, item.reservationId(), false,
                    item.detail());
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
    private void memberOut(long memberId) throws SQLException {
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
        GameMember target = participant(item.targetMemberId());
        boolean live = GameState.LIVE.equals(item.mode());
        if (!live && !GameState.STATIC.equals(item.mode())) {
            throw GameErrors.invalid("A ping is static or live");
        }
        if (!GameState.REAL.equals(item.source()) && !GameState.OPERATOR_FAKE.equals(item.source())) {
            throw GameErrors.invalid("A ping's source is real or operatorFake");
        }
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
            GameStateRepository.sample(transaction, session, upload.id(), epoch);
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
        GameStateRepository.endRun(transaction, session, run.id(), item.outcome(), item.reason());
        // Armed fakes planned on pings the run did not reach are returned; open requests for them fail later.
        for (var plan : GameJokerRepository.plans(transaction, session, "p.boundrunid = ? AND "
                + GameJokerRepository.ARMED, run.id())) {
            GameJokerRepository.release(transaction,
                    GameJokerRepository.reservation(transaction, session, plan.reservationId()).orElseThrow(),
                    "Tool", "The speedhunt ended before its ping");
            fakes.add(new FakeOutcome(plan.reservationId(), GameJokerRepository.RELEASED));
            jokerMembers.add(plan.memberId());
        }
        visible(target.id());
        historyChanged = true;
        result(index, item, run.id(), run.id(), null, null, item.outcome());
    }

    // Live uploads ------------------------------------------------------------------------------------------

    private void liveUploadStarted(int index, GameOperation.LiveUploadStarted item) throws Exception {
        if (!GameState.PURPOSES.contains(item.purpose())) {
            throw GameErrors.invalid("Invalid live upload purpose");
        }
        boolean relay = GameState.HUNTER_RELAY.equals(item.purpose());
        if (relay != (item.sourceMemberId() == null) || relay != (item.recipientMemberId() != null)) {
            throw GameErrors.invalid(relay ? "A hunter relay names its recipient" : "A live upload names its source");
        }
        if (item.intervalSeconds() < 1) {
            throw GameErrors.invalid("Invalid live upload interval");
        }
        if (Boolean.TRUE.equals(item.useJoker()) && !relay) {
            throw GameErrors.invalid("Only a hunter relay uses a joker");
        }
        GameMember member = participant(relay ? item.recipientMemberId() : item.sourceMemberId());
        if (GameState.SPEEDHUNT_PING.equals(item.purpose())) {
            if (caught(member.id())) {
                throw GameErrors.conflict("The " + member.label() + " is out of the game");
            }
            if (onBreak(member.id())) {
                throw GameErrors.conflict("The " + member.label() + " is on a break");
            }
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
        long id = GameStateRepository.insertUpload(transaction, session, item.purpose(),
                relay ? null : member.id(), relay ? member.id() : null, item.toHunters(), item.toPlayers(),
                item.toSupporters(), item.intervalSeconds(), deadline(item.deadline(), "live upload deadline"),
                item.reason(), epoch, item.requestId());
        GameState.LiveUpload upload = GameStateRepository.upload(transaction, session, id).orElseThrow();
        if (Boolean.TRUE.equals(item.useJoker())) {
            relayJoker(upload, item.overAllocate());
        }
        if (upload.visibleToHunters()) {
            visible(member.id());
        }
        result(index, item, id, null, null, null, GameState.ACTIVE);
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

    private void liveUploadSample(int index, GameOperation.LiveUploadSample item) throws Exception {
        GameState.LiveUpload upload = upload(item.liveUploadId());
        GameStateRepository.sample(transaction, session, upload.id(), epoch);
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
        GameStateRepository.endUpload(transaction, session, upload.id(), item.endReason());
        if (upload.visibleToHunters()) {
            visible(upload.memberId());
        }
        result(index, item, upload.id(), null, null, null, GameState.ENDED);
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

    private void breakWaiting(int index, GameOperation.BreakWaiting item) throws Exception {
        GameState.Break pause = pendingOrActiveBreak(item.breakId());
        if (!GameState.PLANNED.equals(pause.state())) {
            throw GameErrors.conflict("Only a planned break can wait; break " + pause.id() + " is " + pause.state());
        }
        GameStateRepository.waitBreak(transaction, session, pause.id());
        result(index, item, pause.id(), null, null, null, GameState.WAITING);
    }

    private void checkBreakCanStart(GameMember member) throws SQLException {
        if (caught(member.id())) {
            throw GameErrors.conflict("The " + member.label() + " is out of the game");
        }
        if (GameStateRepository.activeBreak(transaction, session, member.id()).isPresent()) {
            throw GameErrors.conflict("The " + member.label() + " is already on a break");
        }
        if (GameStateRepository.speedhuntRunning(transaction, session)) {
            throw GameErrors.conflict(BREAK_UNAVAILABLE);
        }
        // A break protects only while hunters see the participant through mirrors.
        GameAccessRule.checkBreakStart(transaction, session, member.id());
    }

    private void breakStarted(int index, GameOperation.BreakStarted item) throws Exception {
        if ((item.breakId() == null) == (item.requestId() == null)) {
            throw GameErrors.invalid("A break start names either a planned break or a break request");
        }
        Instant deadline = deadline(item.deadline(), "break deadline");
        long breakId;
        long memberId;
        if (item.breakId() != null) {
            GameState.Break pause = pendingOrActiveBreak(item.breakId());
            if (!pause.pending()) {
                throw GameErrors.conflict("Break " + pause.id() + " is " + pause.state() + " and cannot start");
            }
            GameMember member = participant(pause.memberId());
            checkBreakCanStart(member);
            if (deadline == null) {
                deadline = transaction.now().plusSeconds(breakSeconds());
            }
            var reservation = GameJokerRepository.reservation(transaction, session, pause.reservationId())
                    .orElseThrow();
            GameJokerRepository.consume(transaction, reservation, "Tool", "The planned break started");
            GameStateRepository.startBreak(transaction, session, pause, deadline);
            breakId = pause.id();
            memberId = member.id();
        } else {
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
            breakId = GameStateRepository.insertActiveBreak(transaction, session, member.id(), deadline,
                    GameState.BY_PARTICIPANT, request.id(), reservationId);
            immediateBreaks.add(request.id());
            memberId = member.id();
        }
        // Armed timetable fakes inside the break are released when it starts.
        for (var plan : GameJokerRepository.plans(transaction, session, "p.memberid = ? AND p.timetableentryid IS NOT"
                + " NULL AND " + GameJokerRepository.ARMED, memberId)) {
            var entry = GameTimetableRepository.find(transaction, session, plan.timetableEntryId());
            if (entry.isPresent() && !entry.get().scheduledAt().isAfter(deadline)) {
                GameJokerRepository.release(transaction,
                        GameJokerRepository.reservation(transaction, session, plan.reservationId()).orElseThrow(),
                        "Tool", "Its timetable ping falls inside a break");
                fakes.add(new FakeOutcome(plan.reservationId(), GameJokerRepository.RELEASED));
            }
        }
        jokerMembers.add(memberId);
        visible(memberId);
        result(index, item, breakId, null, null, null, GameState.ACTIVE);
    }

    private void breakEnded(int index, GameOperation.BreakEnded item) throws Exception {
        GameState.Break pause = pendingOrActiveBreak(item.breakId());
        if (!pause.active()) {
            throw GameErrors.conflict("Break " + pause.id() + " is " + pause.state() + " and cannot end");
        }
        GameStateRepository.finishBreak(transaction, session, pause.id(), GameState.ENDED);
        visible(pause.memberId());
        result(index, item, pause.id(), null, null, null, GameState.ENDED);
    }

    private void breakReturned(int index, GameOperation operation, long breakId, String state, String detail)
            throws Exception {
        GameState.Break pause = pendingOrActiveBreak(breakId);
        if (!pause.pending()) {
            throw GameErrors.conflict("Break " + pause.id() + " is " + pause.state() + " and cannot become "
                    + state);
        }
        GameStateRepository.finishBreak(transaction, session, pause.id(), state);
        var reservation = GameJokerRepository.reservation(transaction, session, pause.reservationId()).orElseThrow();
        if (reservation.reserved()) {
            GameJokerRepository.release(transaction, reservation, "Tool", detail);
        }
        jokerMembers.add(pause.memberId());
        result(index, operation, pause.id(), null, null, null, state);
    }

    // Timetable fakes ---------------------------------------------------------------------------------------

    /**
     * Settles an armed timetable fake: used when the hunters received it with this delivery, released otherwise. A
     * fake that is no longer armed (cancelled, its owner caught, already settled) is skipped.
     */
    private void fakeSettled(int index, GameOperation operation, long reservationId, boolean used, String detail)
            throws Exception {
        var reservation = GameJokerRepository.reservation(transaction, session, reservationId)
                .orElseThrow(() -> GameErrors.invalid("Joker reservation " + reservationId + " not found"));
        var plan = GameJokerRepository.plan(transaction, session, reservationId);
        if (plan.isEmpty() || !plan.get().timetable()) {
            throw GameErrors.invalid("Only timetable fakes are used or released by deliveries");
        }
        String status = SKIPPED;
        if (reservation.armed()) {
            if (used) {
                GameJokerRepository.consume(transaction, reservation, "Tool", null);
                consumedFakes.add(reservationId);
                status = GameJokerRepository.CONSUMED;
            } else {
                GameJokerRepository.release(transaction, reservation, "Tool", detail);
                status = GameJokerRepository.RELEASED;
            }
            jokerMembers.add(plan.get().memberId());
        }
        fakes.add(new FakeOutcome(reservationId, status));
        result(index, operation, reservationId, null, null, null, status);
    }

}
