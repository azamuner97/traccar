/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.database;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import org.traccar.helper.ReflectionCache;
import org.traccar.helper.model.DrawingUtil;
import org.traccar.model.Device;
import org.traccar.model.User;
import org.traccar.storage.query.Columns;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

/** Transactional notification journal. Device row locks serialize resets and inserts. */
@Singleton
public class GameNotificationService {

    public static final String GENERATION_ATTRIBUTE = "traccarToolNotificationGeneration";
    public static final String RESET_ATTRIBUTE = "traccarToolNotificationResetAt";
    public static final String ASSIGNED_DEVICE_ATTRIBUTE = DrawingUtil.ATTRIBUTE_ASSIGNED_DEVICE_ID;
    private static final Set<String> ROLES = Set.of("player", "supporter", "hunter", "spectator");
    private static final Set<String> SESSION_TYPES = Set.of("speedhunt", "playerCaught", "notificationTest");

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Alert(String id, String type, String audience, String occurredAt, String timeZone, String message,
                        Long recipientUserId, Long sourceDeviceId) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(String id, String type, String audience, String occurredAt, String timeZone,
                        String message, Long recipientUserId, Long sourceDeviceId,
                        long gameStateDeviceId, String generation, String createdAt) { }
    public record State(int version, long sessionDeviceId, String generation, String resetAt) { }
    public record Page(int version, long sessionDeviceId, String generation, String resetAt,
                       List<Entry> items, String nextCursor, boolean hasMore) { }
    public record Creation(int version, String generation, Entry item, boolean created) { }

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    @Inject
    public GameNotificationService(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    private Map<String, Object> attributes(String json) throws Exception {
        Map<String, Object> result = json == null ? null : objectMapper.readValue(
                json, new TypeReference<HashMap<String, Object>>() { });
        return result == null ? new HashMap<>() : result;
    }

    private Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    public static boolean canReceive(User user, long deviceId) {
        if (user == null || user.getAdministrator() || !ROLES.contains(
                String.valueOf(user.getAttributes().get("traccarToolUserRole")))) {
            return false;
        }
        Object value = user.getAttributes().get("traccarToolSessionDeviceId");
        return value != null && value.toString().matches("[1-9][0-9]*")
                && value.toString().equals(Long.toString(deviceId));
    }

    public static String generation(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new BadRequestException("Invalid notification generation");
        }
        return value;
    }

    public static Alert validate(Alert alert) {
        if (alert == null || alert.id() == null || !alert.id().matches("[A-Za-z0-9_-]{1,64}")
                || alert.type() == null
                || alert.message() == null || alert.message().isBlank() || alert.message().length() > 8192
                || alert.timeZone() == null || alert.timeZone().length() > 64
                || !ZoneId.getAvailableZoneIds().contains(alert.timeZone())
                || alert.timeZone().startsWith("SystemV/")
                || alert.occurredAt() == null
                || !alert.occurredAt().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z")) {
            throw new BadRequestException("Invalid game notification");
        }
        boolean session = "session".equals(alert.audience()) && SESSION_TYPES.contains(alert.type())
                && alert.recipientUserId() == null && alert.sourceDeviceId() == null;
        boolean targeted = "user".equals(alert.audience()) && "geofenceExit".equals(alert.type())
                && alert.recipientUserId() != null && alert.recipientUserId() > 0
                && alert.sourceDeviceId() != null && alert.sourceDeviceId() > 0;
        if (!session && !targeted) {
            throw new BadRequestException("Invalid notification audience or targets");
        }
        try {
            ZoneId.of(alert.timeZone());
            Instant occurred = Instant.parse(alert.occurredAt()).truncatedTo(ChronoUnit.MILLIS);
            if (occurred.isBefore(Instant.parse("1970-01-01T00:00:01Z"))
                    || occurred.isAfter(Instant.parse("2038-01-19T03:14:07.999Z"))
                    || occurred.isAfter(Instant.now().plusSeconds(60))) {
                throw new IllegalArgumentException("Notification time is outside the supported database range");
            }
            return new Alert(alert.id(), alert.type(), alert.audience(), occurred.toString(),
                    alert.timeZone(), alert.message(), alert.recipientUserId(), alert.sourceDeviceId());
        } catch (IllegalArgumentException | java.time.DateTimeException error) {
            throw new BadRequestException("Invalid notification timestamp or timezone", error);
        }
    }

