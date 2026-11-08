/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.database;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Joker inventories of players and supporters, keyed by their real tracker. A use is reserved while a request
 * is open and consumed when its effect succeeds; any other end releases it. Operators grant and revoke unused
 * quantities; a confirmed operator relay may use a joker beyond the allocation. Every change is in the ledger.
 * Break jokers can only be granted once the session's game settings configure a positive break duration.
 */
@Singleton
public class GameJokerService {

    public static final String HUNTER_POSITIONS = "hunterPositions";
    public static final String FAKE_TIMETABLE = "fakeTimetable";
    public static final String FAKE_SPEEDHUNT = "fakeSpeedhunt";
    public static final String BREAK = "break";
    public static final List<String> TYPES = List.of(HUNTER_POSITIONS, FAKE_TIMETABLE, FAKE_SPEEDHUNT, BREAK);
    /** Types that can be granted and used in this version. */
    public static final Set<String> ENABLED = Set.of(HUNTER_POSITIONS, BREAK);
    /** Request type of each joker type. */
    public static final Map<String, String> REQUEST_TYPES = Map.of(
            "jokerHunterPositions", HUNTER_POSITIONS, "jokerBreak", BREAK);
    /** Types an operator action may use directly (relays); other types are used only through requests. */
    private static final Set<String> OPERATOR_USES = Set.of(HUNTER_POSITIONS);
    private static final ObjectMapper CONFIG_READER = new ObjectMapper();

    public static final String RESERVED = "reserved";
    public static final String CONSUMED = "consumed";
    public static final String RELEASED = "released";
    private static final Set<String> TERMINAL_REQUESTS = Set.of(
            GameRequestService.SUCCEEDED, GameRequestService.FAILED, GameRequestService.REJECTED,
            GameRequestService.EXPIRED, GameRequestService.CANCELLED);

