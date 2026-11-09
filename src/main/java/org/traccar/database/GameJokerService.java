/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.database;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
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
import java.util.HashMap;
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
 * <p>
 * Fake jokers are armed instead of used when their request succeeds: the reservation stays, without its request
 * and with the owner's plan in {@code details}, until the Tool reports the fake delivered (used) or skipped
 * (released). Owners may cancel an armed timetable fake until shortly before its ping. Plans are private to their
 * owner and the operators.
 * <p>
 * Armed speedhunt fakes wait for the next speedhunt run: the delivery of a run's first ping binds every armed
 * speedhunt fake it lists, whoever the run targets, and uses their jokers in the same transaction. Bound fakes are
 * never refunded; only the target's own fake applies, at its ping number.
 */
@Singleton
public class GameJokerService {

    public static final String HUNTER_POSITIONS = "hunterPositions";
    public static final String FAKE_TIMETABLE = "fakeTimetable";
    public static final String FAKE_SPEEDHUNT = "fakeSpeedhunt";
    public static final String BREAK = "break";
    public static final List<String> TYPES = List.of(HUNTER_POSITIONS, FAKE_TIMETABLE, FAKE_SPEEDHUNT, BREAK);
    /** Types that can be granted and used in this version. */
    public static final Set<String> ENABLED = Set.of(HUNTER_POSITIONS, BREAK, FAKE_TIMETABLE, FAKE_SPEEDHUNT);
    /** Request type of each joker type. */
    public static final Map<String, String> REQUEST_TYPES = Map.of(
            "jokerHunterPositions", HUNTER_POSITIONS, "jokerBreak", BREAK, "jokerFakeTimetable", FAKE_TIMETABLE,
            "jokerFakeSpeedhunt", FAKE_SPEEDHUNT);
    /** Fake types: a succeeded request arms the fake; the joker is used when the fake takes effect. */
    static final Set<String> FAKE_TYPES = Set.of(FAKE_TIMETABLE, FAKE_SPEEDHUNT);
    /** Timetable fakes are armed and cancelled at least this long before their ping. */
    public static final int FAKE_TIMETABLE_CUTOFF_SECONDS = 60;
    /**
     * Timetable cells a fake can replace: blank (every audience) and the target codes that reach hunters. The Tool
     * parses the same codes ({@code timetable_target_config_for_code}); {@code b}, {@code x}, operator coordinates
     * and codes without hunters are not eligible.
     */
    static final Set<String> FAKE_TIMETABLE_CONTENT = Set.of("", "2H", "2P2H", "2S2H", "2P2S2H");
    public static final String USED = "used";
    /** A speedhunt fake bound to the run whose first ping the same delivery sends. */
    public static final String BOUND = "bound";
    /** Pings per speedhunt when the stored settings do not say (the Tool's default). */
    static final int DEFAULT_PINGS_PER_SPEEDHUNT = 4;
    /** The highest ping number a fake may name when speedhunts have no ping limit. */
    public static final int MAX_FAKE_PING_NUMBER = 1000;
    /** Bound speedhunt fakes listed for an owner, and for the operators across the session. */
    private static final int BOUND_FAKES_OWN = 5;
    private static final int BOUND_FAKES_OVERVIEW = 50;
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
    /**
     * {@code armed} fakes have no request and carry the owner's plan (coordinate, timetable entry or speedhunt ping
     * number); {@code bound} speedhunt fakes are used and belong to the run they were bound to.
     */
    public record Reservation(long id, long deviceId, String type, Long requestId, String status,
                              String createdAt, boolean armed, boolean bound, JsonNode plan) { }
    /** The operators' overview; {@code boundFakes} are the latest speedhunt fakes bound to a run (used). */
    public record Overview(List<Inventory> inventories, List<Reservation> reservations,
                           List<Reservation> boundFakes) { }
    /** One of the owner's upcoming timetable pings a fake can replace; {@code taken} when one is planned. */
    public record FakeEntry(long entryId, String scheduledAt, boolean taken) { }
    public record Own(List<Inventory> inventories, List<Reservation> reservations,
                      List<FakeEntry> timetableEntries) { }
    /**
     * The Tool's report on an armed fake: a timetable fake {@code used} (delivered) or {@code released}, a speedhunt
     * fake {@code bound} to the run ({@code run}) whose first ping this delivery sends.
     */
    public record FakeSettlement(Long reservationId, String outcome, String detail, String run) {
        public FakeSettlement(Long reservationId, String outcome, String detail) {
            this(reservationId, outcome, detail, null);
        }
    }
    /** {@code consumed}, {@code released} or {@code skipped} (no longer armed). */
    public record FakeOutcome(long reservationId, String status) { }
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

