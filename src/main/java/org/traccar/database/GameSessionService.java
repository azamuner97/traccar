/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.database;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Central storage for game sessions. Each stored part has its own owner and revision: configuration,
 * Traccar resource mapping with membership, Tool runtime, encrypted credentials, geofence plans, and the
 * published timetable. Writes require the revision they were based on, so no whole-document save can
 * overwrite newer state. Logs are append-only.
 */
@Singleton
public class GameSessionService {

    public static final int API_VERSION = 1;
    private static final int MAX_DOCUMENT_LENGTH = 8 * 1024 * 1024;
    private static final int MAX_LOG_BATCH = 500;
    private static final int MAX_LOG_MESSAGE = 16384;
    private static final int MAX_TIMETABLE_ENTRIES = 20000;
    private static final Set<String> MEMBER_ROLES = Set.of("player", "supporter", "hunter", "spectator");
    private static final Set<String> PROGRESS_STATUSES = Set.of("scheduled", "sent", "skipped", "failed");

    public record Summary(long id, String name, int schemaVersion, Long sessionDeviceId, long configRevision,
                          long resourcesRevision, long runtimeRevision, String createdAt, String updatedAt) { }
    public record Member(long userId, String role, Integer number, Long assignedDeviceId, String login) { }
    public record Session(long id, String name, int schemaVersion, Long sessionDeviceId,
                          long configRevision, JsonNode config, long resourcesRevision, JsonNode resources,
                          long runtimeRevision, JsonNode runtime, List<Member> members,
                          String createdAt, String updatedAt) { }
    public record Revision(long revision) { }
    public record Document(long revision, JsonNode document) { }
    public record TimetableEntry(long id, String scheduledAt, String participant, String content,
                                 String status, String statusAt) { }
    public record Timetable(long revision, JsonNode columns, JsonNode source, List<TimetableEntry> entries) { }
    public record EntryInput(String scheduledAt, String participant, String content) { }
    public record Published(long revision, boolean structureChanged, List<TimetableEntry> entries) { }
    public record Progress(long id, String status) { }
    public record LogInput(String category, String occurredAt, String message) { }
    public record LogEntry(long id, String category, String occurredAt, String message) { }
    public record LogPage(List<LogEntry> items, String nextCursor, boolean hasMore) { }

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final GameCredentialCipher cipher;