    public record Inventory(long deviceId, String type, int granted, int used, int reserved, int available,
                            int overAllocated) { }
    public record Reservation(long id, long deviceId, String type, Long requestId, String status,
                              String createdAt) { }
    public record Overview(List<Inventory> inventories, List<Reservation> reservations) { }
    public record LedgerEntry(long id, long deviceId, String type, String kind, int quantity, Long requestId,
                              Long reservationId, String actor, String detail, String occurredAt) { }
    public record Change(Long deviceId, String type, Integer quantity, String reason) { }
    public record Use(Long deviceId, String type, Boolean overAllocate, String detail) { }
    public record UseOutcome(long deviceId, String type, boolean overAllocated) { }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws Exception;
    }

    private final DataSource dataSource;

    @Inject
    public GameJokerService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private <T> T transaction(Work<T> work) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private static String iso(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column, utc());
        return value == null ? null : value.toInstant().toString();
    }

    private static String text(String value, int length) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("\\p{Cntrl}", " ").trim();
        return cleaned.length() > length ? cleaned.substring(0, length) : cleaned;
    }

    static String type(String value) {
        if (value == null || !TYPES.contains(value)) {
            throw new BadRequestException("Unknown joker type");
        }
        if (!ENABLED.contains(value)) {
            throw new BadRequestException("This joker type is not available yet");
        }
        return value;
    }

    /** Players and supporters of a session, by real tracker. */
    private static Set<Long> participants(Connection connection, long gameSessionId) throws SQLException {
        Set<Long> result = new java.util.LinkedHashSet<>();
        try (var query = connection.prepareStatement("SELECT assigneddeviceid FROM tc_game_session_members"
                + " WHERE gamesessionid = ? AND role IN ('player','supporter') AND assigneddeviceid IS NOT NULL")) {
            query.setLong(1, gameSessionId);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(rows.getLong(1));
                }
            }
        }
        return result;
    }

    /**
     * The break duration in minutes configured in the session's game settings
     * ({@code game_settings.break_duration_minutes}), or {@code null} when none is configured.
     */
    static Double breakMinutes(Connection connection, long gameSessionId) throws SQLException {
        String config;
        try (var query = connection.prepareStatement("SELECT config FROM tc_game_sessions WHERE id = ?")) {
            query.setLong(1, gameSessionId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                config = rows.getString(1);
            }
        }
        JsonNode minutes;
        try {
            minutes = CONFIG_READER.readTree(config == null ? "{}" : config)
                    .path("game_settings").path("break_duration_minutes");
        } catch (Exception error) {
            return null;
        }
        if (!minutes.isNumber() || !Double.isFinite(minutes.asDouble()) || minutes.asDouble() <= 0) {
            return null;
        }
        return minutes.asDouble();
    }

    private static int[] counts(Connection connection, long gameSessionId, long deviceId, String type, boolean lock)
            throws SQLException {
        int granted = 0;
        int used = 0;
        try (var query = connection.prepareStatement("SELECT granted,used FROM tc_game_jokers WHERE gamesessionid = ?"
                + " AND deviceid = ? AND type = ?" + (lock ? " FOR UPDATE" : ""))) {
            query.setLong(1, gameSessionId);
            query.setLong(2, deviceId);
            query.setString(3, type);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    granted = rows.getInt(1);
                    used = rows.getInt(2);
                }
            }
        }
        int reserved;
        try (var query = connection.prepareStatement("SELECT COUNT(*) FROM tc_game_joker_reservations"
                + " WHERE gamesessionid = ? AND deviceid = ? AND type = ? AND status = ?")) {
            query.setLong(1, gameSessionId);
            query.setLong(2, deviceId);
            query.setString(3, type);
            query.setString(4, RESERVED);
            try (var rows = query.executeQuery()) {
                rows.next();
                reserved = rows.getInt(1);
            }
        }
        return new int[] {granted, used, reserved};
    }

    private static Inventory inventory(long deviceId, String type, int[] counts) {
        int available = Math.max(0, counts[0] - counts[1] - counts[2]);
        return new Inventory(deviceId, type, counts[0], counts[1], counts[2], available,
                Math.max(0, counts[1] - counts[0]));
    }

    private static void store(Connection connection, long gameSessionId, long deviceId, String type, int granted,
                              int used) throws SQLException {
        int updated;
        try (var query = connection.prepareStatement("UPDATE tc_game_jokers SET granted = ?, used = ?, updatedat = ?"
                + " WHERE gamesessionid = ? AND deviceid = ? AND type = ?")) {
            query.setInt(1, granted);
            query.setInt(2, used);
            query.setTimestamp(3, Timestamp.from(now()), utc());
            query.setLong(4, gameSessionId);
            query.setLong(5, deviceId);
            query.setString(6, type);
            updated = query.executeUpdate();
        }
        if (updated == 0) {
            try (var query = connection.prepareStatement("INSERT INTO tc_game_jokers"
                    + " (gamesessionid,deviceid,type,granted,used,updatedat) VALUES (?,?,?,?,?,?)")) {
                query.setLong(1, gameSessionId);
                query.setLong(2, deviceId);
                query.setString(3, type);
                query.setInt(4, granted);
                query.setInt(5, used);
                query.setTimestamp(6, Timestamp.from(now()), utc());
                query.executeUpdate();
            }
        }
    }

    private static void ledger(Connection connection, long gameSessionId, long deviceId, String type, String kind,
                               int quantity, Long requestId, Long reservationId, String actor, String detail)
            throws SQLException {
        try (var query = connection.prepareStatement("INSERT INTO tc_game_joker_ledger (gamesessionid,deviceid,type,"
                + "kind,quantity,requestid,reservationid,actor,detail,occurredat) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            query.setLong(1, gameSessionId);
            query.setLong(2, deviceId);
            query.setString(3, type);
            query.setString(4, kind);
            query.setInt(5, quantity);
            if (requestId == null) {
                query.setNull(6, Types.BIGINT);
            } else {
                query.setLong(6, requestId);
            }
            if (reservationId == null) {
                query.setNull(7, Types.BIGINT);
            } else {
                query.setLong(7, reservationId);
            }
            query.setString(8, text(actor, 128));
            query.setString(9, text(detail, 512));
            query.setTimestamp(10, Timestamp.from(now()), utc());
            query.executeUpdate();
        }
    }

    // --- reading ------------------------------------------------------------------------------------------

    /** Granted, used and reserved counts per tracker and type, read with two queries. */
    private static Map<Long, Map<String, int[]>> tallies(Connection connection, long gameSessionId, Long deviceId)
            throws SQLException {
        Map<Long, Map<String, int[]>> result = new LinkedHashMap<>();
        String device = deviceId == null ? "" : " AND deviceid = ?";
        try (var query = connection.prepareStatement("SELECT deviceid,type,granted,used FROM tc_game_jokers"
                + " WHERE gamesessionid = ?" + device)) {
            query.setLong(1, gameSessionId);
            if (deviceId != null) {
                query.setLong(2, deviceId);
            }
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    int[] counts = result.computeIfAbsent(rows.getLong(1), key -> new LinkedHashMap<>())
                            .computeIfAbsent(rows.getString(2), key -> new int[3]);
                    counts[0] = rows.getInt(3);
                    counts[1] = rows.getInt(4);
                }
            }
        }
        try (var query = connection.prepareStatement("SELECT deviceid,type,COUNT(*) FROM tc_game_joker_reservations"
                + " WHERE gamesessionid = ? AND status = ?" + device + " GROUP BY deviceid,type")) {
            query.setLong(1, gameSessionId);
            query.setString(2, RESERVED);
            if (deviceId != null) {
                query.setLong(3, deviceId);
            }
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    result.computeIfAbsent(rows.getLong(1), key -> new LinkedHashMap<>())
                            .computeIfAbsent(rows.getString(2), key -> new int[3])[2] = rows.getInt(3);
                }
            }
        }
        return result;
    }

    private static List<Inventory> inventories(Collection<Long> devices, Map<Long, Map<String, int[]>> tallies) {
        List<Inventory> result = new ArrayList<>();
        for (long deviceId : devices) {
            Map<String, int[]> byType = tallies.getOrDefault(deviceId, Map.of());
            for (String type : TYPES) {
                result.add(inventory(deviceId, type, byType.getOrDefault(type, new int[3])));
            }
        }
        return result;
    }

    public Overview overview(long gameSessionId) throws Exception {
        return transaction(connection -> {
            Set<Long> devices = participants(connection, gameSessionId);
            var tallies = tallies(connection, gameSessionId, null);
            devices.addAll(tallies.keySet());
            return new Overview(inventories(devices, tallies), reservations(connection, gameSessionId, null));
        });
    }

    /** A participant's own inventory and open reservations. */
    public Overview own(long gameSessionId, long deviceId) throws Exception {
        return transaction(connection -> new Overview(
                inventories(List.of(deviceId), tallies(connection, gameSessionId, deviceId)),
                reservations(connection, gameSessionId, deviceId)));
    }

    private static List<Reservation> reservations(Connection connection, long gameSessionId, Long deviceId)
            throws SQLException {
        List<Reservation> result = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT * FROM tc_game_joker_reservations WHERE gamesessionid = ?"
                + " AND status = ?" + (deviceId == null ? "" : " AND deviceid = ?") + " ORDER BY id")) {
            query.setLong(1, gameSessionId);
            query.setString(2, RESERVED);
            if (deviceId != null) {
                query.setLong(3, deviceId);
            }
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    long request = rows.getLong("requestid");
                    // Reservations of armed fakes have no request.
                    Long requestId = rows.wasNull() ? null : request;
                    result.add(new Reservation(rows.getLong("id"), rows.getLong("deviceid"), rows.getString("type"),
                            requestId, rows.getString("status"), iso(rows, "createdat")));
                }
            }
        }
        return result;
    }

    public List<LedgerEntry> ledger(long gameSessionId, int requestedLimit) throws Exception {
        int limit = requestedLimit <= 0 ? 200 : Math.min(requestedLimit, 1000);
        return transaction(connection -> {
            List<LedgerEntry> result = new ArrayList<>();
            try (var query = connection.prepareStatement(
                    "SELECT * FROM tc_game_joker_ledger WHERE gamesessionid = ? ORDER BY id DESC")) {
                query.setLong(1, gameSessionId);
                query.setMaxRows(limit);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        long request = rows.getLong("requestid");
                        Long requestId = rows.wasNull() ? null : request;
                        long reservation = rows.getLong("reservationid");
                        Long reservationId = rows.wasNull() ? null : reservation;
                        result.add(new LedgerEntry(rows.getLong("id"), rows.getLong("deviceid"), rows.getString("type"),
                                rows.getString("kind"), rows.getInt("quantity"), requestId, reservationId,
                                rows.getString("actor"), rows.getString("detail"), iso(rows, "occurredat")));
                    }
                }
            }
            return result;
        });
    }

    // --- operator changes ---------------------------------------------------------------------------------

    /** Grants (positive quantity) or revokes (negative) unused jokers; revoking never touches used or reserved. */
    public Inventory change(long gameSessionId, Change change, String actor) throws Exception {
        if (change == null || change.deviceId() == null || change.quantity() == null || change.quantity() == 0
                || Math.abs(change.quantity()) > 1000) {
            throw new BadRequestException("Participant and a non-zero quantity are required");
        }
        String type = type(change.type());
        return transaction(connection -> {
            if (!participants(connection, gameSessionId).contains(change.deviceId())) {
                throw new BadRequestException("Jokers belong to players and supporters of this session");
            }
            if (BREAK.equals(type) && change.quantity() > 0 && breakMinutes(connection, gameSessionId) == null) {
                throw new WebApplicationException(
                        "Set a positive break duration in the game settings before granting break jokers", 409);
            }
            int[] counts = counts(connection, gameSessionId, change.deviceId(), type, true);
            int available = Math.max(0, counts[0] - counts[1] - counts[2]);
            if (change.quantity() < 0 && -change.quantity() > available) {
                throw new WebApplicationException("Only " + available + " unused joker(s) can be revoked", 409);
            }
            int granted = counts[0] + change.quantity();
            store(connection, gameSessionId, change.deviceId(), type, granted, counts[1]);
            ledger(connection, gameSessionId, change.deviceId(), type, change.quantity() > 0 ? "grant" : "revoke",
                    Math.abs(change.quantity()), null, null, actor, change.reason());
            return inventory(change.deviceId(), type, counts(connection, gameSessionId, change.deviceId(), type,
                    false));
        });
    }

    // --- inside other transactions ------------------------------------------------------------------------

    /** Reserves one use for a new request; refuses when none is available. */
    static void reserve(Connection connection, long gameSessionId, long deviceId, String type, long requestId,
                        String actor) throws SQLException {
        int[] counts = counts(connection, gameSessionId, deviceId, type, true);
        if (counts[0] - counts[1] - counts[2] <= 0) {
            throw new WebApplicationException("No joker of this type is available", 409);
        }
        Instant now = now();
        long id;
        try (var query = connection.prepareStatement("INSERT INTO tc_game_joker_reservations (gamesessionid,deviceid,"
                + "type,requestid,status,details,createdat,updatedat) VALUES (?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            query.setLong(1, gameSessionId);
            query.setLong(2, deviceId);
            query.setString(3, type);
            query.setLong(4, requestId);
            query.setString(5, RESERVED);
            query.setString(6, "{}");
            query.setTimestamp(7, Timestamp.from(now), utc());
            query.setTimestamp(8, Timestamp.from(now), utc());
            query.executeUpdate();
            try (var keys = query.getGeneratedKeys()) {
                keys.next();
                id = keys.getLong(1);
            }
        }
        ledger(connection, gameSessionId, deviceId, type, "reserve", 1, requestId, id, actor, null);
    }

    /**
     * Settles the reservations of requests that reached an end: a succeeded request consumes its use, any other
     * end releases it. Runs in every transaction that ends requests.
     */
    static void settle(Connection connection) throws SQLException {
        record Pending(long id, long gameSessionId, long deviceId, String type, long requestId, String status) { }
        List<Pending> pending = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT r.id,r.gamesessionid,r.deviceid,r.type,r.requestid,"
                + "q.status FROM tc_game_joker_reservations r JOIN tc_game_requests q ON q.id = r.requestid"
                + " WHERE r.status = ?")) {
            query.setString(1, RESERVED);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    if (TERMINAL_REQUESTS.contains(rows.getString(6))) {
                        pending.add(new Pending(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getString(4),
                                rows.getLong(5), rows.getString(6)));
                    }
                }
            }
        }
        for (Pending item : pending) {
            boolean consumed = GameRequestService.SUCCEEDED.equals(item.status());
            try (var query = connection.prepareStatement(
                    "UPDATE tc_game_joker_reservations SET status = ?, updatedat = ? WHERE id = ?")) {
                query.setString(1, consumed ? CONSUMED : RELEASED);
                query.setTimestamp(2, Timestamp.from(now()), utc());
                query.setLong(3, item.id());
                query.executeUpdate();
            }
            if (consumed) {
                int[] counts = counts(connection, item.gameSessionId(), item.deviceId(), item.type(), true);
                store(connection, item.gameSessionId(), item.deviceId(), item.type(), counts[0], counts[1] + 1);
            }
            ledger(connection, item.gameSessionId(), item.deviceId(), item.type(), consumed ? "use" : "release", 1,
                    item.requestId(), item.id(), null, consumed ? null : "Request " + item.status());
        }
    }

    /**
     * Uses one joker for an operator action. Reserved uses are never taken; without an available joker the use
     * needs the operator's confirmation and is recorded beyond the allocation.
     */
    static UseOutcome use(Connection connection, long gameSessionId, Use use, String actor) throws SQLException {
        if (use == null || use.deviceId() == null) {
            throw new BadRequestException("Joker use needs a participant");
        }
        String type = type(use.type());
        if (!OPERATOR_USES.contains(type)) {
            throw new BadRequestException("Operator actions only use Get hunter positions jokers");
        }
        if (!participants(connection, gameSessionId).contains(use.deviceId())) {
            throw new BadRequestException("Jokers belong to players and supporters of this session");
        }
        int[] counts = counts(connection, gameSessionId, use.deviceId(), type, true);
        boolean over = counts[0] - counts[1] - counts[2] <= 0;
        if (over && !Boolean.TRUE.equals(use.overAllocate())) {
            throw new WebApplicationException("No joker of this type is available; confirm a use beyond the allocation",
                    409);
        }
        store(connection, gameSessionId, use.deviceId(), type, counts[0], counts[1] + 1);
        ledger(connection, gameSessionId, use.deviceId(), type, over ? "overuse" : "use", 1, null, null, actor,
                use.detail());
        return new UseOutcome(use.deviceId(), type, over);
    }

    /** Ends the open requests of caught participants and releases their reservations. */
    static void participantsCaught(Connection connection, long gameSessionId, Collection<Long> deviceIds,
                                   Instant now) throws SQLException {
        if (deviceIds.isEmpty()) {
            return;
        }
        Map<Long, Long> members = new LinkedHashMap<>();
        try (var query = connection.prepareStatement("SELECT userid,assigneddeviceid FROM tc_game_session_members"
                + " WHERE gamesessionid = ? AND assigneddeviceid IS NOT NULL")) {
            query.setLong(1, gameSessionId);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    members.put(rows.getLong(1), rows.getLong(2));
                }
            }
        }
        for (var entry : members.entrySet()) {
            if (!deviceIds.contains(entry.getValue())) {
                continue;
            }
            try (var query = connection.prepareStatement("UPDATE tc_game_requests SET status = ?, message = ?,"
                    + " updatedat = ?, completedat = ? WHERE gamesessionid = ? AND requesteruserid = ?"
                    + " AND status IN ('pending','approved','executing')")) {
                query.setString(1, GameRequestService.CANCELLED);
                query.setString(2, "You are out of the game");
                query.setTimestamp(3, Timestamp.from(now), utc());
                query.setTimestamp(4, Timestamp.from(now), utc());
                query.setLong(5, gameSessionId);
                query.setLong(6, entry.getKey());
                query.executeUpdate();
            }
        }
        settle(connection);
    }
}
