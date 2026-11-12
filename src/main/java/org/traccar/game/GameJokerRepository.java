/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Joker accounting of data model 2: member inventories (tc_game_jokers), the hunters' pool (tc_game_joker_pools),
 * reservations (tc_game_joker_reservations, accounting only), the append-only ledger (tc_game_joker_ledger) and fake
 * ping plans (tc_game_fake_ping_plans). Every balance change writes its ledger entry in the same transaction.
 *
 * <p>A reservation holds one use: {@code reserved} while a request is open or a plan is armed, then
 * {@code consumed} (used) or {@code released}. It is <em>armed</em> while it is reserved and either has no request
 * (an operator's planned break) or its request succeeded. A direct use (an operator relay, a run started by the
 * operator) is a reservation that is consumed when it is created, so every use has a reservation row that game
 * records can link to. Uses beyond the allocation are marked {@code overallocated} and recorded as
 * {@code overuse}.
 *
 * <p>Ledger kinds: {@code grant} and {@code revoke} change the granted quantity, {@code use} and {@code overuse}
 * the used quantity, and {@code reserve}, {@code arm} and {@code release} follow reservations. The balance of every
 * inventory equals the sum of its ledger entries ({@link GameJokerService#reconcile}).
 */
public final class GameJokerRepository {

    public static final String HUNTER_POSITIONS = "hunterPositions";
    public static final String FAKE_PING = "fakePing";
    public static final String BREAK = "break";
    /** A speedhunt joker of the hunters' shared pool. */
    public static final String SPEEDHUNT = "speedhunt";
    /** Types members hold, and types the hunters' pool holds. */
    public static final List<String> MEMBER_TYPES = List.of(HUNTER_POSITIONS, FAKE_PING, BREAK);
    public static final List<String> POOL_TYPES = List.of(SPEEDHUNT);

    public static final String RESERVED = "reserved";
    public static final String CONSUMED = "consumed";
    public static final String RELEASED = "released";

    public static final String GRANT = "grant";
    public static final String REVOKE = "revoke";
    public static final String RESERVE = "reserve";
    public static final String ARM = "arm";
    public static final String USE = "use";
    public static final String OVERUSE = "overuse";
    public static final String RELEASE = "release";

    /** One reservation; {@code memberId} is null for pool jokers. */
    public record Reservation(long id, long gameSessionId, Long memberId, String type, Long requestId,
                              String requestStatus, String status, boolean overAllocated, Instant createdAt,
                              Instant updatedAt) {

        public boolean reserved() {
            return RESERVED.equals(status);
        }

        /** Reserved and no longer waiting for its request: an armed fake, planned break or pool joker. */
        public boolean armed() {
            return reserved() && (requestId == null || GameRequest.SUCCEEDED.equals(requestStatus));
        }

        /** Reserved for a request that is still open. */
        public boolean held() {
            return reserved() && requestId != null && GameRequest.OPEN.contains(requestStatus);
        }
    }

    /**
     * A fake ping plan: a coordinate and exactly one of a timetable entry or a speedhunt ping number. A speedhunt
     * fake is bound to a run ({@code boundRunId}) when it binds, or when it is planned on a running speedhunt.
     */
    public record Plan(long reservationId, long gameSessionId, long memberId, double latitude, double longitude,
                       Long timetableEntryId, Integer pingNumber, Long boundRunId, Long openEntryId,
                       Long unboundMemberId) {

        public boolean timetable() {
            return timetableEntryId != null;
        }

        public boolean speedhunt() {
            return pingNumber != null;
        }
    }

    private static final String RESERVATIONS = "SELECT r.id, r.gamesessionid, r.memberid, r.type, r.requestid,"
            + " q.status AS requeststatus, r.status, r.overallocated, r.createdat, r.updatedat"
            + " FROM tc_game_joker_reservations r"
            + " LEFT JOIN tc_game_requests q ON q.gamesessionid = r.gamesessionid AND q.id = r.requestid";

    private static final String PLANS = "SELECT reservationid, gamesessionid, memberid, latitude, longitude,"
            + " timetableentryid, pingnumber, boundrunid, openentryid, unboundmemberid FROM tc_game_fake_ping_plans";

    private GameJokerRepository() {
    }

    public static boolean poolType(String type) {
        return POOL_TYPES.contains(type);
    }

    private static Reservation reservation(ResultSet rows) throws SQLException {
        return new Reservation(rows.getLong("id"), rows.getLong("gamesessionid"),
                GameTransaction.longOrNull(rows, "memberid"), rows.getString("type"),
                GameTransaction.longOrNull(rows, "requestid"), rows.getString("requeststatus"),
                rows.getString("status"), rows.getBoolean("overallocated"), GameTime.get(rows, "createdat"),
                GameTime.get(rows, "updatedat"));
    }

    private static Plan plan(ResultSet rows) throws SQLException {
        int number = rows.getInt("pingnumber");
        Integer pingNumber = rows.wasNull() ? null : number;
        return new Plan(rows.getLong("reservationid"), rows.getLong("gamesessionid"), rows.getLong("memberid"),
                rows.getDouble("latitude"), rows.getDouble("longitude"),
                GameTransaction.longOrNull(rows, "timetableentryid"), pingNumber,
                GameTransaction.longOrNull(rows, "boundrunid"), GameTransaction.longOrNull(rows, "openentryid"),
                GameTransaction.longOrNull(rows, "unboundmemberid"));
    }

    // Balances ----------------------------------------------------------------------------------------------

    /** Granted and used quantities of one holder and type ({@code memberId} null for the pool). */
    public static int[] balance(GameTransaction transaction, long sessionId, Long memberId, String type)
            throws SQLException {
        Optional<int[]> row = memberId == null
                ? transaction.first("SELECT granted, used FROM tc_game_joker_pools WHERE gamesessionid = ?"
                        + " AND type = ?", rows -> new int[] {rows.getInt(1), rows.getInt(2)}, sessionId, type)
                : transaction.first("SELECT granted, used FROM tc_game_jokers WHERE gamesessionid = ?"
                        + " AND memberid = ? AND type = ?", rows -> new int[] {rows.getInt(1), rows.getInt(2)},
                        sessionId, memberId, type);
        return row.orElse(new int[] {0, 0});
    }

    public static int reservedCount(GameTransaction transaction, long sessionId, Long memberId, String type)
            throws SQLException {
        return memberId == null
                ? transaction.first("SELECT COUNT(*) FROM tc_game_joker_reservations WHERE gamesessionid = ?"
                        + " AND memberid IS NULL AND type = ? AND status = ?", rows -> rows.getInt(1),
                        sessionId, type, RESERVED).orElse(0)
                : transaction.first("SELECT COUNT(*) FROM tc_game_joker_reservations WHERE gamesessionid = ?"
                        + " AND memberid = ? AND type = ? AND status = ?", rows -> rows.getInt(1),
                        sessionId, memberId, type, RESERVED).orElse(0);
    }

    /** Granted minus used and reserved, never below zero. */
    public static int available(GameTransaction transaction, long sessionId, Long memberId, String type)
            throws SQLException {
        int[] balance = balance(transaction, sessionId, memberId, type);
        return Math.max(0, balance[0] - balance[1] - reservedCount(transaction, sessionId, memberId, type));
    }

    public static void store(GameTransaction transaction, long sessionId, Long memberId, String type, int granted,
                             int used) throws SQLException {
        Instant now = transaction.now();
        if (memberId == null) {
            if (transaction.update("UPDATE tc_game_joker_pools SET granted = ?, used = ?, updatedat = ?"
                    + " WHERE gamesessionid = ? AND type = ?", granted, used, now, sessionId, type) == 0) {
                transaction.update("INSERT INTO tc_game_joker_pools (gamesessionid, type, granted, used, updatedat)"
                        + " VALUES (?, ?, ?, ?, ?)", sessionId, type, granted, used, now);
            }
        } else if (transaction.update("UPDATE tc_game_jokers SET granted = ?, used = ?, updatedat = ?"
                + " WHERE gamesessionid = ? AND memberid = ? AND type = ?",
                granted, used, now, sessionId, memberId, type) == 0) {
            transaction.update("INSERT INTO tc_game_jokers (gamesessionid, memberid, type, granted, used, updatedat)"
                    + " VALUES (?, ?, ?, ?, ?, ?)", sessionId, memberId, type, granted, used, now);
        }
    }

    public static void ledger(GameTransaction transaction, long sessionId, Long memberId, String type, String kind,
                              int quantity, Long requestId, Long reservationId, String actor, String detail)
            throws SQLException {
        transaction.update("INSERT INTO tc_game_joker_ledger (gamesessionid, memberid, type, kind, quantity,"
                + " requestid, reservationid, actor, detail, occurredat) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                sessionId, memberId, type, kind, quantity, requestId, reservationId, clip(actor, 128),
                clip(detail, 512), transaction.now());
    }

    static String clip(String value, int length) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("\\p{Cntrl}", " ").trim();
        return cleaned.length() > length ? cleaned.substring(0, length) : cleaned;
    }

    // Reservations ------------------------------------------------------------------------------------------

    public static Optional<Reservation> reservation(GameTransaction transaction, long sessionId, long id)
            throws SQLException {
        return transaction.first(RESERVATIONS + " WHERE r.gamesessionid = ? AND r.id = ?",
                GameJokerRepository::reservation, sessionId, id);
    }

    /** The reservations of a request (at most one in practice). */
    public static List<Reservation> ofRequest(GameTransaction transaction, long sessionId, long requestId)
            throws SQLException {
        return transaction.list(RESERVATIONS + " WHERE r.gamesessionid = ? AND r.requestid = ? ORDER BY r.id",
                GameJokerRepository::reservation, sessionId, requestId);
    }

    /** Reserved reservations of a session, optionally of one holder (pool: {@code pool} true). */
    public static List<Reservation> reserved(GameTransaction transaction, long sessionId, Long memberId,
                                             boolean pool) throws SQLException {
        if (pool) {
            return transaction.list(RESERVATIONS + " WHERE r.gamesessionid = ? AND r.memberid IS NULL"
                    + " AND r.status = ? ORDER BY r.id", GameJokerRepository::reservation, sessionId, RESERVED);
        }
        return memberId == null
                ? transaction.list(RESERVATIONS + " WHERE r.gamesessionid = ? AND r.status = ? ORDER BY r.id",
                        GameJokerRepository::reservation, sessionId, RESERVED)
                : transaction.list(RESERVATIONS + " WHERE r.gamesessionid = ? AND r.memberid = ? AND r.status = ?"
                        + " ORDER BY r.id", GameJokerRepository::reservation, sessionId, memberId, RESERVED);
    }

    private static long insertReservation(GameTransaction transaction, long sessionId, Long memberId, String type,
                                          Long requestId, String status, boolean overAllocated) throws SQLException {
        return transaction.insert("INSERT INTO tc_game_joker_reservations (gamesessionid, memberid, type, requestid,"
                + " status, overallocated, createdat, updatedat) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                sessionId, memberId, type, requestId, status, overAllocated, transaction.now(), transaction.now());
    }

    /**
     * Reserves one use, for a request or an armed plan without request. Without an available joker it needs
     * {@code overAllocate}; the use then counts beyond the allocation once it is consumed.
     */
    public static long reserve(GameTransaction transaction, long sessionId, Long memberId, String type,
                               Long requestId, boolean overAllocate, String actor, String detail)
            throws SQLException {
        boolean over = available(transaction, sessionId, memberId, type) <= 0;
        if (over && !overAllocate) {
            throw GameErrors.conflict(SPEEDHUNT.equals(type)
                    ? "No speedhunt joker is left; ask the operator for more"
                    : "No joker of this type is available");
        }
        long id = insertReservation(transaction, sessionId, memberId, type, requestId, RESERVED, over);
        ledger(transaction, sessionId, memberId, type, RESERVE, 1, requestId, id, actor, detail);
        return id;
    }

    /** Records that a reservation stays reserved after its request succeeded: an armed fake or plan. */
    public static void arm(GameTransaction transaction, Reservation reservation, String actor) throws SQLException {
        ledger(transaction, reservation.gameSessionId(), reservation.memberId(), reservation.type(), ARM, 1,
                reservation.requestId(), reservation.id(), actor, null);
    }

    private static void finish(GameTransaction transaction, Reservation reservation, String status)
            throws SQLException {
        if (transaction.update("UPDATE tc_game_joker_reservations SET status = ?, updatedat = ?"
                + " WHERE gamesessionid = ? AND id = ? AND status = ?", status, transaction.now(),
                reservation.gameSessionId(), reservation.id(), RESERVED) != 1) {
            throw GameErrors.conflict("Joker reservation " + reservation.id() + " is no longer reserved");
        }
        clearPlanMarkers(transaction, reservation.gameSessionId(), reservation.id());
    }

    /** Uses a reserved joker: the balance's used quantity and the ledger change together. */
    public static void consume(GameTransaction transaction, Reservation reservation, String actor, String detail)
            throws SQLException {
        finish(transaction, reservation, CONSUMED);
        int[] balance = balance(transaction, reservation.gameSessionId(), reservation.memberId(), reservation.type());
        store(transaction, reservation.gameSessionId(), reservation.memberId(), reservation.type(), balance[0],
                balance[1] + 1);
        ledger(transaction, reservation.gameSessionId(), reservation.memberId(), reservation.type(),
                reservation.overAllocated() ? OVERUSE : USE, 1, reservation.requestId(), reservation.id(), actor,
                detail);
    }

    /** Returns a reserved joker. */
    public static void release(GameTransaction transaction, Reservation reservation, String actor, String detail)
            throws SQLException {
        finish(transaction, reservation, RELEASED);
        ledger(transaction, reservation.gameSessionId(), reservation.memberId(), reservation.type(), RELEASE, 1,
                reservation.requestId(), reservation.id(), actor, detail);
    }

    /**
     * Uses one joker directly, for an operator action. Reserved uses are never taken; without an available joker
     * the use needs {@code overAllocate} and is recorded beyond the allocation.
     *
     * @return the consumed reservation that records the use
     */
    public static Reservation useDirect(GameTransaction transaction, long sessionId, Long memberId, String type,
                                        boolean overAllocate, String actor, String detail) throws SQLException {
        boolean over = available(transaction, sessionId, memberId, type) <= 0;
        if (over && !overAllocate) {
            throw GameErrors.conflict(SPEEDHUNT.equals(type)
                    ? "No speedhunt joker is left in the hunters' pool; confirm a use beyond the allocation"
                    : "No joker of this type is available; confirm a use beyond the allocation");
        }
        long id = insertReservation(transaction, sessionId, memberId, type, null, CONSUMED, over);
        int[] balance = balance(transaction, sessionId, memberId, type);
        store(transaction, sessionId, memberId, type, balance[0], balance[1] + 1);
        ledger(transaction, sessionId, memberId, type, over ? OVERUSE : USE, 1, null, id, actor, detail);
        return reservation(transaction, sessionId, id).orElseThrow();
    }

    // Fake ping plans ---------------------------------------------------------------------------------------

    public static Optional<Plan> plan(GameTransaction transaction, long sessionId, long reservationId)
            throws SQLException {
        return transaction.first(PLANS + " WHERE gamesessionid = ? AND reservationid = ?",
                GameJokerRepository::plan, sessionId, reservationId);
    }

    public static void insertPlan(GameTransaction transaction, long sessionId, long reservationId, long memberId,
                                  double latitude, double longitude, Long entryId, Integer pingNumber,
                                  Long runId) throws SQLException {
        Long unbound = pingNumber != null && runId == null ? memberId : null;
        transaction.update("INSERT INTO tc_game_fake_ping_plans (reservationid, gamesessionid, memberid, latitude,"
                + " longitude, timetableentryid, pingnumber, boundrunid, openentryid, unboundmemberid)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", reservationId, sessionId, memberId, latitude, longitude,
                entryId, pingNumber, runId, entryId, unbound);
    }

    /** Clears the "at most one open" markers of a plan whose reservation ended. */
    private static void clearPlanMarkers(GameTransaction transaction, long sessionId, long reservationId)
            throws SQLException {
        transaction.update("UPDATE tc_game_fake_ping_plans SET openentryid = NULL, unboundmemberid = NULL"
                + " WHERE gamesessionid = ? AND reservationid = ?", sessionId, reservationId);
    }

    /** Binds an unbound speedhunt fake to a run; the caller consumes its reservation. */
    public static void bind(GameTransaction transaction, long sessionId, long reservationId, long runId)
            throws SQLException {
        transaction.update("UPDATE tc_game_fake_ping_plans SET boundrunid = ?, unboundmemberid = NULL"
                + " WHERE gamesessionid = ? AND reservationid = ?", runId, sessionId, reservationId);
    }

    /** Timetable entries a member's open fakes (requested or armed) are planned on. */
    public static List<Long> openFakeEntries(GameTransaction transaction, long sessionId, long memberId)
            throws SQLException {
        return transaction.list("SELECT openentryid FROM tc_game_fake_ping_plans WHERE gamesessionid = ?"
                + " AND memberid = ? AND openentryid IS NOT NULL", rows -> rows.getLong(1), sessionId, memberId);
    }

    /** Whether a timetable entry already has an open fake: requested or armed. */
    public static boolean entryTaken(GameTransaction transaction, long sessionId, long entryId) throws SQLException {
        return transaction.exists("SELECT 1 FROM tc_game_fake_ping_plans WHERE gamesessionid = ? AND openentryid = ?",
                sessionId, entryId);
    }

    /** Whether a member holds an unbound speedhunt fake for the next speedhunt: requested or armed. */
    public static boolean unboundHeld(GameTransaction transaction, long sessionId, long memberId)
            throws SQLException {
        return transaction.exists("SELECT 1 FROM tc_game_fake_ping_plans WHERE gamesessionid = ?"
                + " AND unboundmemberid = ?", sessionId, memberId);
    }

    /** Whether a member holds an open fake (requested or armed) planned on a running speedhunt. */
    public static boolean runningHeld(GameTransaction transaction, long sessionId, long memberId, long runId)
            throws SQLException {
        return transaction.exists("SELECT 1 FROM tc_game_fake_ping_plans p JOIN tc_game_joker_reservations r"
                + " ON r.gamesessionid = p.gamesessionid AND r.id = p.reservationid"
                + " WHERE p.gamesessionid = ? AND p.memberid = ? AND p.boundrunid = ? AND r.status = ?",
                sessionId, memberId, runId, RESERVED);
    }

    /** Plans with their reservation, of a session, filtered by an SQL condition on {@code p} and {@code r}. */
    public static List<Plan> plans(GameTransaction transaction, long sessionId, String condition, Object... params)
            throws SQLException {
        Object[] all = new Object[params.length + 1];
        all[0] = sessionId;
        System.arraycopy(params, 0, all, 1, params.length);
        return transaction.list("SELECT p.reservationid, p.gamesessionid, p.memberid, p.latitude, p.longitude,"
                + " p.timetableentryid, p.pingnumber, p.boundrunid, p.openentryid, p.unboundmemberid"
                + " FROM tc_game_fake_ping_plans p JOIN tc_game_joker_reservations r"
                + " ON r.gamesessionid = p.gamesessionid AND r.id = p.reservationid"
                + " LEFT JOIN tc_game_requests q ON q.gamesessionid = r.gamesessionid AND q.id = r.requestid"
                + " WHERE p.gamesessionid = ? AND " + condition + " ORDER BY p.reservationid",
                GameJokerRepository::plan, all);
    }

    /** SQL condition for plans whose reservation is armed. */
    public static final String ARMED = "r.status = 'reserved' AND (r.requestid IS NULL OR q.status = 'succeeded')";

    /**
     * Releases armed timetable fakes whose entry is no longer active or no longer reaches the hunters, after a new
     * timetable version was published.
     *
     * @return the members whose fakes were released
     */
    public static Set<Long> timetableChanged(GameTransaction transaction, long sessionId) throws SQLException {
        Set<Long> owners = new LinkedHashSet<>();
        record Planned(long reservationId, long memberId, Boolean active, String content) {
        }
        List<Planned> planned = transaction.list("SELECT p.reservationid, p.memberid, e.active, e.content"
                + " FROM tc_game_fake_ping_plans p JOIN tc_game_joker_reservations r"
                + " ON r.gamesessionid = p.gamesessionid AND r.id = p.reservationid"
                + " LEFT JOIN tc_game_requests q ON q.gamesessionid = r.gamesessionid AND q.id = r.requestid"
                + " JOIN tc_game_timetable_entries e ON e.gamesessionid = p.gamesessionid"
                + " AND e.id = p.timetableentryid WHERE p.gamesessionid = ? AND " + ARMED,
                rows -> new Planned(rows.getLong(1), rows.getLong(2), rows.getBoolean(3), rows.getString(4)),
                sessionId);
        for (Planned item : planned) {
            String detail = !item.active() ? "Its timetable ping was removed"
                    : !GameTimetableService.fakeEligible(item.content())
                            ? "Its timetable ping no longer reaches the hunters" : null;
            if (detail != null) {
                release(transaction, reservation(transaction, sessionId, item.reservationId()).orElseThrow(),
                        null, detail);
                owners.add(item.memberId());
            }
        }
        return owners;
    }

    /** Members holding the given reservations (pool reservations have none). */
    public static Set<Long> owners(GameTransaction transaction, long sessionId, List<Long> reservationIds)
            throws SQLException {
        Set<Long> result = new LinkedHashSet<>();
        for (long id : reservationIds) {
            reservation(transaction, sessionId, id).map(Reservation::memberId).ifPresent(result::add);
        }
        return result;
    }

    /** Every reservation of a session, newest first, for the operators' views; at most {@code limit}. */
    public static List<Reservation> latest(GameTransaction transaction, long sessionId, String type, String status,
                                           int limit) throws SQLException {
        return transaction.list(RESERVATIONS + " WHERE r.gamesessionid = ? AND r.type = ? AND r.status = ?"
                + " ORDER BY r.id DESC", limit, GameJokerRepository::reservation, sessionId, type, status);
    }

    static List<Long> ids(List<Reservation> reservations) {
        List<Long> result = new ArrayList<>();
        reservations.forEach(item -> result.add(item.id()));
        return result;
    }

}
