/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Jokers of data model 2 (model-1 rules, Stage 4 and changes B–D): players and supporters hold "Get hunter
 * positions", fake ping and break jokers; the hunters share a pool of speedhunt jokers.
 *
 * <ul>
 * <li>Operators grant and revoke unused quantities; every change is in the ledger in the same transaction.</li>
 * <li>A request reserves a use; a succeeded request uses it, except that fake pings, planned breaks and pool
 * jokers are armed and used when they take effect. Any other end returns it.</li>
 * <li>A fake ping is planned on one of the owner's timetable pings, on a ping of the next speedhunt (it binds to
 * the next run that starts and is used for every owner, never refunded), or on a ping of a running speedhunt.</li>
 * <li>A planned break lives in its break row; its reservation holds the joker until it starts.</li>
 * <li>Fake plans, unused inventories and planned breaks are private to their owner and the operators; joker
 * invalidations reach only them (and the hunters for their pool).</li>
 * </ul>
 */
@Singleton
public class GameJokerService {

    public static final String USE_TIMETABLE = "timetable";
    public static final String USE_SPEEDHUNT = "speedhunt";
    public static final String USE_RUNNING_SPEEDHUNT = "runningSpeedhunt";
    public static final String USE_PLANNED_BREAK = "plannedBreak";
    /** Bound speedhunt fakes listed for an owner, and for the operators across the session. */
    static final int BOUND_FAKES_OWN = 5;
    static final int BOUND_FAKES_OVERVIEW = 50;

    public record Inventory(Long memberId, String type, int granted, int used, int reserved, int available,
                            int overAllocated) {
    }

    /** A fake's plan; {@code boundRunId} is the opaque run reference, shown to operators only. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FakePlan(double latitude, double longitude, Long entryId, Instant scheduledAt, Integer pingNumber,
                           boolean bound, Long boundRunId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlannedBreak(long breakId, Instant startsAt, String state, String plannedBy) {
    }

    /** A reservation with its plan: {@code armed} plans have no open request any more. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Reservation(long id, Long memberId, String type, Long requestId, String status, boolean armed,
                              boolean overAllocated, Instant createdAt, String use, FakePlan fake,
                              PlannedBreak plannedBreak) {
    }

    public record FakeEntry(long entryId, Instant scheduledAt, boolean taken) {
    }

    public record Overview(List<Inventory> inventories, List<Reservation> reservations,
                           List<Reservation> boundFakes) {
    }

    /** {@code pingsPerSpeedhunt}: the ping limit a speedhunt fake must stay within (null: no limit). */
    public record Own(List<Inventory> inventories, List<Reservation> reservations, List<FakeEntry> timetableEntries,
                      Integer pingsPerSpeedhunt) {
    }

    public record LedgerEntry(long id, Long memberId, String type, String kind, int quantity, Long requestId,
                              Long reservationId, String actor, String detail, Instant occurredAt) {
    }

    /** A grant (positive) or revocation (negative) for a member, or for the hunters' pool (no member). */
    public record Change(Long memberId, String type, Integer quantity, String reason) {
    }

    /** An operator's direct use, such as a manual hunter relay. */
    public record Use(Long memberId, String type, Boolean overAllocate, String detail) {
    }

    public record UseOutcome(Long memberId, String type, boolean overAllocated, long reservationId) {
    }

    /** A balance that does not equal the sum of its ledger entries. */
    public record Discrepancy(Long memberId, String type, String field, long stored, long expected) {
    }

    private final GameTransactions transactions;
    private final GamePublisher publisher;

    @Inject
    public GameJokerService(GameTransactions transactions, GamePublisher publisher) {
        this.transactions = transactions;
        this.publisher = publisher;
    }

    static String type(String value, Long memberId) {
        if (value == null || !GameJokerRepository.MEMBER_TYPES.contains(value)
                && !GameJokerRepository.POOL_TYPES.contains(value)) {
            throw GameErrors.invalid("Unknown joker type");
        }
        if (GameJokerRepository.poolType(value) != (memberId == null)) {
            throw GameErrors.invalid("Speedhunt jokers belong to the hunters' pool; other jokers to players and"
                    + " supporters");
        }
        return value;
    }