    static String deviceName(Connection connection, long deviceId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT name FROM tc_devices WHERE id = ?")) {
            query.setLong(1, deviceId);
            try (var rows = query.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    static boolean fakeEligible(String content) {
        return content != null && FAKE_TIMETABLE_CONTENT.contains(content.trim());
    }

    private static JsonNode plan(String details) {
        try {
            JsonNode node = CONFIG_READER.readTree(details == null ? "{}" : details);
            return node.isObject() ? node : JsonNodeFactory.instance.objectNode();
        } catch (Exception error) {
            return JsonNodeFactory.instance.objectNode();
        }
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

    /**
     * The ping limit of a speedhunt from the session's game settings ({@code game_settings.pings_per_speedhunt}),
     * or {@code null} when speedhunts are unlimited (a blank setting).
     */
    static Integer pingsPerSpeedhunt(Connection connection, long gameSessionId) throws SQLException {
        String config;
        try (var query = connection.prepareStatement("SELECT config FROM tc_game_sessions WHERE id = ?")) {
            query.setLong(1, gameSessionId);
            try (var rows = query.executeQuery()) {
                config = rows.next() ? rows.getString(1) : null;
            }
        }
        JsonNode settings;
        try {
            settings = CONFIG_READER.readTree(config == null ? "{}" : config).path("game_settings");
        } catch (Exception error) {
            return DEFAULT_PINGS_PER_SPEEDHUNT;
        }
        JsonNode pings = settings.path("pings_per_speedhunt");
        if (pings.isMissingNode() || pings.isNull()) {
            return DEFAULT_PINGS_PER_SPEEDHUNT;
        }
        if (pings.isTextual() && pings.asText().isBlank()) {
            return null;
        }
        return pings.canConvertToInt() && pings.asInt() > 0 ? pings.asInt() : DEFAULT_PINGS_PER_SPEEDHUNT;
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
            return new Overview(inventories(devices, tallies), reservations(connection, gameSessionId, null),
                    boundSpeedhuntFakes(connection, gameSessionId, null));
        });
    }

    /** A participant's own inventory, open reservations and armed plans, and the pings a fake can replace. */
    public Own own(long gameSessionId, long deviceId) throws Exception {
        return transaction(connection -> {
            List<Reservation> reservations = reservations(connection, gameSessionId, deviceId);
            Set<Long> taken = new java.util.HashSet<>();
            for (Reservation item : reservations) {
                if (FAKE_TIMETABLE.equals(item.type()) && item.plan().hasNonNull("entryId")) {
                    taken.add(item.plan().get("entryId").asLong());
                }
            }
            List<Reservation> visible = new ArrayList<>(reservations);
            visible.addAll(boundSpeedhuntFakes(connection, gameSessionId, deviceId));
            return new Own(inventories(List.of(deviceId), tallies(connection, gameSessionId, deviceId)),
                    visible, fakeEntries(connection, gameSessionId, deviceId, taken));
        });
    }

    /**
     * The most recent speedhunt fakes that were bound to a run (used): the owner's own ({@code deviceId}), so they
     * see the binding, or every owner's for the operators ({@code deviceId} null), who also see the run reference.
     */
    private static List<Reservation> boundSpeedhuntFakes(Connection connection, long gameSessionId, Long deviceId)
            throws SQLException {
        List<Reservation> result = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT * FROM tc_game_joker_reservations WHERE gamesessionid = ?"
                + " AND type = ? AND status = ?" + (deviceId == null ? "" : " AND deviceid = ?")
                + " ORDER BY id DESC")) {
            query.setLong(1, gameSessionId);
            query.setString(2, FAKE_SPEEDHUNT);
            query.setString(3, CONSUMED);
            if (deviceId != null) {
                query.setLong(4, deviceId);
            }
            query.setMaxRows(deviceId == null ? BOUND_FAKES_OVERVIEW : BOUND_FAKES_OWN);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    ObjectNode plan = (ObjectNode) plan(rows.getString("details"));
                    if (plan.hasNonNull("boundAt")) {
                        if (deviceId != null) {
                            // The run reference stays with the operators: owners only learn that and when it bound.
                            plan.remove("boundTo");
                        }
                        result.add(new Reservation(rows.getLong("id"), rows.getLong("deviceid"), FAKE_SPEEDHUNT, null,
                                CONSUMED, iso(rows, "createdat"), false, true, plan));
                    }
                }
            }
        }
        return result;
    }

    /** The participant's active timetable entries that reach hunters and are far enough ahead for a fake. */
    private static List<FakeEntry> fakeEntries(Connection connection, long gameSessionId, long deviceId,
                                               Set<Long> taken) throws SQLException {
        List<FakeEntry> result = new ArrayList<>();
        String name = deviceName(connection, deviceId);
        if (name == null) {
            return result;
        }
        try (var query = connection.prepareStatement("SELECT id,scheduledat,content FROM tc_game_timetable_entries"
                + " WHERE gamesessionid = ? AND participant = ? AND active = ? AND scheduledat > ?"
                + " ORDER BY scheduledat")) {
            query.setLong(1, gameSessionId);
            query.setString(2, name);
            query.setBoolean(3, true);
            query.setTimestamp(4, Timestamp.from(now().plusSeconds(FAKE_TIMETABLE_CUTOFF_SECONDS)), utc());
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    if (fakeEligible(rows.getString("content"))) {
                        long id = rows.getLong("id");
                        result.add(new FakeEntry(id, iso(rows, "scheduledat"), taken.contains(id)));
                    }
                }
            }
        }
        return result;
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
                    String type = rows.getString("type");
                    JsonNode plan = plan(rows.getString("details"));
                    result.add(new Reservation(rows.getLong("id"), rows.getLong("deviceid"), type,
                            requestId, rows.getString("status"), iso(rows, "createdat"),
                            requestId == null && FAKE_TYPES.contains(type), false, plan));
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
            if (change.quantity() > 0) {
                // Break and fake jokers need hunters to see participants only through mirrors.
                GameAccessRule.checkGrant(connection, gameSessionId, type);
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
        reserve(connection, gameSessionId, deviceId, type, requestId, actor, null);
    }

    /** Reserves one use for a new request with its plan (a fake's coordinate and ping). */
    static void reserve(Connection connection, long gameSessionId, long deviceId, String type, long requestId,
                        String actor, JsonNode details) throws SQLException {
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
            query.setString(6, details == null ? "{}" : details.toString());
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
     * Settles the reservations of requests that reached an end: a succeeded request consumes its use (a fake is
     * armed instead), any other end releases it. Runs in every transaction that ends requests.
     */
    static void settle(Connection connection) throws SQLException {
        record Pending(long id, long gameSessionId, long deviceId, String type, long requestId, String status,
                       String details) { }
        List<Pending> pending = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT r.id,r.gamesessionid,r.deviceid,r.type,r.requestid,"
                + "q.status,r.details FROM tc_game_joker_reservations r JOIN tc_game_requests q"
                + " ON q.id = r.requestid WHERE r.status = ?")) {
            query.setString(1, RESERVED);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    if (TERMINAL_REQUESTS.contains(rows.getString(6))) {
                        pending.add(new Pending(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getString(4),
                                rows.getLong(5), rows.getString(6), rows.getString(7)));
                    }
                }
            }
        }
        for (Pending item : pending) {
            boolean consumed = GameRequestService.SUCCEEDED.equals(item.status());
            if (consumed && FAKE_TYPES.contains(item.type())) {
                // The fake is armed: it keeps its reservation, without the request, until it takes effect.
                ObjectNode details = (ObjectNode) plan(item.details());
                details.put("requestId", item.requestId());
                details.put("armedAt", now().toString());
                try (var query = connection.prepareStatement("UPDATE tc_game_joker_reservations SET requestid = NULL,"
                        + " details = ?, updatedat = ? WHERE id = ?")) {
                    query.setString(1, details.toString());
                    query.setTimestamp(2, Timestamp.from(now()), utc());
                    query.setLong(3, item.id());
                    query.executeUpdate();
                }
                ledger(connection, item.gameSessionId(), item.deviceId(), item.type(), "arm", 1, item.requestId(),
                        item.id(), null, null);
                continue;
            }
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

    private record Armed(long id, long gameSessionId, long deviceId, String type, JsonNode plan) { }

    /** An armed fake of the session, locked; {@code null} when the reservation is not an armed fake any more. */
    private static Armed armed(Connection connection, long gameSessionId, long reservationId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT id,gamesessionid,deviceid,type,requestid,status,details"
                + " FROM tc_game_joker_reservations WHERE id = ? AND gamesessionid = ? FOR UPDATE")) {
            query.setLong(1, reservationId);
            query.setLong(2, gameSessionId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new NotFoundException("Joker reservation not found");
                }
                rows.getLong("requestid");
                boolean open = !rows.wasNull();
                if (open || !RESERVED.equals(rows.getString("status"))
                        || !FAKE_TYPES.contains(rows.getString("type"))) {
                    return null;
                }
                return new Armed(rows.getLong("id"), gameSessionId, rows.getLong("deviceid"), rows.getString("type"),
                        plan(rows.getString("details")));
            }
        }
    }

    private static void endArmed(Connection connection, Armed fake, boolean used, String actor, String detail)
            throws SQLException {
        try (var query = connection.prepareStatement(
                "UPDATE tc_game_joker_reservations SET status = ?, updatedat = ? WHERE id = ?")) {
            query.setString(1, used ? CONSUMED : RELEASED);
            query.setTimestamp(2, Timestamp.from(now()), utc());
            query.setLong(3, fake.id());
            query.executeUpdate();
        }
        if (used) {
            int[] counts = counts(connection, fake.gameSessionId(), fake.deviceId(), fake.type(), true);
            store(connection, fake.gameSessionId(), fake.deviceId(), fake.type(), counts[0], counts[1] + 1);
        }
        Long requestId = fake.plan().hasNonNull("requestId") ? fake.plan().get("requestId").asLong() : null;
        ledger(connection, fake.gameSessionId(), fake.deviceId(), fake.type(), used ? "use" : "release", 1,
                requestId, fake.id(), actor, detail);
    }

    /**
     * Records the Tool's reports on armed timetable fakes inside an effects transaction: {@code used} when the
     * hunters received the fake with the same delivery, {@code released} when its ping was skipped or failed. A
     * fake that is no longer armed (already settled, cancelled, or its owner caught) is skipped.
     */
    static List<FakeOutcome> settleFakes(Connection connection, long gameSessionId, List<FakeSettlement> items)
            throws SQLException {
        List<FakeOutcome> result = new ArrayList<>();
        for (FakeSettlement item : items) {
            if (item == null || item.reservationId() == null || !USED.equals(item.outcome())
                    && !RELEASED.equals(item.outcome()) && !BOUND.equals(item.outcome())) {
                throw new BadRequestException("Invalid fake outcome");
            }
            Armed fake = armed(connection, gameSessionId, item.reservationId());
            if (fake == null) {
                result.add(new FakeOutcome(item.reservationId(), "skipped"));
                continue;
            }
            if (BOUND.equals(item.outcome())) {
                if (!FAKE_SPEEDHUNT.equals(fake.type())) {
                    throw new BadRequestException("Only speedhunt fakes are bound to a speedhunt");
                }
                // Bound to this run and used for its owner, whoever the run targets; never refunded.
                ObjectNode details = (ObjectNode) fake.plan().deepCopy();
                details.put("boundAt", now().toString());
                details.put("boundTo", text(item.run(), 64));
                try (var query = connection.prepareStatement(
                        "UPDATE tc_game_joker_reservations SET details = ? WHERE id = ?")) {
                    query.setString(1, details.toString());
                    query.setLong(2, fake.id());
                    query.executeUpdate();
                }
                endArmed(connection, fake, true, "Tool", "Bound to a speedhunt");
                result.add(new FakeOutcome(fake.id(), CONSUMED));
                continue;
            }
            if (!FAKE_TIMETABLE.equals(fake.type())) {
                throw new BadRequestException("Only timetable fakes are used or released by deliveries");
            }
            boolean used = USED.equals(item.outcome());
            endArmed(connection, fake, used, "Tool", text(item.detail(), 512));
            result.add(new FakeOutcome(fake.id(), used ? CONSUMED : RELEASED));
        }
        return result;
    }

    /** The owner cancels an armed fake; a timetable fake only until shortly before its ping. */
    public Reservation cancelFake(long gameSessionId, long deviceId, long reservationId, String actor)
            throws Exception {
        return transaction(connection -> {
            Armed fake = armed(connection, gameSessionId, reservationId);
            if (fake == null || fake.deviceId() != deviceId) {
                if (fake != null) {
                    throw new ForbiddenException("Only the owner can cancel this fake");
                }
                throw new WebApplicationException("This fake can no longer be cancelled", 409);
            }
            if (FAKE_TIMETABLE.equals(fake.type())) {
                Instant scheduled = Instant.parse(fake.plan().path("scheduledAt").asText());
                if (!scheduled.isAfter(now().plusSeconds(FAKE_TIMETABLE_CUTOFF_SECONDS))) {
                    throw new WebApplicationException("This ping is too close to cancel its fake", 409);
                }
            }
            endArmed(connection, fake, false, actor, "Cancelled by the owner");
            return new Reservation(fake.id(), fake.deviceId(), fake.type(), null, RELEASED, null, false, false,
                    fake.plan());
        });
    }

    /** The member accounts of the players and supporters holding the given trackers. */
    static Set<Long> ownerUsers(Connection connection, long gameSessionId, Collection<Long> deviceIds)
            throws SQLException {
        Set<Long> result = new java.util.LinkedHashSet<>();
        if (deviceIds.isEmpty()) {
            return result;
        }
        try (var query = connection.prepareStatement("SELECT userid,assigneddeviceid FROM tc_game_session_members"
                + " WHERE gamesessionid = ? AND role IN ('player','supporter') AND assigneddeviceid IS NOT NULL")) {
            query.setLong(1, gameSessionId);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    if (deviceIds.contains(rows.getLong(2))) {
                        result.add(rows.getLong(1));
                    }
                }
            }
        }
        return result;
    }

    /** The owners of the given trackers, for telling only them that their jokers changed. */
    public Set<Long> owners(long gameSessionId, Collection<Long> deviceIds) throws Exception {
        return transaction(connection -> ownerUsers(connection, gameSessionId, deviceIds));
    }

    /** The trackers holding the given reservations. */
    static Set<Long> reservationDevices(Connection connection, long gameSessionId, Collection<Long> ids)
            throws SQLException {
        Set<Long> result = new java.util.LinkedHashSet<>();
        for (long id : ids) {
            try (var query = connection.prepareStatement("SELECT deviceid FROM tc_game_joker_reservations"
                    + " WHERE id = ? AND gamesessionid = ?")) {
                query.setLong(1, id);
                query.setLong(2, gameSessionId);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        result.add(rows.getLong(1));
                    }
                }
            }
        }
        return result;
    }

    /** Whether the participant already has an unbound speedhunt fake: requested (open) or armed. */
    static boolean speedhuntFakeHeld(Connection connection, long gameSessionId, long deviceId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT 1 FROM tc_game_joker_reservations"
                + " WHERE gamesessionid = ? AND deviceid = ? AND type = ? AND status = ?")) {
            query.setLong(1, gameSessionId);
            query.setLong(2, deviceId);
            query.setString(3, FAKE_SPEEDHUNT);
            query.setString(4, RESERVED);
            query.setMaxRows(1);
            try (var rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** Whether a timetable entry already has a fake: requested (open) or armed. */
    static boolean timetableFakeTaken(Connection connection, long gameSessionId, long entryId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT details FROM tc_game_joker_reservations"
                + " WHERE gamesessionid = ? AND status = ? AND type = ?")) {
            query.setLong(1, gameSessionId);
            query.setString(2, RESERVED);
            query.setString(3, FAKE_TIMETABLE);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    if (plan(rows.getString(1)).path("entryId").asLong(0) == entryId) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The owner's armed fake plans for their own status view. */
    static com.fasterxml.jackson.databind.node.ArrayNode ownFakes(Connection connection, long gameSessionId,
                                                                   long deviceId) throws SQLException {
        var result = JsonNodeFactory.instance.arrayNode();
        for (Reservation item : reservations(connection, gameSessionId, deviceId)) {
            if (item.armed()) {
                ObjectNode fake = result.addObject();
                fake.put("reservationId", item.id());
                fake.put("type", item.type());
                fake.setAll((ObjectNode) item.plan().deepCopy());
            }
        }
        return result;
    }

    /** Releases armed fakes of the given trackers (caught participants); bound fakes are already used. */
    private static void releaseArmed(Connection connection, long gameSessionId, Collection<Long> deviceIds,
                                     String detail) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT id,deviceid FROM tc_game_joker_reservations"
                + " WHERE gamesessionid = ? AND status = ? AND requestid IS NULL")) {
            query.setLong(1, gameSessionId);
            query.setString(2, RESERVED);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    if (deviceIds.contains(rows.getLong(2))) {
                        ids.add(rows.getLong(1));
                    }
                }
            }
        }
        for (long id : ids) {
            Armed fake = armed(connection, gameSessionId, id);
            if (fake != null) {
                endArmed(connection, fake, false, null, detail);
            }
        }
    }

    /**
     * Releases armed timetable fakes whose entry is no longer active or no longer reaches hunters after a new
     * timetable version was published.
     */
    static void timetableChanged(Connection connection, long gameSessionId) throws SQLException {
        Map<Long, String> active = new HashMap<>();
        try (var query = connection.prepareStatement("SELECT id,content FROM tc_game_timetable_entries"
                + " WHERE gamesessionid = ? AND active = ?")) {
            query.setLong(1, gameSessionId);
            query.setBoolean(2, true);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    active.put(rows.getLong(1), rows.getString(2));
                }
            }
        }
        List<Long> ids = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT id FROM tc_game_joker_reservations WHERE gamesessionid = ?"
                + " AND status = ? AND requestid IS NULL AND type = ?")) {
            query.setLong(1, gameSessionId);
            query.setString(2, RESERVED);
            query.setString(3, FAKE_TIMETABLE);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getLong(1));
                }
            }
        }
        for (long id : ids) {
            Armed fake = armed(connection, gameSessionId, id);
            if (fake == null) {
                continue;
            }
            long entry = fake.plan().path("entryId").asLong(0);
            if (!active.containsKey(entry)) {
                endArmed(connection, fake, false, null, "Its timetable ping was removed");
            } else if (!fakeEligible(active.get(entry))) {
                endArmed(connection, fake, false, null, "Its timetable ping no longer reaches the hunters");
            }
        }
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
        releaseArmed(connection, gameSessionId, deviceIds, "Its owner is out of the game");
    }
}