    private Map<String, Object> lockDevice(Connection connection, long deviceId) throws Exception {
        try (var query = connection.prepareStatement("SELECT attributes FROM tc_devices WHERE id = ? FOR UPDATE")) {
            query.setLong(1, deviceId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new NotFoundException("Session device not found");
                }
                Map<String, Object> attributes = attributes(rows.getString(1));
                if (!Boolean.TRUE.equals(attributes.get("traccarToolGameState"))) {
                    throw new BadRequestException("Not a managed session device");
                }
                return attributes;
            }
        }
    }

    private State state(long deviceId, Map<String, Object> attributes) {
        return new State(2, deviceId, (String) attributes.get(GENERATION_ATTRIBUTE),
                (String) attributes.get(RESET_ATTRIBUTE));
    }

    private void saveState(Connection connection, long deviceId, Map<String, Object> attributes) throws Exception {
        String json = objectMapper.writeValueAsString(attributes);
        if (json.length() > 4000) {
            throw new BadRequestException("Device attributes exceed database limit");
        }
        try (var query = connection.prepareStatement("UPDATE tc_devices SET attributes = ? WHERE id = ?")) {
            query.setString(1, json);
            query.setLong(2, deviceId);
            query.executeUpdate();
        }
    }

    /** Ordinary device saves must never overwrite journal-owned metadata, even with stale attributes. */
    public void updateDevice(Device device) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Map<String, Object> before;
                try (var query = connection.prepareStatement(
                        "SELECT attributes FROM tc_devices WHERE id = ? FOR UPDATE")) {
                    query.setLong(1, device.getId());
                    try (var rows = query.executeQuery()) {
                        if (!rows.next()) {
                            throw new NotFoundException();
                        }
                        before = attributes(rows.getString(1));
                    }
                }
                var after = device.getAttributes();
                for (String key : List.of(GENERATION_ATTRIBUTE, RESET_ATTRIBUTE)) {
                    after.remove(key);
                    if (before.containsKey(key)) {
                        after.put(key, before.get(key));
                    }
                }
                if (Boolean.TRUE.equals(before.get("traccarToolGameState"))) {
                    after.put("traccarToolGameState", true);
                }
                if (objectMapper.writeValueAsString(after).length() > 4000) {
                    throw new BadRequestException("Device attributes exceed database limit");
                }
                // Update all ordinary fields and protected metadata atomically.
                // A failed name/unique-ID/foreign-key validation cannot partially
                // commit attributes while the rest of a Device PUT fails.
                var columns = new Columns.Exclude("id").getColumns(Device.class, "get");
                String assignments = String.join(",", columns.stream().map(column -> column + " = ?").toList());
                try (var query = connection.prepareStatement(
                        "UPDATE tc_devices SET " + assignments + " WHERE id = ?")) {
                    for (int index = 0; index < columns.size(); index++) {
                        String column = columns.get(index);
                        Object value;
                        try {
                            value = ReflectionCache.getProperties(Device.class, "get").get(column)
                                    .handle().invokeExact((Object) device);
                        } catch (Throwable error) {
                            throw new SQLException("Device property binding failed", error);
                        }
                        if (value instanceof Map<?, ?>) {
                            query.setString(index + 1, objectMapper.writeValueAsString(value));
                        } else if (value instanceof java.util.Date date) {
                            query.setTimestamp(index + 1, new Timestamp(date.getTime()));
                        } else if (value instanceof Long number && number == 0 && column.endsWith("Id")) {
                            query.setNull(index + 1, Types.BIGINT);
                        } else {
                            query.setObject(index + 1, value);
                        }
                    }
                    query.setLong(columns.size() + 1, device.getId());
                    query.executeUpdate();
                }
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public State initialize(long deviceId) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var attributes = lockDevice(connection, deviceId);
                if (attributes.get(GENERATION_ATTRIBUTE) == null) {
                    attributes.put(GENERATION_ATTRIBUTE, UUID.randomUUID().toString());
                    attributes.put(RESET_ATTRIBUTE, Instant.now().toString());
                    saveState(connection, deviceId, attributes);
                }
                State result = state(deviceId, attributes);
                connection.commit();
                return result;
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public State reset(long deviceId, String expected, String replacement) throws Exception {
        generation(expected);
        generation(replacement);
        if (expected.equals(replacement)) {
            throw new BadRequestException("Reset requires a new generation");
        }
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var attributes = lockDevice(connection, deviceId);
                if (!replacement.equals(attributes.get(GENERATION_ATTRIBUTE))) {
                    if (!expected.equals(attributes.get(GENERATION_ATTRIBUTE))) {
                        throw new WebApplicationException("Notification generation changed", 409);
                    }
                    try (var query = connection.prepareStatement(
                            "DELETE FROM tc_game_notifications WHERE sessiondeviceid = ?")) {
                        query.setLong(1, deviceId);
                        query.executeUpdate();
                    }
                    attributes.put(GENERATION_ATTRIBUTE, replacement);
                    attributes.put(RESET_ATTRIBUTE, Instant.now().toString());
                    saveState(connection, deviceId, attributes);
                }
                State result = state(deviceId, attributes);
                connection.commit();
                return result;
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private Entry entry(ResultSet rows) throws Exception {
        Calendar calendar = utc();
        long recipient = rows.getLong("recipientuserid");
        Long recipientUserId = rows.wasNull() ? null : recipient;
        long source = rows.getLong("sourcedeviceid");
        Long sourceDeviceId = rows.wasNull() ? null : source;
        return new Entry(rows.getString("alertid"), rows.getString("type"), rows.getString("audience"),
                rows.getTimestamp("occurredat", calendar).toInstant().toString(), rows.getString("timezone"),
                rows.getString("message"), recipientUserId, sourceDeviceId,
                rows.getLong("sessiondeviceid"), rows.getString("generation"),
                rows.getTimestamp("createdat", calendar).toInstant().toString());
    }

    public Creation create(long deviceId, String expected, Alert input) throws Exception {
        generation(expected);
        Alert alert = validate(input);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var attributes = lockDevice(connection, deviceId);
                if (!expected.equals(attributes.get(GENERATION_ATTRIBUTE))) {
                    throw new WebApplicationException("Notification generation changed", 409);
                }
                try (var query = connection.prepareStatement(
                        "SELECT * FROM tc_game_notifications WHERE sessiondeviceid = ? AND alertid = ?")) {
                    query.setLong(1, deviceId);
                    query.setString(2, alert.id());
                    try (var rows = query.executeQuery()) {
                        if (rows.next()) {
                            Entry existing = entry(rows);
                            Alert stored = new Alert(existing.id(), existing.type(), existing.audience(),
                                    existing.occurredAt(), existing.timeZone(), existing.message(),
                                    existing.recipientUserId(), existing.sourceDeviceId());
                            if (!stored.equals(alert)) {
                                throw new WebApplicationException("Conflicting notification ID", 409);
                            }
                            connection.commit();
                            return new Creation(2, expected, existing, false);
                        }
                    }
                }
                Instant created = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                try (var query = connection.prepareStatement("INSERT INTO tc_game_notifications "
                        + "(alertid,sessiondeviceid,generation,type,audience,occurredat,timezone,message,"
                        + "recipientuserid,sourcedeviceid,createdat) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    query.setString(1, alert.id());
                    query.setLong(2, deviceId);
                    query.setString(3, expected);
                    query.setString(4, alert.type());
                    query.setString(5, alert.audience());
                    query.setTimestamp(6, Timestamp.from(Instant.parse(alert.occurredAt())), utc());
                    query.setString(7, alert.timeZone());
                    query.setString(8, alert.message());
                    if (alert.recipientUserId() == null) {
                        query.setNull(9, Types.INTEGER);
                    } else {
                        query.setLong(9, alert.recipientUserId());
                    }
                    if (alert.sourceDeviceId() == null) {
                        query.setNull(10, Types.INTEGER);
                    } else {
                        query.setLong(10, alert.sourceDeviceId());
                    }
                    query.setTimestamp(11, Timestamp.from(created), utc());
                    query.executeUpdate();
                }
                connection.commit();
                return new Creation(2, expected, new Entry(alert.id(), alert.type(), alert.audience(),
                        alert.occurredAt(), alert.timeZone(), alert.message(),
                        alert.recipientUserId(), alert.sourceDeviceId(),
                        deviceId, expected, created.toString()), true);
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public Page list(long deviceId, String expected, String cursor, int requestedLimit, Long recipientUserId)
            throws Exception {
        if (expected != null) {
            generation(expected);
        }
        int limit = requestedLimit == 0 ? 100 : requestedLimit;
        if (limit < 1 || limit > 500 || cursor != null && !cursor.matches("[0-9]{1,19}")) {
            throw new BadRequestException("Invalid notification pagination");
        }
        long after;
        try {
            after = cursor == null ? 0 : Long.parseLong(cursor);
        } catch (NumberFormatException error) {
            throw new BadRequestException("Invalid notification cursor");
        }
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Same lock as writes: metadata and rows always describe one generation.
                var attributes = lockDevice(connection, deviceId);
                State state = state(deviceId, attributes);
                if (expected != null && !expected.equals(state.generation())) {
                    throw new WebApplicationException("Notification generation changed", 409);
                }
                List<Entry> items = new ArrayList<>();
                boolean more = false;
                String next = Long.toString(after);
                String recipientFilter = recipientUserId == null
                        ? ""
                        : " AND (audience = 'session' OR recipientuserid = ?)";
                try (var query = connection.prepareStatement("SELECT * FROM tc_game_notifications "
                        + "WHERE sessiondeviceid = ? AND generation = ? AND id > ?"
                        + recipientFilter + " ORDER BY id ASC")) {
                    query.setLong(1, deviceId);
                    query.setString(2, state.generation());
                    query.setLong(3, after);
                    if (recipientUserId != null) {
                        query.setLong(4, recipientUserId);
                    }
                    query.setMaxRows(limit + 1);
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) {
                            if (items.size() == limit) {
                                more = true;
                                break;
                            }
                            items.add(entry(rows));
                            next = Long.toString(rows.getLong("id"));
                        }
                    }
                }
                connection.commit();
                return new Page(2, deviceId, state.generation(), state.resetAt(), items, next, more);
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }
}