    /** An active player or supporter of the session, who can hold jokers. */
    static GameMember holder(GameTransaction transaction, long sessionId, long memberId) throws SQLException {
        GameMember member = GameMemberRepository.find(transaction, sessionId, memberId)
                .orElseThrow(() -> GameErrors.invalid("Jokers belong to players and supporters of this session"));
        if (!GameMember.hasState(member.role())) {
            throw GameErrors.invalid("Jokers belong to players and supporters of this session");
        }
        if (!member.active()) {
            throw GameErrors.conflict("The " + member.label() + " is retired");
        }
        return member;
    }

    /** Registers the after-commit joker invalidation for owners, and for the hunters when the pool changed. */
    void notifyOwners(GameTransaction transaction, long sessionId, Set<Long> members, boolean pool)
            throws SQLException {
        Set<Long> owners = new HashSet<>(members);
        if (pool) {
            owners.addAll(GameStateService.hunters(transaction, sessionId));
        }
        if (!owners.isEmpty()) {
            Set<Long> recipients = Set.copyOf(owners);
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(sessionId, GamePublisher.JOKERS, recipients)));
        }
    }

    // Views -------------------------------------------------------------------------------------------------

    private static Inventory inventory(GameTransaction transaction, long sessionId, Long memberId, String type)
            throws SQLException {
        int[] balance = GameJokerRepository.balance(transaction, sessionId, memberId, type);
        int reserved = GameJokerRepository.reservedCount(transaction, sessionId, memberId, type);
        return new Inventory(memberId, type, balance[0], balance[1], reserved,
                Math.max(0, balance[0] - balance[1] - reserved), Math.max(0, balance[1] - balance[0]));
    }

    private static List<Inventory> inventories(GameTransaction transaction, long sessionId, List<Long> memberIds)
            throws SQLException {
        List<Inventory> result = new ArrayList<>();
        for (long memberId : memberIds) {
            for (String type : GameJokerRepository.MEMBER_TYPES) {
                result.add(inventory(transaction, sessionId, memberId, type));
            }
        }
        return result;
    }

    static Reservation view(GameTransaction transaction, long sessionId, GameJokerRepository.Reservation item,
                            boolean operator) throws SQLException {
        FakePlan fake = null;
        PlannedBreak planned = null;
        String use = null;
        Optional<GameJokerRepository.Plan> plan = GameJokerRepository.plan(transaction, sessionId, item.id());
        if (plan.isPresent()) {
            var value = plan.get();
            Instant scheduled = value.timetable() ? GameTimetableRepository.find(transaction, sessionId,
                    value.timetableEntryId()).map(GameTimetableRepository.Entry::scheduledAt).orElse(null) : null;
            boolean bound = value.boundRunId() != null && GameJokerRepository.CONSUMED.equals(item.status());
            fake = new FakePlan(value.latitude(), value.longitude(), value.timetableEntryId(), scheduled,
                    value.pingNumber(), bound, operator ? value.boundRunId() : null);
            if (value.timetable()) {
                use = USE_TIMETABLE;
            } else {
                // A fake on a running speedhunt was requested with that speedhunt's run token.
                boolean running = item.requestId() != null && GameRequestRepository.find(transaction, sessionId,
                        item.requestId()).map(request -> request.parameters().hasNonNull("runToken")).orElse(false);
                use = running ? USE_RUNNING_SPEEDHUNT : USE_SPEEDHUNT;
            }
        }
        Optional<GameState.Break> pause = GameStateRepository.breakOfReservation(transaction, sessionId, item.id());
        if (pause.isPresent() && pause.get().plannedStart() != null) {
            planned = new PlannedBreak(pause.get().id(), pause.get().plannedStart(), pause.get().state(),
                    pause.get().plannedBy());
            use = USE_PLANNED_BREAK;
        }
        return new Reservation(item.id(), item.memberId(), item.type(), item.held() ? item.requestId() : null,
                item.status(), item.armed(), item.overAllocated(), item.createdAt(), use, fake, planned);
    }

    private static List<Reservation> views(GameTransaction transaction, long sessionId,
                                           List<GameJokerRepository.Reservation> items, boolean operator)
            throws SQLException {
        List<Reservation> result = new ArrayList<>();
        for (var item : items) {
            result.add(view(transaction, sessionId, item, operator));
        }
        return result;
    }

    /** The latest fakes bound to a speedhunt (used), of one owner or of the session. */
    private static List<Reservation> boundFakes(GameTransaction transaction, long sessionId, Long memberId,
                                                int limit, boolean operator) throws SQLException {
        List<Reservation> result = new ArrayList<>();
        for (var item : GameJokerRepository.latest(transaction, sessionId, GameJokerRepository.FAKE_PING,
                GameJokerRepository.CONSUMED, 0)) {
            if (result.size() >= limit) {
                break;
            }
            if (memberId != null && !memberId.equals(item.memberId())) {
                continue;
            }
            var plan = GameJokerRepository.plan(transaction, sessionId, item.id());
            if (plan.isPresent() && plan.get().boundRunId() != null) {
                result.add(view(transaction, sessionId, item, operator));
            }
        }
        return result;
    }

    /** The operators' overview: every inventory and the pool, open reservations and the latest bound fakes. */
    public Overview overview(long sessionId) throws Exception {
        return transactions.read(transaction -> {
            GameDocumentService.sessionExists(transaction, sessionId);
            return overview(transaction, sessionId);
        });
    }

    /** The operators' overview inside a transaction (also for the operator snapshot). */
    static Overview overview(GameTransaction transaction, long sessionId) throws SQLException {
        Set<Long> holders = new LinkedHashSet<>();
        for (GameMember member : GameMemberRepository.list(transaction, sessionId)) {
            if (member.active() && GameMember.hasState(member.role())) {
                holders.add(member.id());
            }
        }
        holders.addAll(transaction.list("SELECT DISTINCT memberid FROM tc_game_jokers WHERE gamesessionid = ?"
                + " ORDER BY memberid", rows -> rows.getLong(1), sessionId));
        List<Inventory> inventories = inventories(transaction, sessionId, List.copyOf(holders));
        for (String type : GameJokerRepository.POOL_TYPES) {
            inventories.add(inventory(transaction, sessionId, null, type));
        }
        return new Overview(inventories,
                views(transaction, sessionId, GameJokerRepository.reserved(transaction, sessionId, null, false), true),
                boundFakes(transaction, sessionId, null, BOUND_FAKES_OVERVIEW, true));
    }

    /**
     * A player's or supporter's own jokers: inventory, open reservations with their plans, the latest own fakes
     * that bound to a speedhunt (without the run reference), and the timetable pings a fake can still replace.
     */
    public Own own(GameMember owner) throws Exception {
        if (!GameMember.hasState(owner.role())) {
            throw new jakarta.ws.rs.ForbiddenException("Only players and supporters hold jokers");
        }
        return transactions.read(transaction -> {
            long sessionId = owner.gameSessionId();
            List<Reservation> reservations = views(transaction, sessionId,
                    GameJokerRepository.reserved(transaction, sessionId, owner.id(), false), false);
            reservations.addAll(boundFakes(transaction, sessionId, owner.id(), BOUND_FAKES_OWN, false));
            Set<Long> taken = new HashSet<>(GameJokerRepository.openFakeEntries(transaction, sessionId, owner.id()));
            List<FakeEntry> entries = new ArrayList<>();
            for (var entry : GameTimetableRepository.upcoming(transaction, sessionId, owner.id(),
                    transaction.now().plusSeconds(GameTimetableService.FAKE_CUTOFF_SECONDS))) {
                if (GameTimetableService.fakeEligible(entry.content())) {
                    entries.add(new FakeEntry(entry.id(), entry.scheduledAt(), taken.contains(entry.id())));
                }
            }
            return new Own(inventories(transaction, sessionId, List.of(owner.id())), reservations, entries,
                    GameSettings.read(transaction, sessionId).pingsPerSpeedhunt());
        });
    }

    /** The hunters' pool: its inventory and the pool jokers held by requests or armed. Every hunter sees it. */
    public Own pool(long sessionId) throws Exception {
        return transactions.read(transaction -> {
            GameDocumentService.sessionExists(transaction, sessionId);
            List<Inventory> inventories = new ArrayList<>();
            for (String type : GameJokerRepository.POOL_TYPES) {
                inventories.add(inventory(transaction, sessionId, null, type));
            }
            return new Own(inventories, views(transaction, sessionId,
                    GameJokerRepository.reserved(transaction, sessionId, null, true), false), List.of(),
                    GameSettings.read(transaction, sessionId).pingsPerSpeedhunt());
        });
    }

    public List<LedgerEntry> ledger(long sessionId, int requestedLimit) throws Exception {
        int limit = requestedLimit <= 0 ? 200 : Math.min(requestedLimit, 1000);
        return transactions.read(transaction -> {
            GameDocumentService.sessionExists(transaction, sessionId);
            return transaction.list("SELECT id, memberid, type, kind, quantity, requestid, reservationid, actor,"
                    + " detail, occurredat FROM tc_game_joker_ledger WHERE gamesessionid = ? ORDER BY id DESC", limit,
                    rows -> new LedgerEntry(rows.getLong("id"), GameTransaction.longOrNull(rows, "memberid"),
                            rows.getString("type"), rows.getString("kind"), rows.getInt("quantity"),
                            GameTransaction.longOrNull(rows, "requestid"),
                            GameTransaction.longOrNull(rows, "reservationid"), rows.getString("actor"),
                            rows.getString("detail"), GameTime.get(rows, "occurredat")), sessionId);
        });
    }

    // Operator changes --------------------------------------------------------------------------------------

    /** Grants (positive quantity) or revokes (negative) unused jokers; revoking never takes used or reserved ones. */
    public Inventory change(long sessionId, Change change, String actor) throws Exception {
        if (change == null || change.quantity() == null || change.quantity() == 0
                || Math.abs(change.quantity()) > 1000) {
            throw GameErrors.invalid("A non-zero quantity is required");
        }
        String type = type(change.type(), change.memberId());
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            if (change.memberId() != null) {
                holder(transaction, sessionId, change.memberId());
            }
            if (GameJokerRepository.BREAK.equals(type) && change.quantity() > 0
                    && GameSettings.read(transaction, sessionId).breakMinutes() == null) {
                throw GameErrors.conflict("Set a positive break duration in the game settings before granting break"
                        + " jokers");
            }
            if (change.quantity() > 0) {
                GameAccessRule.checkGrant(transaction, sessionId, type);
            }
            int available = GameJokerRepository.available(transaction, sessionId, change.memberId(), type);
            if (change.quantity() < 0 && -change.quantity() > available) {
                throw GameErrors.conflict("Only " + available + " unused joker(s) can be revoked");
            }
            int[] balance = GameJokerRepository.balance(transaction, sessionId, change.memberId(), type);
            GameJokerRepository.store(transaction, sessionId, change.memberId(), type,
                    balance[0] + change.quantity(), balance[1]);
            GameJokerRepository.ledger(transaction, sessionId, change.memberId(), type,
                    change.quantity() > 0 ? GameJokerRepository.GRANT : GameJokerRepository.REVOKE,
                    Math.abs(change.quantity()), null, null, actor, change.reason());
            transaction.bumpSession();
            notifyOwners(transaction, sessionId, change.memberId() == null ? Set.of() : Set.of(change.memberId()),
                    change.memberId() == null);
            return inventory(transaction, sessionId, change.memberId(), type);
        });
    }

    /**
     * Uses one joker directly for an operator action inside a delivery (a manual hunter relay). Reserved uses are
     * never taken; without an available joker the use needs {@code overAllocate}.
     */
    static UseOutcome use(GameTransaction transaction, long sessionId, Use use, String actor) throws SQLException {
        if (use == null || use.memberId() == null) {
            throw GameErrors.invalid("A joker use names its member");
        }
        if (!GameJokerRepository.HUNTER_POSITIONS.equals(use.type())) {
            throw GameErrors.invalid("Operator actions use only Get hunter positions jokers; a speedhunt uses its"
                    + " pool joker with its first ping");
        }
        holder(transaction, sessionId, use.memberId());
        var used = GameJokerRepository.useDirect(transaction, sessionId, use.memberId(), use.type(),
                Boolean.TRUE.equals(use.overAllocate()), actor, use.detail());
        return new UseOutcome(use.memberId(), use.type(), used.overAllocated(), used.id());
    }

    // Planned breaks ----------------------------------------------------------------------------------------

    /**
     * Whether a break of a member from {@code start} would overlap their active break, another planned or waiting
     * break, or a break they requested. A start that has passed counts from now, and each break takes the
     * configured duration.
     */
    static boolean breakOverlaps(GameTransaction transaction, long sessionId, long memberId, Instant start,
                                 long seconds) throws SQLException {
        Instant now = transaction.now();
        Instant from = start.isBefore(now) ? now : start;
        Instant to = from.plusSeconds(seconds);
        List<Instant[]> intervals = new ArrayList<>();
        for (GameState.Break pause : GameStateRepository.currentBreaks(transaction, sessionId, memberId)) {
            if (pause.active()) {
                if (pause.deadline().isAfter(now)) {
                    intervals.add(new Instant[] {pause.startedAt(), pause.deadline()});
                }
            } else {
                Instant other = pause.plannedStart().isBefore(now) ? now : pause.plannedStart();
                intervals.add(new Instant[] {other, other.plusSeconds(seconds)});
            }
        }
        for (GameRequest request : GameRequestRepository.open(transaction, sessionId)) {
            if (GameRequest.BREAK.equals(request.type()) && request.requesterMemberId() != null
                    && request.requesterMemberId() == memberId) {
                Instant other = request.plannedBreak()
                        ? GameTime.parse(request.parameters().get("startsAt").asText(), "break start") : now;
                Instant otherFrom = other.isBefore(now) ? now : other;
                intervals.add(new Instant[] {otherFrom, otherFrom.plusSeconds(seconds)});
            }
        }
        for (Instant[] interval : intervals) {
            if (interval[0].isBefore(to) && from.isBefore(interval[1])) {
                return true;
            }
        }
        return false;
    }

    /**
     * An operator plans a member's break inside a transaction that holds the session lock: from {@code startsAt},
     * or now when it is missing or has passed. The break joker is reserved for the break row (no request);
     * without an available joker the plan needs {@code overAllocate}, and its use counts beyond the allocation
     * once the break starts.
     *
     * @return the planned break's ID
     */
    static long planBreak(GameTransaction transaction, long sessionId, long memberId, Instant startsAt,
                          boolean overAllocate, String actor) throws SQLException {
        GameMember member = holder(transaction, sessionId, memberId);
        Double minutes = GameSettings.read(transaction, sessionId).breakMinutes();
        if (minutes == null) {
            throw GameErrors.conflict("Set a positive break duration in the game settings first");
        }
        if (GameStateRepository.activeCatch(transaction, sessionId, member.id()).isPresent()) {
            throw GameErrors.conflict("This participant is out of the game");
        }
        Instant now = transaction.now();
        Instant start = (startsAt != null && startsAt.isAfter(now) ? startsAt : now).truncatedTo(ChronoUnit.SECONDS);
        if (breakOverlaps(transaction, sessionId, member.id(), start, GameSettings.breakSeconds(minutes))) {
            throw GameErrors.conflict("This break would overlap another break of this participant");
        }
        // Planning a break beyond the allocation grants a break joker in effect: hunters must see only mirrors.
        GameAccessRule.checkGrant(transaction, sessionId, GameJokerRepository.BREAK);
        boolean over = GameJokerRepository.available(transaction, sessionId, member.id(),
                GameJokerRepository.BREAK) <= 0;
        long reservation = GameJokerRepository.reserve(transaction, sessionId, member.id(), GameJokerRepository.BREAK,
                null, overAllocate, actor, over ? "Planned by an operator beyond the allocation"
                        : "Planned by an operator");
        GameJokerRepository.arm(transaction,
                GameJokerRepository.reservation(transaction, sessionId, reservation).orElseThrow(), actor);
        return GameStateRepository.insertPlannedBreak(transaction, sessionId, member.id(), start,
                GameState.BY_OPERATOR, null, reservation);
    }

    /** An operator plans a member's break (see {@link #planBreak(GameTransaction, long, long, Instant, boolean,
     * String)}). */
    public Reservation planBreak(long sessionId, long memberId, String startsAt, boolean overAllocate, String actor)
            throws Exception {
        Instant start = GameTime.parseOptional(startsAt, "break start");
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            long breakId = planBreak(transaction, sessionId, memberId, start, overAllocate, actor);
            transaction.bumpSession();
            notifyOwners(transaction, sessionId, Set.of(memberId), false);
            long reservation = GameStateRepository.pause(transaction, sessionId, breakId).orElseThrow()
                    .reservationId();
            return view(transaction, sessionId,
                    GameJokerRepository.reservation(transaction, sessionId, reservation).orElseThrow(), true);
        });
    }

    // Owner cancellation ------------------------------------------------------------------------------------

    /**
     * The owner cancels an armed fake or planned break until it takes effect; its joker returns. Another owner's
     * reservation answers exactly like a missing one (404), so probing tells a participant nothing.
     */
    public Reservation cancel(GameMember owner, long reservationId) throws Exception {
        long sessionId = owner.gameSessionId();
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            GameMemberRepository.caller(transaction, owner);
            var reservation = GameJokerRepository.reservation(transaction, sessionId, reservationId)
                    .filter(item -> item.memberId() != null && item.memberId() == owner.id())
                    .orElseThrow(() -> GameErrors.notFound("Joker reservation not found"));
            if (!reservation.armed()) {
                throw GameErrors.conflict("This can no longer be cancelled");
            }
            Optional<GameState.Break> pause = GameStateRepository.breakOfReservation(transaction, sessionId,
                    reservationId);
            if (pause.isPresent()) {
                if (!pause.get().pending()) {
                    throw GameErrors.conflict("This can no longer be cancelled");
                }
                GameStateRepository.finishBreak(transaction, sessionId, pause.get().id(), GameState.CANCELLED);
            } else if (GameJokerRepository.plan(transaction, sessionId, reservationId).isEmpty()) {
                throw GameErrors.conflict("This can no longer be cancelled");
            }
            GameJokerRepository.release(transaction, reservation, owner.displayName(), "Cancelled by the owner");
            transaction.bumpSession();
            notifyOwners(transaction, sessionId, Set.of(owner.id()), false);
            return view(transaction, sessionId,
                    GameJokerRepository.reservation(transaction, sessionId, reservationId).orElseThrow(), false);
        });
    }

    /**
     * Releases a member's armed fakes that have not bound yet and cancels their planned and waiting breaks, inside
     * a transaction that holds the session lock.
     *
     * @return whether anything was released
     */
    static boolean releasePlans(GameTransaction transaction, long sessionId, long memberId, String detail)
            throws SQLException {
        boolean released = false;
        for (var plan : GameJokerRepository.plans(transaction, sessionId,
                "p.memberid = ? AND " + GameJokerRepository.ARMED, memberId)) {
            GameJokerRepository.release(transaction,
                    GameJokerRepository.reservation(transaction, sessionId, plan.reservationId()).orElseThrow(),
                    null, detail);
            released = true;
        }
        for (GameState.Break pause : GameStateRepository.currentBreaks(transaction, sessionId, memberId)) {
            if (pause.pending()) {
                GameStateRepository.finishBreak(transaction, sessionId, pause.id(), GameState.CANCELLED);
                var reservation = GameJokerRepository.reservation(transaction, sessionId, pause.reservationId());
                if (reservation.isPresent() && reservation.get().reserved()) {
                    GameJokerRepository.release(transaction, reservation.get(), null, detail);
                }
                released = true;
            }
        }
        return released;
    }

    // Reconciliation ----------------------------------------------------------------------------------------

    /**
     * Checks that every balance equals the sum of its ledger entries (granted = grants − revocations, used = uses
     * + uses beyond the allocation) and that every use has its consumed reservation. Empty when consistent.
     */
    public List<Discrepancy> reconcile(long sessionId) throws Exception {
        return transactions.read(transaction -> {
            record Key(Long memberId, String type) {
            }
            Map<Key, long[]> stored = new HashMap<>();
            transaction.list("SELECT memberid, type, granted, used FROM tc_game_jokers WHERE gamesessionid = ?"
                    + " UNION ALL SELECT NULL, type, granted, used FROM tc_game_joker_pools WHERE gamesessionid = ?",
                    rows -> stored.put(new Key(GameTransaction.longOrNull(rows, "memberid"), rows.getString("type")),
                            new long[] {rows.getLong("granted"), rows.getLong("used")}), sessionId, sessionId);
            Map<Key, long[]> ledger = new HashMap<>();
            transaction.list("SELECT memberid, type, kind, SUM(quantity) AS total FROM tc_game_joker_ledger"
                    + " WHERE gamesessionid = ? GROUP BY memberid, type, kind", rows -> {
                        long[] sums = ledger.computeIfAbsent(new Key(GameTransaction.longOrNull(rows, "memberid"),
                                rows.getString("type")), key -> new long[2]);
                        long total = rows.getLong("total");
                        switch (rows.getString("kind")) {
                            case GameJokerRepository.GRANT -> sums[0] += total;
                            case GameJokerRepository.REVOKE -> sums[0] -= total;
                            case GameJokerRepository.USE, GameJokerRepository.OVERUSE -> sums[1] += total;
                            default -> {
                            }
                        }
                        return null;
                    }, sessionId);
            Map<Key, Long> consumed = new HashMap<>();
            transaction.list("SELECT memberid, type, COUNT(*) AS total FROM tc_game_joker_reservations"
                    + " WHERE gamesessionid = ? AND status = ? GROUP BY memberid, type",
                    rows -> consumed.put(new Key(GameTransaction.longOrNull(rows, "memberid"), rows.getString("type")),
                            rows.getLong("total")), sessionId, GameJokerRepository.CONSUMED);
            Set<Key> keys = new LinkedHashSet<>(stored.keySet());
            keys.addAll(ledger.keySet());
            keys.addAll(consumed.keySet());
            List<Discrepancy> result = new ArrayList<>();
            for (Key key : keys) {
                long[] balance = stored.getOrDefault(key, new long[2]);
                long[] sums = ledger.getOrDefault(key, new long[2]);
                if (balance[0] != sums[0]) {
                    result.add(new Discrepancy(key.memberId(), key.type(), "granted", balance[0], sums[0]));
                }
                if (balance[1] != sums[1]) {
                    result.add(new Discrepancy(key.memberId(), key.type(), "used", balance[1], sums[1]));
                }
                if (balance[1] != consumed.getOrDefault(key, 0L)) {
                    result.add(new Discrepancy(key.memberId(), key.type(), "consumed reservations", balance[1],
                            consumed.getOrDefault(key, 0L)));
                }
            }
            return result;
        });
    }

}