    @Inject
    public GameSessionService(DataSource dataSource, ObjectMapper objectMapper, GameCredentialCipher cipher) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.cipher = cipher;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws Exception;
    }

    private <T> T transaction(Work<T> work) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException error) {
                connection.rollback();
                if (error.getSQLState() != null && error.getSQLState().startsWith("23")) {
                    throw new WebApplicationException("Game session constraint violated: " + error.getMessage(), 409);
                }
                throw error;
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    private static String iso(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column, utc());
        return value == null ? null : value.toInstant().toString();
    }

    private static Instant instant(String value, String label) {
        try {
            Instant parsed = Instant.parse(value).truncatedTo(ChronoUnit.MILLIS);
            if (parsed.isBefore(Instant.parse("1970-01-01T00:00:01Z"))
                    || parsed.isAfter(Instant.parse("2038-01-19T03:14:07.999Z"))) {
                throw new BadRequestException(label + " is outside the supported range");
            }
            return parsed;
        } catch (DateTimeParseException | NullPointerException error) {
            throw new BadRequestException("Invalid " + label);
        }
    }

    public static String name(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || value.chars().anyMatch(character -> character < 32 || character == 127)
                || value.contains("/") || value.contains("\\")) {
            throw new BadRequestException("Invalid game session name");
        }
        return value;
    }

    private String json(JsonNode document, String label) throws Exception {
        if (document == null || !document.isObject()) {
            throw new BadRequestException(label + " must be a JSON object");
        }
        String serialized = objectMapper.writeValueAsString(document);
        if (serialized.length() > MAX_DOCUMENT_LENGTH) {
            throw new BadRequestException(label + " exceeds the size limit");
        }
        return serialized;
    }

    private JsonNode parse(String value) throws Exception {
        return value == null ? JsonNodeFactory.instance.objectNode() : objectMapper.readTree(value);
    }

    private static WebApplicationException conflict(String part, long current) {
        return new WebApplicationException(part + " revision conflict: current revision is " + current, 409);
    }

    /** Finds the game session registered for a hidden session device, or fails with 409. */
    public static long gameSessionIdForDevice(Connection connection, long deviceId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT id FROM tc_game_sessions WHERE sessiondeviceid = ?")) {
            query.setLong(1, deviceId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new WebApplicationException(
                            "Session device is not registered with a game session", 409);
                }
                return rows.getLong(1);
            }
        }
    }

    private Map<String, Long> lock(Connection connection, long id) throws SQLException {
        try (var query = connection.prepareStatement("SELECT configrevision,resourcesrevision,runtimerevision"
                + " FROM tc_game_sessions WHERE id = ? FOR UPDATE")) {
            query.setLong(1, id);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new NotFoundException("Game session not found");
                }
                Map<String, Long> revisions = new HashMap<>();
                revisions.put("config", rows.getLong(1));
                revisions.put("resources", rows.getLong(2));
                revisions.put("runtime", rows.getLong(3));
                return revisions;
            }
        }
    }

    private void touch(Connection connection, long id) throws SQLException {
        try (var query = connection.prepareStatement("UPDATE tc_game_sessions SET updatedat = ? WHERE id = ?")) {
            query.setTimestamp(1, now(), utc());
            query.setLong(2, id);
            query.executeUpdate();
        }
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static Summary summary(ResultSet rows) throws SQLException {
        return new Summary(rows.getLong("id"), rows.getString("name"), rows.getInt("schemaversion"),
                nullableLong(rows, "sessiondeviceid"), rows.getLong("configrevision"),
                rows.getLong("resourcesrevision"), rows.getLong("runtimerevision"),
                iso(rows, "createdat"), iso(rows, "updatedat"));
    }

    public List<Summary> list() throws Exception {
        return transaction(connection -> {
            List<Summary> result = new ArrayList<>();
            try (var query = connection.prepareStatement("SELECT id,name,schemaversion,sessiondeviceid,"
                    + "configrevision,resourcesrevision,runtimerevision,createdat,updatedat"
                    + " FROM tc_game_sessions ORDER BY name");
                 var rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(summary(rows));
                }
            }
            return result;
        });
    }

    private List<Member> members(Connection connection, long id) throws SQLException {
        List<Member> result = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT userid,role,number,assigneddeviceid,login"
                + " FROM tc_game_session_members WHERE gamesessionid = ? ORDER BY userid")) {
            query.setLong(1, id);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    int number = rows.getInt("number");
                    Integer parsedNumber = rows.wasNull() ? null : number;
                    result.add(new Member(rows.getLong("userid"), rows.getString("role"), parsedNumber,
                            nullableLong(rows, "assigneddeviceid"), rows.getString("login")));
                }
            }
        }
        return result;
    }

    private Session read(Connection connection, long id) throws Exception {
        try (var query = connection.prepareStatement("SELECT * FROM tc_game_sessions WHERE id = ?")) {
            query.setLong(1, id);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new NotFoundException("Game session not found");
                }
                return new Session(id, rows.getString("name"), rows.getInt("schemaversion"),
                        nullableLong(rows, "sessiondeviceid"),
                        rows.getLong("configrevision"), parse(rows.getString("config")),
                        rows.getLong("resourcesrevision"), parse(rows.getString("resources")),
                        rows.getLong("runtimerevision"), parse(rows.getString("runtime")),
                        members(connection, id), iso(rows, "createdat"), iso(rows, "updatedat"));
            }
        }
    }

    public Session get(long id) throws Exception {
        return transaction(connection -> read(connection, id));
    }

    public Session create(String name, int schemaVersion, JsonNode config) throws Exception {
        String validName = name(name);
        String serialized = json(config, "Configuration");
        if (schemaVersion < 1) {
            throw new BadRequestException("Invalid schema version");
        }
        return transaction(connection -> {
            try (var query = connection.prepareStatement("SELECT id FROM tc_game_sessions WHERE name = ?")) {
                query.setString(1, validName);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        throw new WebApplicationException("A game session with this name already exists", 409);
                    }
                }
            }
            long id;
            Timestamp created = now();
            try (var query = connection.prepareStatement("INSERT INTO tc_game_sessions (name,schemaversion,"
                    + "configrevision,config,resourcesrevision,resources,runtimerevision,runtime,createdat,updatedat)"
                    + " VALUES (?,?,1,?,1,'{}',1,'{}',?,?)", Statement.RETURN_GENERATED_KEYS)) {
                query.setString(1, validName);
                query.setInt(2, schemaVersion);
                query.setString(3, serialized);
                query.setTimestamp(4, created, utc());
                query.setTimestamp(5, created, utc());
                query.executeUpdate();
                try (var keys = query.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            return read(connection, id);
        });
    }

    public Revision rename(long id, long expectedRevision, String name) throws Exception {
        String validName = name(name);
        return transaction(connection -> {
            long current = lock(connection, id).get("config");
            String stored;
            try (var query = connection.prepareStatement("SELECT name FROM tc_game_sessions WHERE id = ?")) {
                query.setLong(1, id);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    stored = rows.getString(1);
                }
            }
            if (validName.equals(stored) && (expectedRevision == current || expectedRevision + 1 == current)) {
                return new Revision(current);
            }
            if (expectedRevision != current) {
                throw conflict("Configuration", current);
            }
            try (var query = connection.prepareStatement(
                    "SELECT id FROM tc_game_sessions WHERE name = ? AND id <> ?")) {
                query.setString(1, validName);
                query.setLong(2, id);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        throw new WebApplicationException("A game session with this name already exists", 409);
                    }
                }
            }
            try (var query = connection.prepareStatement(
                    "UPDATE tc_game_sessions SET name = ?, configrevision = ? WHERE id = ?")) {
                query.setString(1, validName);
                query.setLong(2, current + 1);
                query.setLong(3, id);
                query.executeUpdate();
            }
            touch(connection, id);
            return new Revision(current + 1);
        });
    }

    /** Applies a revision-checked document write; an identical retry of the last write is idempotent. */
    private Revision writeDocument(
            Connection connection, long id, String part, String column, long expectedRevision, String serialized)
            throws Exception {
        long current = lock(connection, id).get(part);
        if (expectedRevision != current) {
            if (expectedRevision + 1 == current) {
                try (var query = connection.prepareStatement(
                        "SELECT " + column + " FROM tc_game_sessions WHERE id = ?")) {
                    query.setLong(1, id);
                    try (var rows = query.executeQuery()) {
                        rows.next();
                        if (parse(rows.getString(1)).equals(objectMapper.readTree(serialized))) {
                            return new Revision(current);
                        }
                    }
                }
            }
            throw conflict(Character.toUpperCase(part.charAt(0)) + part.substring(1), current);
        }
        try (var query = connection.prepareStatement("UPDATE tc_game_sessions SET " + column + " = ?, "
                + part + "revision = ? WHERE id = ?")) {
            query.setString(1, serialized);
            query.setLong(2, current + 1);
            query.setLong(3, id);
            query.executeUpdate();
        }
        touch(connection, id);
        return new Revision(current + 1);
    }

    public Revision updateConfig(long id, long expectedRevision, Integer schemaVersion, JsonNode config)
            throws Exception {
        String serialized = json(config, "Configuration");
        return transaction(connection -> {
            Revision result = writeDocument(connection, id, "config", "config", expectedRevision, serialized);
            if (schemaVersion != null) {
                if (schemaVersion < 1) {
                    throw new BadRequestException("Invalid schema version");
                }
                try (var query = connection.prepareStatement(
                        "UPDATE tc_game_sessions SET schemaversion = ? WHERE id = ?")) {
                    query.setInt(1, schemaVersion);
                    query.setLong(2, id);
                    query.executeUpdate();
                }
            }
            return result;
        });
    }

    public Revision updateRuntime(long id, long expectedRevision, JsonNode runtime) throws Exception {
        String serialized = json(runtime, "Runtime");
        return transaction(connection -> writeDocument(
                connection, id, "runtime", "runtime", expectedRevision, serialized));
    }

    private static Member member(Member value) {
        if (value == null || value.userId() <= 0 || !MEMBER_ROLES.contains(value.role())
                || value.login() == null || value.login().isBlank() || value.login().length() > 128
                || value.number() != null && value.number() <= 0
                || value.assignedDeviceId() != null && value.assignedDeviceId() <= 0
                || "spectator".equals(value.role()) && (value.number() != null || value.assignedDeviceId() != null)) {
            throw new BadRequestException("Invalid game session member");
        }
        return value;
    }

    private void checkSessionDevice(Connection connection, long deviceId) throws Exception {
        try (var query = connection.prepareStatement("SELECT attributes FROM tc_devices WHERE id = ?")) {
            query.setLong(1, deviceId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new BadRequestException("Session device not found");
                }
                Map<String, Object> attributes = rows.getString(1) == null ? Map.of()
                        : objectMapper.readValue(rows.getString(1), new TypeReference<HashMap<String, Object>>() { });
                if (!Boolean.TRUE.equals(attributes.get("traccarToolGameState"))) {
                    throw new BadRequestException("Not a managed session device");
                }
            }
        }
    }

    public Revision updateResources(long id, long expectedRevision, Long sessionDeviceId, JsonNode resources,
                                    List<Member> members) throws Exception {
        String serialized = json(resources, "Resource mapping");
        List<Member> validMembers = members == null ? List.of() : members.stream().map(GameSessionService::member)
                .toList();
        Set<Long> users = new HashSet<>();
        for (Member value : validMembers) {
            if (!users.add(value.userId())) {
                throw new BadRequestException("Duplicate game session member");
            }
        }
        if (sessionDeviceId != null && sessionDeviceId <= 0) {
            throw new BadRequestException("Invalid session device");
        }
        return transaction(connection -> {
            long current = lock(connection, id).get("resources");
            Session stored = read(connection, id);
            boolean identical = stored.resources().equals(objectMapper.readTree(serialized))
                    && java.util.Objects.equals(stored.sessionDeviceId(), sessionDeviceId)
                    && new HashSet<>(stored.members()).equals(new HashSet<>(validMembers));
            if (expectedRevision != current) {
                if (expectedRevision + 1 == current && identical) {
                    return new Revision(current);
                }
                throw conflict("Resources", current);
            }
            if (sessionDeviceId != null) {
                checkSessionDevice(connection, sessionDeviceId);
            }
            try (var query = connection.prepareStatement("UPDATE tc_game_sessions SET resources = ?,"
                    + " resourcesrevision = ?, sessiondeviceid = ? WHERE id = ?")) {
                query.setString(1, serialized);
                query.setLong(2, current + 1);
                if (sessionDeviceId == null) {
                    query.setNull(3, Types.INTEGER);
                } else {
                    query.setLong(3, sessionDeviceId);
                }
                query.setLong(4, id);
                query.executeUpdate();
            }
            try (var query = connection.prepareStatement(
                    "DELETE FROM tc_game_session_members WHERE gamesessionid = ?")) {
                query.setLong(1, id);
                query.executeUpdate();
            }
            for (Member value : validMembers) {
                try (var query = connection.prepareStatement("INSERT INTO tc_game_session_members"
                        + " (gamesessionid,userid,role,number,assigneddeviceid,login) VALUES (?,?,?,?,?,?)")) {
                    query.setLong(1, id);
                    query.setLong(2, value.userId());
                    query.setString(3, value.role());
                    if (value.number() == null) {
                        query.setNull(4, Types.INTEGER);
                    } else {
                        query.setInt(4, value.number());
                    }
                    if (value.assignedDeviceId() == null) {
                        query.setNull(5, Types.INTEGER);
                    } else {
                        query.setLong(5, value.assignedDeviceId());
                    }
                    query.setString(6, value.login());
                    query.executeUpdate();
                }
            }
            touch(connection, id);
            return new Revision(current + 1);
        });
    }

    private long sideRevision(Connection connection, String table, long id, boolean lock) throws SQLException {
        try (var query = connection.prepareStatement("SELECT revision FROM " + table + " WHERE gamesessionid = ?"
                + (lock ? " FOR UPDATE" : ""))) {
            query.setLong(1, id);
            try (var rows = query.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    public Document getCredentials(long id) throws Exception {
        return transaction(connection -> {
            lock(connection, id);
            try (var query = connection.prepareStatement(
                    "SELECT revision,ciphertext FROM tc_game_session_credentials WHERE gamesessionid = ?")) {
                query.setLong(1, id);
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) {
                        return new Document(0, JsonNodeFactory.instance.objectNode());
                    }
                    return new Document(rows.getLong(1), objectMapper.readTree(cipher.decrypt(rows.getString(2))));
                }
            }
        });
    }

    public Revision updateCredentials(long id, long expectedRevision, JsonNode credentials) throws Exception {
        String serialized = json(credentials, "Credentials");
        return transaction(connection -> {
            lock(connection, id);
            long current = sideRevision(connection, "tc_game_session_credentials", id, true);
            if (expectedRevision != current) {
                if (expectedRevision + 1 == current) {
                    try (var query = connection.prepareStatement(
                            "SELECT ciphertext FROM tc_game_session_credentials WHERE gamesessionid = ?")) {
                        query.setLong(1, id);
                        try (var rows = query.executeQuery()) {
                            if (rows.next() && objectMapper.readTree(cipher.decrypt(rows.getString(1)))
                                    .equals(objectMapper.readTree(serialized))) {
                                return new Revision(current);
                            }
                        }
                    }
                }
                throw conflict("Credentials", current);
            }
            String encrypted = cipher.encrypt(serialized);
            String sql = current == 0
                    ? "INSERT INTO tc_game_session_credentials (ciphertext,revision,gamesessionid) VALUES (?,?,?)"
                    : "UPDATE tc_game_session_credentials SET ciphertext = ?, revision = ? WHERE gamesessionid = ?";
            try (var query = connection.prepareStatement(sql)) {
                query.setString(1, encrypted);
                query.setLong(2, current + 1);
                query.setLong(3, id);
                query.executeUpdate();
            }
            touch(connection, id);
            return new Revision(current + 1);
        });
    }

    public Document getGeofencePlans(long id) throws Exception {
        return transaction(connection -> {
            lock(connection, id);
            try (var query = connection.prepareStatement(
                    "SELECT revision,data FROM tc_game_geofence_plans WHERE gamesessionid = ?")) {
                query.setLong(1, id);
                try (var rows = query.executeQuery()) {
                    return rows.next()
                            ? new Document(rows.getLong(1), parse(rows.getString(2)))
                            : new Document(0, JsonNodeFactory.instance.objectNode());
                }
            }
        });
    }

    public Revision updateGeofencePlans(long id, long expectedRevision, JsonNode data) throws Exception {
        String serialized = json(data, "Geofence plans");
        return transaction(connection -> {
            lock(connection, id);
            long current = sideRevision(connection, "tc_game_geofence_plans", id, true);
            if (expectedRevision != current) {
                if (expectedRevision + 1 == current) {
                    try (var query = connection.prepareStatement(
                            "SELECT data FROM tc_game_geofence_plans WHERE gamesessionid = ?")) {
                        query.setLong(1, id);
                        try (var rows = query.executeQuery()) {
                            if (rows.next() && parse(rows.getString(1)).equals(objectMapper.readTree(serialized))) {
                                return new Revision(current);
                            }
                        }
                    }
                }
                throw conflict("Geofence plans", current);
            }
            String sql = current == 0
                    ? "INSERT INTO tc_game_geofence_plans (data,revision,gamesessionid) VALUES (?,?,?)"
                    : "UPDATE tc_game_geofence_plans SET data = ?, revision = ? WHERE gamesessionid = ?";
            try (var query = connection.prepareStatement(sql)) {
                query.setString(1, serialized);
                query.setLong(2, current + 1);
                query.setLong(3, id);
                query.executeUpdate();
            }
            touch(connection, id);
            return new Revision(current + 1);
        });
    }

    private List<TimetableEntry> activeEntries(Connection connection, long id) throws SQLException {
        List<TimetableEntry> result = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT id,scheduledat,participant,content,status,statusat"
                + " FROM tc_game_timetable_entries WHERE gamesessionid = ? AND active = ?"
                + " ORDER BY scheduledat,participant")) {
            query.setLong(1, id);
            query.setBoolean(2, true);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(new TimetableEntry(rows.getLong("id"), iso(rows, "scheduledat"),
                            rows.getString("participant"), rows.getString("content"), rows.getString("status"),
                            iso(rows, "statusat")));
                }
            }
        }
        return result;
    }

    private Timetable readTimetable(Connection connection, long id) throws Exception {
        try (var query = connection.prepareStatement(
                "SELECT revision,headers,source FROM tc_game_timetables WHERE gamesessionid = ?")) {
            query.setLong(1, id);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    return new Timetable(0, JsonNodeFactory.instance.arrayNode(),
                            JsonNodeFactory.instance.objectNode(), List.of());
                }
                return new Timetable(rows.getLong(1), objectMapper.readTree(rows.getString(2)),
                        parse(rows.getString(3)), activeEntries(connection, id));
            }
        }
    }

    public Timetable getTimetable(long id) throws Exception {
        return transaction(connection -> {
            lock(connection, id);
            return readTimetable(connection, id);
        });
    }

    private record EntryKey(Instant scheduledAt, String participant) { }

    /**
     * Publishes a parsed timetable. Entries are identified by scheduled UTC time and participant: matching entries
     * keep their ID and progress, changed content is updated in place, new entries are added, and entries that
     * disappeared are deactivated.
     */
    public Published publishTimetable(long id, long expectedRevision, JsonNode columns, JsonNode source,
                                      List<EntryInput> entries) throws Exception {
        if (columns == null || !columns.isArray() || columns.size() == 0) {
            throw new BadRequestException("Timetable columns must be a non-empty JSON array");
        }
        String serializedSource = json(source == null ? JsonNodeFactory.instance.objectNode() : source,
                "Timetable source");
        String serializedColumns = objectMapper.writeValueAsString(columns);
        if (entries == null || entries.size() > MAX_TIMETABLE_ENTRIES) {
            throw new BadRequestException("Invalid timetable entry list");
        }
        Map<EntryKey, String> desired = new LinkedHashMap<>();
        for (EntryInput entry : entries) {
            if (entry == null || entry.participant() == null || entry.participant().isBlank()
                    || entry.participant().length() > 128 || entry.content() == null
                    || entry.content().length() > 512) {
                throw new BadRequestException("Invalid timetable entry");
            }
            EntryKey key = new EntryKey(instant(entry.scheduledAt(), "timetable entry time"), entry.participant());
            if (desired.put(key, entry.content()) != null) {
                throw new BadRequestException("Duplicate timetable entry");
            }
        }
        return transaction(connection -> {
            lock(connection, id);
            Timetable before = readTimetable(connection, id);
            long current = sideRevision(connection, "tc_game_timetables", id, true);
            Set<Instant> previousTimes = new HashSet<>();
            Map<EntryKey, TimetableEntry> previous = new HashMap<>();
            for (TimetableEntry entry : before.entries()) {
                Instant time = Instant.parse(entry.scheduledAt());
                previousTimes.add(time);
                previous.put(new EntryKey(time, entry.participant()), entry);
            }
            Set<Instant> desiredTimes = new HashSet<>();
            desired.keySet().forEach(key -> desiredTimes.add(key.scheduledAt()));
            boolean structureChanged = !before.columns().equals(columns) || !previousTimes.equals(desiredTimes);
            boolean unchanged = !structureChanged && previous.size() == desired.size()
                    && desired.entrySet().stream().allMatch(item -> previous.containsKey(item.getKey())
                            && previous.get(item.getKey()).content().equals(item.getValue()))
                    && before.source().equals(objectMapper.readTree(serializedSource));
            if (expectedRevision != current) {
                if (expectedRevision + 1 == current && unchanged) {
                    return new Published(current, false, before.entries());
                }
                throw conflict("Timetable", current);
            }
            long revision = current + 1;
            Timestamp updated = now();
            String header = current == 0
                    ? "INSERT INTO tc_game_timetables (headers,source,updatedat,revision,gamesessionid)"
                            + " VALUES (?,?,?,?,?)"
                    : "UPDATE tc_game_timetables SET headers = ?, source = ?, updatedat = ?, revision = ?"
                            + " WHERE gamesessionid = ?";
            try (var query = connection.prepareStatement(header)) {
                query.setString(1, serializedColumns);
                query.setString(2, serializedSource);
                query.setTimestamp(3, updated, utc());
                query.setLong(4, revision);
                query.setLong(5, id);
                query.executeUpdate();
            }
            try (var query = connection.prepareStatement("UPDATE tc_game_timetable_entries SET active = ?,"
                    + " revision = ? WHERE gamesessionid = ? AND active = ?")) {
                query.setBoolean(1, false);
                query.setLong(2, revision);
                query.setLong(3, id);
                query.setBoolean(4, true);
                query.executeUpdate();
            }
            for (var item : desired.entrySet()) {
                Timestamp scheduled = Timestamp.from(item.getKey().scheduledAt());
                int updatedRows;
                try (var query = connection.prepareStatement("UPDATE tc_game_timetable_entries"
                        + " SET content = ?, active = ?, revision = ?"
                        + " WHERE gamesessionid = ? AND scheduledat = ? AND participant = ?")) {
                    query.setString(1, item.getValue());
                    query.setBoolean(2, true);
                    query.setLong(3, revision);
                    query.setLong(4, id);
                    query.setTimestamp(5, scheduled, utc());
                    query.setString(6, item.getKey().participant());
                    updatedRows = query.executeUpdate();
                }
                if (updatedRows == 0) {
                    try (var query = connection.prepareStatement("INSERT INTO tc_game_timetable_entries"
                            + " (gamesessionid,scheduledat,participant,content,active,revision,status)"
                            + " VALUES (?,?,?,?,?,?,?)")) {
                        query.setLong(1, id);
                        query.setTimestamp(2, scheduled, utc());
                        query.setString(3, item.getKey().participant());
                        query.setString(4, item.getValue());
                        query.setBoolean(5, true);
                        query.setLong(6, revision);
                        query.setString(7, "scheduled");
                        query.executeUpdate();
                    }
                }
            }
            touch(connection, id);
            return new Published(revision, structureChanged, activeEntries(connection, id));
        });
    }

    public void updateTimetableProgress(long id, List<Progress> progress) throws Exception {
        if (progress == null || progress.size() > MAX_TIMETABLE_ENTRIES) {
            throw new BadRequestException("Invalid timetable progress");
        }
        for (Progress item : progress) {
            if (item == null || item.id() <= 0 || !PROGRESS_STATUSES.contains(item.status())) {
                throw new BadRequestException("Invalid timetable progress entry");
            }
        }
        transaction(connection -> {
            lock(connection, id);
            Timestamp at = now();
            for (Progress item : progress) {
                try (var query = connection.prepareStatement("UPDATE tc_game_timetable_entries SET status = ?,"
                        + " statusat = ? WHERE id = ? AND gamesessionid = ?")) {
                    query.setString(1, item.status());
                    query.setTimestamp(2, at, utc());
                    query.setLong(3, item.id());
                    query.setLong(4, id);
                    if (query.executeUpdate() == 0) {
                        throw new BadRequestException("Unknown timetable entry " + item.id());
                    }
                }
            }
            return null;
        });
    }

    public void appendLogs(long id, List<LogInput> entries) throws Exception {
        if (entries == null || entries.isEmpty() || entries.size() > MAX_LOG_BATCH) {
            throw new BadRequestException("Invalid log batch");
        }
        List<Instant> times = new ArrayList<>();
        for (LogInput entry : entries) {
            if (entry == null || entry.category() == null || !entry.category().matches("[A-Za-z0-9_-]{1,32}")
                    || entry.message() == null || entry.message().length() > MAX_LOG_MESSAGE) {
                throw new BadRequestException("Invalid log entry");
            }
            times.add(instant(entry.occurredAt(), "log time"));
        }
        transaction(connection -> {
            lock(connection, id);
            try (var query = connection.prepareStatement("INSERT INTO tc_game_session_logs"
                    + " (gamesessionid,category,occurredat,message) VALUES (?,?,?,?)")) {
                for (int index = 0; index < entries.size(); index++) {
                    query.setLong(1, id);
                    query.setString(2, entries.get(index).category());
                    query.setTimestamp(3, Timestamp.from(times.get(index)), utc());
                    query.setString(4, entries.get(index).message());
                    query.addBatch();
                }
                query.executeBatch();
            }
            return null;
        });
    }

    public LogPage listLogs(long id, String cursor, int requestedLimit) throws Exception {
        int limit = requestedLimit == 0 ? 500 : requestedLimit;
        if (limit < 1 || limit > 1000 || cursor != null && !cursor.matches("[0-9]{1,19}")) {
            throw new BadRequestException("Invalid log pagination");
        }
        long after = cursor == null ? 0 : Long.parseLong(cursor);
        return transaction(connection -> {
            lock(connection, id);
            List<LogEntry> items = new ArrayList<>();
            boolean more = false;
            String next = Long.toString(after);
            try (PreparedStatement query = connection.prepareStatement("SELECT id,category,occurredat,message"
                    + " FROM tc_game_session_logs WHERE gamesessionid = ? AND id > ? ORDER BY id")) {
                query.setLong(1, id);
                query.setLong(2, after);
                query.setMaxRows(limit + 1);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        if (items.size() == limit) {
                            more = true;
                            break;
                        }
                        items.add(new LogEntry(rows.getLong("id"), rows.getString("category"),
                                iso(rows, "occurredat"), rows.getString("message")));
                        next = Long.toString(rows.getLong("id"));
                    }
                }
            }
            return new LogPage(items, next, more);
        });
    }

    /** Deletes saved session data. Managed Traccar resources are cleaned up separately by Tool. */
    public void delete(long id) throws Exception {
        transaction(connection -> {
            lock(connection, id);
            try (var query = connection.prepareStatement("DELETE FROM tc_game_sessions WHERE id = ?")) {
                query.setLong(1, id);
                query.executeUpdate();
            }
            return null;
        });
    }
}
