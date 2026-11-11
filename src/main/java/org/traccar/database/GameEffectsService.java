/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.database;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import org.traccar.model.Position;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Fenced, idempotent Tool deliveries. One delivery is one game operation: the positions written to the
 * session's mirrors and game-state device, the related runtime change, archived game notifications, queued
 * pushes and log entries, committed in one transaction under a durable delivery ID. Repeating a delivery ID
 * returns the stored outcome without applying anything again.
 */
@Singleton
public class GameEffectsService {

    public static final String PROTOCOL = "osmand";
    private static final int MAX_POSITIONS = 200;
    private static final int MAX_ALERTS = 100;
    private static final int MAX_PUSHES = 500;
    private static final int MAX_ATTRIBUTES = 64;

    public record PositionInput(Long deviceId, String uniqueId, Double latitude, Double longitude, String fixTime,
                                Map<String, Object> attributes,
                                @JsonInclude(JsonInclude.Include.NON_NULL) Long fakeReservationId,
                                @JsonInclude(JsonInclude.Include.NON_NULL) Double fallbackLatitude,
                                @JsonInclude(JsonInclude.Include.NON_NULL) Double fallbackLongitude) {

        public PositionInput(Long deviceId, String uniqueId, Double latitude, Double longitude, String fixTime,
                             Map<String, Object> attributes) {
            this(deviceId, uniqueId, latitude, longitude, fixTime, attributes, null, null, null);
        }
    }
    public record RuntimeInput(Long expectedRevision, JsonNode document) { }
    public record NotificationInput(Long sessionDeviceId, String generation,
                                    List<GameNotificationService.Alert> alerts) { }
    public record PushInput(Long userId, String subject, String message, String notificator, String alertId) { }
    public record Delivery(String deliveryId, List<PositionInput> positions, RuntimeInput runtime,
                           NotificationInput notifications, List<PushInput> pushes,
                           List<GameSessionService.LogInput> logs,
                           List<GameRequestService.Completion> requests, JsonNode status,
                           List<GameJokerService.Use> jokers, List<Long> caughtParticipants,
                           List<GameJokerService.FakeSettlement> fakes,
                           @JsonInclude(JsonInclude.Include.NON_NULL) GameJokerService.RunningPing speedhuntPing,
                           @JsonInclude(JsonInclude.Include.NON_NULL) Boolean fakePositionsGuarded) {
        public Delivery(String deliveryId, List<PositionInput> positions, RuntimeInput runtime,
                        NotificationInput notifications, List<PushInput> pushes,
                        List<GameSessionService.LogInput> logs, List<GameRequestService.Completion> requests,
                        JsonNode status, List<GameJokerService.Use> jokers, List<Long> caughtParticipants,
                        List<GameJokerService.FakeSettlement> fakes, GameJokerService.RunningPing speedhuntPing) {
            this(deliveryId, positions, runtime, notifications, pushes, logs, requests, status, jokers,
                    caughtParticipants, fakes, speedhuntPing, null);
        }

        public Delivery(String deliveryId, List<PositionInput> positions, RuntimeInput runtime,
                        NotificationInput notifications, List<PushInput> pushes,
                        List<GameSessionService.LogInput> logs, List<GameRequestService.Completion> requests,
                        JsonNode status, List<GameJokerService.Use> jokers, List<Long> caughtParticipants,
                        List<GameJokerService.FakeSettlement> fakes) {
            this(deliveryId, positions, runtime, notifications, pushes, logs, requests, status, jokers,
                    caughtParticipants, fakes, null);
        }
        public Delivery(String deliveryId, List<PositionInput> positions, RuntimeInput runtime,
                        NotificationInput notifications, List<PushInput> pushes,
                        List<GameSessionService.LogInput> logs) {
            this(deliveryId, positions, runtime, notifications, pushes, logs, null, null, null, null, null);
        }

        public Delivery(String deliveryId, List<PositionInput> positions, RuntimeInput runtime,
                        NotificationInput notifications, List<PushInput> pushes,
                        List<GameSessionService.LogInput> logs, List<GameRequestService.Completion> requests,
                        JsonNode status) {
            this(deliveryId, positions, runtime, notifications, pushes, logs, requests, status, null, null, null);
        }

        public Delivery(String deliveryId, List<PositionInput> positions, RuntimeInput runtime,
                        NotificationInput notifications, List<PushInput> pushes,
                        List<GameSessionService.LogInput> logs, List<GameRequestService.Completion> requests,
                        JsonNode status, List<GameJokerService.Use> jokers, List<Long> caughtParticipants) {
            this(deliveryId, positions, runtime, notifications, pushes, logs, requests, status, jokers,
                    caughtParticipants, null);
        }
    }

    public record PositionOutcome(long deviceId, long positionId, boolean latest, String fixTime) { }
    public record AlertOutcome(String alertId, boolean created) { }
    public record PushOutcome(Long id, Long userId, String status, String detail) { }
    public record RequestOutcome(long id, String status) { }
    public record Outcome(String deliveryId, boolean replayed, long epoch, List<PositionOutcome> positions,
                          Long runtimeRevision, String generation, List<AlertOutcome> alerts,
                          List<PushOutcome> pushes, List<RequestOutcome> requests, Long statusRevision,
                          List<GameJokerService.UseOutcome> jokers, List<GameJokerService.FakeOutcome> fakes) { }

    /** The committed outcome plus what the caller publishes after commit. */
    /**
     * The committed outcome plus what the caller publishes after commit. {@code jokerUsers} are the owners whose
     * jokers changed: only they (and administrators) are told, so nobody else learns when a joker took effect.
     */
    public record Applied(Outcome outcome, List<Position> latest, Set<Long> devices, Long notificationDevice,
                          Set<Long> notifiedRecipients, boolean sessionNotified, List<Long> queuedPushes,
                          boolean speedhuntHistoryChanged, boolean requestsChanged, boolean statusChanged,
                          Set<Long> jokerUsers) { }

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final GameSessionService sessions;
    private final GameNotificationService notifications;
    private final GameRequestService requests;

    @Inject
    public GameEffectsService(DataSource dataSource, ObjectMapper objectMapper, GameSessionService sessions,
                              GameNotificationService notifications, GameRequestService requests) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.sessions = sessions;
        this.notifications = notifications;
        this.requests = requests;
    }

    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    /** Checks the shape of a delivery and validates its alerts; returns the validated alerts. */
    public List<GameNotificationService.Alert> validate(Delivery delivery) {
        if (delivery == null || delivery.deliveryId() == null
                || !delivery.deliveryId().matches("[A-Za-z0-9_-]{8,64}")) {
            throw new BadRequestException("Invalid delivery ID");
        }
        if (delivery.positions() != null && delivery.positions().size() > MAX_POSITIONS
                || delivery.pushes() != null && delivery.pushes().size() > MAX_PUSHES
                || delivery.fakes() != null && delivery.fakes().size() > MAX_POSITIONS) {
            throw new BadRequestException("Delivery is too large");
        }
        List<GameNotificationService.Alert> alerts = new ArrayList<>();
        if (delivery.notifications() != null) {
            var input = delivery.notifications();
            if (input.sessionDeviceId() == null || input.alerts() == null || input.alerts().isEmpty()
                    || input.alerts().size() > MAX_ALERTS) {
                throw new BadRequestException("Invalid delivery notifications");
            }
            GameNotificationService.generation(input.generation());
            for (var alert : input.alerts()) {
                alerts.add(GameNotificationService.validate(alert));
            }
        }
        for (PushInput push : delivery.pushes() == null ? List.<PushInput>of() : delivery.pushes()) {
            if (push == null || push.userId() == null || push.userId() <= 0 || push.message() == null
                    || push.message().isBlank() || push.message().length() > 8192
                    || push.subject() != null && push.subject().length() > 512
                    || push.notificator() != null && !push.notificator().matches("[A-Za-z0-9_]{1,64}")
                    || push.alertId() != null && !push.alertId().matches("[A-Za-z0-9_-]{1,64}")) {
                throw new BadRequestException("Invalid delivery push");
            }
        }
        return alerts;
    }

    private String hash(Delivery delivery) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                objectMapper.writeValueAsString(delivery).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    /**
     * The fix time to store, in whole seconds. MySQL keeps {@code tc_positions.fixtime} to the second and rounds
     * fractions on insert, so a millisecond fix time would be compared against a different stored value: a ping at
     * x.6 s is stored as x+1 and a later ping at x.9 s in the same second would then count as older. Truncating
     * first stores exactly the value that is compared, so deliveries within one second all become latest in order.
     */
    private static Instant fixTime(String value, Instant now) {
        if (value == null) {
            return now.truncatedTo(ChronoUnit.SECONDS);
        }
        try {
            Instant parsed = Instant.parse(value).truncatedTo(ChronoUnit.SECONDS);
            if (parsed.isAfter(now.plusSeconds(60)) || parsed.isBefore(Instant.parse("1970-01-01T00:00:01Z"))) {
                throw new BadRequestException("Position fix time is out of range");
            }
            return parsed;
        } catch (DateTimeParseException error) {
            throw new BadRequestException("Invalid position fix time");
        }
    }

    /** The uniform mirror payload the Tool has always uploaded, plus the caller's scalar extras. */
    private Map<String, Object> attributes(Map<String, Object> extras) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(Position.KEY_EVENT, "location");
        result.put(Position.KEY_MOTION, false);
        result.put(Position.KEY_ODOMETER, 0);
        result.put("activity", "still");
        result.put(Position.KEY_BATTERY_LEVEL, 100);
        if (extras != null) {
            if (extras.size() > MAX_ATTRIBUTES) {
                throw new BadRequestException("Too many position attributes");
            }
            for (var entry : extras.entrySet()) {
                Object value = entry.getValue();
                if (entry.getKey() == null || !entry.getKey().matches("[A-Za-z0-9_.-]{1,64}")
                        || !(value instanceof String || value instanceof Number || value instanceof Boolean)
                        || value instanceof String text && text.length() > 2048) {
                    throw new BadRequestException("Invalid position attribute");
                }
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    private long resolveDevice(Connection connection, PositionInput input) throws SQLException {
        if (input.deviceId() != null) {
            return input.deviceId();
        }
        if (input.uniqueId() == null || input.uniqueId().isBlank()) {
            throw new BadRequestException("Position destination required");
        }
        try (var query = connection.prepareStatement("SELECT id FROM tc_devices WHERE uniqueid = ?")) {
            query.setString(1, input.uniqueId());
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new BadRequestException("Unknown position destination " + input.uniqueId());
                }
                return rows.getLong(1);
            }
        }
    }

    private Instant latestFix(Connection connection, long deviceId) throws SQLException {
        long positionId;
        try (var query = connection.prepareStatement("SELECT positionid FROM tc_devices WHERE id = ? FOR UPDATE")) {
            query.setLong(1, deviceId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new BadRequestException("Position destination not found");
                }
                positionId = rows.getLong(1);
            }
        }
        if (positionId <= 0) {
            return null;
        }
        try (var query = connection.prepareStatement("SELECT fixtime FROM tc_positions WHERE id = ?")) {
            query.setLong(1, positionId);
            try (var rows = query.executeQuery()) {
                // Whole seconds like the new fix time, also for rows stored with fractions (H2, PostgreSQL).
                return rows.next() ? rows.getTimestamp(1).toInstant().truncatedTo(ChronoUnit.SECONDS) : null;
            }
        }
    }

    private static void checkCoordinates(Double latitude, Double longitude) {
        if (latitude == null || longitude == null
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180 || latitude.isNaN() || longitude.isNaN()) {
            throw new BadRequestException("Invalid position coordinates");
        }
    }

    /** The reservation lock orders owner cancellation and the choice of position in the same transaction. */
    private static PositionInput deliveredPosition(
            PositionInput input, Set<Long> guardedReservations, Set<Long> consumedReservations) {
        if (input.fakeReservationId() == null
                && input.fallbackLatitude() == null && input.fallbackLongitude() == null) {
            return input;
        }
        if (!guardedReservations.contains(input.fakeReservationId())) {
            throw new BadRequestException("A guarded position needs a matching fake settlement");
        }
        checkCoordinates(input.fallbackLatitude(), input.fallbackLongitude());
        if (consumedReservations.contains(input.fakeReservationId())) {
            return input;
        }
        return new PositionInput(input.deviceId(), input.uniqueId(),
                input.fallbackLatitude(), input.fallbackLongitude(), input.fixTime(), input.attributes());
    }

    /** Older Tools may have sent a cancelled fake's coordinate without its real fallback. */
    private static void checkLegacyPositions(
            List<PositionInput> positions, Set<Long> guardedReservations, Set<Long> consumedReservations) {
        Set<Long> unsettled = new LinkedHashSet<>(guardedReservations);
        unsettled.removeAll(consumedReservations);
        for (PositionInput position : positions) {
            if (position != null) {
                unsettled.remove(position.fakeReservationId());
            }
        }
        if (!unsettled.isEmpty()) {
            throw new WebApplicationException("A fake was cancelled. Update the Tool to send its fallback.", 409);
        }
    }

    private Position insertPosition(Connection connection, long deviceId, PositionInput input, Instant now)
            throws Exception {
        checkCoordinates(input.latitude(), input.longitude());
        Position position = new Position(PROTOCOL);
        position.setDeviceId(deviceId);
        Date fix = Date.from(fixTime(input.fixTime(), now));
        position.setServerTime(Date.from(now));
        position.setDeviceTime(fix);
        position.setFixTime(fix);
        position.setValid(true);
        position.setLatitude(input.latitude());
        position.setLongitude(input.longitude());
        position.setAttributes(new org.traccar.model.AttributeMap(attributes(input.attributes())));
        // Same column conversions as DatabaseStorage, so these positions read back like tracker uploads.
        try (var query = connection.prepareStatement("INSERT INTO tc_positions (protocol,deviceid,servertime,"
                + "devicetime,fixtime,valid,latitude,longitude,altitude,speed,course,address,attributes,accuracy,"
                + "network,geofenceids) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            query.setString(1, PROTOCOL);
            query.setLong(2, deviceId);
            query.setTimestamp(3, new Timestamp(position.getServerTime().getTime()));
            query.setTimestamp(4, new Timestamp(fix.getTime()));
            query.setTimestamp(5, new Timestamp(fix.getTime()));
            query.setBoolean(6, true);
            query.setDouble(7, input.latitude());
            query.setDouble(8, input.longitude());
            query.setDouble(9, 0);
            query.setDouble(10, 0);
            query.setDouble(11, 0);
            query.setNull(12, Types.VARCHAR);
            query.setString(13, objectMapper.writeValueAsString(position.getAttributes()));
            query.setDouble(14, 0);
            query.setNull(15, Types.VARCHAR);
            query.setNull(16, Types.VARCHAR);
            query.executeUpdate();
            try (var keys = query.getGeneratedKeys()) {
                keys.next();
                position.setId(keys.getLong(1));
            }
        }
        return position;
    }

    private Outcome stored(Connection connection, long rowId, String json) throws Exception {
        Outcome outcome = objectMapper.readValue(json, Outcome.class);
        List<PushOutcome> pushes = new ArrayList<>();
        Map<Long, PushOutcome> current = new LinkedHashMap<>();
        try (var query = connection.prepareStatement(
                "SELECT id,userid,status,detail FROM tc_game_push_outbox WHERE deliveryrowid = ?")) {
            query.setLong(1, rowId);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    long user = rows.getLong("userid");
                    current.put(rows.getLong("id"), new PushOutcome(rows.getLong("id"), rows.wasNull() ? null : user,
                            rows.getString("status"), rows.getString("detail")));
                }
            }
        }
        for (PushOutcome push : outcome.pushes()) {
            pushes.add(push.id() != null && current.containsKey(push.id()) ? current.get(push.id()) : push);
        }
        return new Outcome(outcome.deliveryId(), true, outcome.epoch(), outcome.positions(),
                outcome.runtimeRevision(), outcome.generation(), outcome.alerts(), pushes,
                outcome.requests() == null ? List.of() : outcome.requests(), outcome.statusRevision(),
                outcome.jokers() == null ? List.of() : outcome.jokers(),
                outcome.fakes() == null ? List.of() : outcome.fakes());
    }

    private static boolean member(Connection connection, long gameSessionId, long userId) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT 1 FROM tc_game_session_members WHERE gamesessionid = ? AND userid = ?")) {
            query.setLong(1, gameSessionId);
            query.setLong(2, userId);
            try (var rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    /**
     * Applies a delivery that the caller has validated and fenced with {@code epoch}. Alerts must already be
     * validated by {@link #validate(Delivery)} and checked against their recipients.
     */
    public Applied apply(
            long gameSessionId, long epoch, Delivery delivery, List<GameNotificationService.Alert> alerts)
            throws Exception {
        String hash = hash(delivery);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Applied result = apply(connection, gameSessionId, epoch, delivery, alerts, hash);
                connection.commit();
                return result;
            } catch (SQLException error) {
                connection.rollback();
                if (error.getSQLState() != null && error.getSQLState().startsWith("23")) {
                    throw new WebApplicationException(
                            "Delivery conflicts with stored data: " + error.getMessage(), 409);
                }
                throw error;
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private Applied apply(Connection connection, long gameSessionId, long epoch, Delivery delivery,
                          List<GameNotificationService.Alert> alerts, String hash) throws Exception {
        Long sessionDevice;
        try (var query = connection.prepareStatement(
                "SELECT sessiondeviceid FROM tc_game_sessions WHERE id = ? FOR UPDATE")) {
            query.setLong(1, gameSessionId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new NotFoundException("Game session not found");
                }
                long value = rows.getLong(1);
                sessionDevice = rows.wasNull() ? null : value;
            }
        }
        try (var query = connection.prepareStatement(
                "SELECT id,gamesessionid,requesthash,outcome FROM tc_game_deliveries WHERE deliveryid = ?")) {
            query.setString(1, delivery.deliveryId());
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    if (rows.getLong("gamesessionid") != gameSessionId || !hash.equals(rows.getString("requesthash"))) {
                        throw new WebApplicationException("Delivery ID was already used for different effects", 409);
                    }
                    return new Applied(stored(connection, rows.getLong("id"), rows.getString("outcome")),
                            List.of(), Set.of(), null, Set.of(), false, List.of(), false, false, false, Set.of());
                }
            }
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        long rowId;
        try (var query = connection.prepareStatement("INSERT INTO tc_game_deliveries"
                + " (gamesessionid,deliveryid,epoch,requesthash,outcome,createdat) VALUES (?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            query.setLong(1, gameSessionId);
            query.setString(2, delivery.deliveryId());
            query.setLong(3, epoch);
            query.setString(4, hash);
            query.setString(5, "{}");
            query.setTimestamp(6, Timestamp.from(now), utc());
            query.executeUpdate();
            try (var keys = query.getGeneratedKeys()) {
                keys.next();
                rowId = keys.getLong(1);
            }
        }

        // Settle before writing the positions. A cancelled or released reservation can only send its fallback.
        List<GameJokerService.FakeOutcome> fakeOutcomes = delivery.fakes() == null || delivery.fakes().isEmpty()
                ? List.of() : GameJokerService.settleFakes(connection, gameSessionId, delivery.fakes());
        var runningPing = delivery.speedhuntPing();
        double[] runningCoordinate = null;
        if (runningPing != null) {
            if (delivery.positions() == null || delivery.positions().isEmpty()) {
                throw new BadRequestException("A speedhunt ping must deliver a position");
            }
            var running = GameJokerService.deliverRunningFakes(connection, gameSessionId, runningPing);
            if (!running.outcomes().isEmpty()) {
                var outcomes = new ArrayList<>(fakeOutcomes);
                outcomes.addAll(running.outcomes());
                fakeOutcomes = outcomes;
            }
            if (running.coordinate() != null) {
                runningCoordinate = GameJokerService.runningFakeCoordinate(running.coordinate(), runningPing.live());
            }
        }
        Set<Long> guardedReservations = new LinkedHashSet<>();
        if (delivery.fakes() != null) {
            for (var fake : delivery.fakes()) {
                if (GameJokerService.USED.equals(fake.outcome()) || GameJokerService.BOUND.equals(fake.outcome())) {
                    guardedReservations.add(fake.reservationId());
                }
            }
        }
        Set<Long> consumedReservations = new LinkedHashSet<>();
        for (var fake : fakeOutcomes) {
            if (GameJokerService.CONSUMED.equals(fake.status())) {
                consumedReservations.add(fake.reservationId());
            }
        }

        List<PositionOutcome> positionOutcomes = new ArrayList<>();
        List<Position> latest = new ArrayList<>();
        Set<Long> devices = new LinkedHashSet<>();
        List<PositionInput> positions = delivery.positions() == null ? List.of() : delivery.positions();
        if (!positions.isEmpty()) {
            if (!Boolean.TRUE.equals(delivery.fakePositionsGuarded())) {
                checkLegacyPositions(positions, guardedReservations, consumedReservations);
            }
            Map<Long, Boolean> allowed = sessions.effectDevices(connection, gameSessionId);
            for (PositionInput input : positions) {
                if (input == null) {
                    throw new BadRequestException("Invalid position");
                }
                long deviceId = resolveDevice(connection, input);
                if (!allowed.containsKey(deviceId)) {
                    throw new BadRequestException(
                            "Device " + deviceId + " is not a mirror or the game-state device of this session");
                }
                Instant previous = latestFix(connection, deviceId);
                PositionInput delivered = deliveredPosition(input, guardedReservations, consumedReservations);
                if (runningCoordinate != null && runningPing.hunterMirror().equals(input.uniqueId())) {
                    delivered = new PositionInput(input.deviceId(), input.uniqueId(), runningCoordinate[0],
                            runningCoordinate[1], input.fixTime(), input.attributes());
                }
                Position position = insertPosition(connection, deviceId, delivered, now);
                boolean becomesLatest = previous == null || !position.getFixTime().toInstant().isBefore(previous);
                if (becomesLatest) {
                    try (var query = connection.prepareStatement(
                            "UPDATE tc_devices SET positionid = ? WHERE id = ?")) {
                        query.setLong(1, position.getId());
                        query.setLong(2, deviceId);
                        query.executeUpdate();
                    }
                    latest.add(position);
                }
                devices.add(deviceId);
                positionOutcomes.add(new PositionOutcome(deviceId, position.getId(), becomesLatest,
                        position.getFixTime().toInstant().toString()));
            }
        }

        Long runtimeRevision = null;
        boolean speedhuntHistoryChanged = false;
        if (delivery.runtime() != null) {
            if (delivery.runtime().expectedRevision() == null || delivery.runtime().expectedRevision() < 0) {
                throw new BadRequestException("Runtime expectedRevision is required");
            }
            var update = sessions.updateRuntime(connection, gameSessionId,
                    delivery.runtime().expectedRevision(), delivery.runtime().document());
            runtimeRevision = update.revision();
            speedhuntHistoryChanged = update.speedhuntHistoryChanged();
        }

        List<AlertOutcome> alertOutcomes = new ArrayList<>();
        Set<String> created = new LinkedHashSet<>();
        Set<Long> notifiedRecipients = new LinkedHashSet<>();
        boolean sessionNotified = false;
        String generation = null;
        Long notificationDevice = null;
        if (delivery.notifications() != null) {
            notificationDevice = delivery.notifications().sessionDeviceId();
            if (!notificationDevice.equals(sessionDevice)) {
                throw new BadRequestException("Notifications target another session device");
            }
            generation = delivery.notifications().generation();
            for (var alert : alerts) {
                var creation = notifications.create(connection, notificationDevice, generation, alert);
                alertOutcomes.add(new AlertOutcome(alert.id(), creation.created()));
                if (creation.created()) {
                    created.add(alert.id());
                    if (alert.recipientUserId() == null) {
                        sessionNotified = true;
                    } else {
                        notifiedRecipients.add(alert.recipientUserId());
                    }
                }
            }
        }

        List<PushOutcome> pushOutcomes = new ArrayList<>();
        List<Long> queued = new ArrayList<>();
        for (PushInput push : delivery.pushes() == null ? List.<PushInput>of() : delivery.pushes()) {
            if (push.alertId() != null && !created.contains(push.alertId())) {
                pushOutcomes.add(new PushOutcome(null, push.userId(), "skipped",
                        "The alert was archived before; its push is not sent again"));
                continue;
            }
            if (!member(connection, gameSessionId, push.userId())) {
                pushOutcomes.add(new PushOutcome(null, push.userId(), "rejected",
                        "The recipient is not a member of this session"));
                continue;
            }
            try (var query = connection.prepareStatement("INSERT INTO tc_game_push_outbox (deliveryrowid,"
                    + "gamesessionid,userid,recipientlabel,notificator,subject,message,status,createdat)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                query.setLong(1, rowId);
                query.setLong(2, gameSessionId);
                query.setLong(3, push.userId());
                query.setString(4, GameNotificationService.recipientLabel(connection, push.userId()));
                query.setString(5, push.notificator() == null ? "traccar" : push.notificator());
                query.setString(6, push.subject() == null ? "" : push.subject());
                query.setString(7, push.message());
                query.setString(8, GamePushOutbox.QUEUED);
                query.setTimestamp(9, Timestamp.from(now), utc());
                query.executeUpdate();
                try (var keys = query.getGeneratedKeys()) {
                    keys.next();
                    long id = keys.getLong(1);
                    queued.add(id);
                    pushOutcomes.add(new PushOutcome(id, push.userId(), GamePushOutbox.QUEUED, null));
                }
            }
        }

        if (delivery.logs() != null && !delivery.logs().isEmpty()) {
            sessions.appendLogs(connection, gameSessionId, delivery.logs());
        }

        List<GameJokerService.UseOutcome> jokerOutcomes = new ArrayList<>();
        for (var use : delivery.jokers() == null ? List.<GameJokerService.Use>of() : delivery.jokers()) {
            jokerOutcomes.add(GameJokerService.use(connection, gameSessionId, use, "Operator"));
        }
        boolean caught = delivery.caughtParticipants() != null && !delivery.caughtParticipants().isEmpty();
        if (caught) {
            GameJokerService.participantsCaught(connection, gameSessionId, delivery.caughtParticipants(), now);
        }

        // Trackers whose jokers changed with this delivery; their owners are told after commit.
        Set<Long> jokerDevices = new LinkedHashSet<>();
        jokerOutcomes.forEach(item -> jokerDevices.add(item.deviceId()));
        if (caught) {
            jokerDevices.addAll(delivery.caughtParticipants());
        }
        jokerDevices.addAll(GameJokerService.reservationDevices(connection, gameSessionId,
                fakeOutcomes.stream().filter(item -> !"skipped".equals(item.status()))
                        .map(GameJokerService.FakeOutcome::reservationId).toList()));
        List<RequestOutcome> requestOutcomes = new ArrayList<>();
        if (delivery.requests() != null && !delivery.requests().isEmpty()) {
            for (var request : requests.complete(connection, gameSessionId, delivery.requests())) {
                requestOutcomes.add(new RequestOutcome(request.id(), request.status()));
                if (GameRequestService.JOKER_TYPES.contains(request.type()) && request.targetDeviceId() != null) {
                    jokerDevices.add(request.targetDeviceId());
                }
            }
        }
        Long statusRevision = runningPing == null ? null : requests.advanceRunningPing(
                connection, gameSessionId, runningPing);
        if (delivery.status() != null) {
            statusRevision = requests.putStatus(connection, gameSessionId, delivery.status());
        }

        Outcome outcome = new Outcome(delivery.deliveryId(), false, epoch, positionOutcomes, runtimeRevision,
                generation, alertOutcomes, pushOutcomes, requestOutcomes, statusRevision, jokerOutcomes, fakeOutcomes);
        try (var query = connection.prepareStatement("UPDATE tc_game_deliveries SET outcome = ? WHERE id = ?")) {
            query.setString(1, objectMapper.writeValueAsString(outcome));
            query.setLong(2, rowId);
            query.executeUpdate();
        }
        return new Applied(outcome, latest, devices, notificationDevice, notifiedRecipients, sessionNotified,
                queued, speedhuntHistoryChanged, !requestOutcomes.isEmpty() || caught, statusRevision != null,
                GameJokerService.ownerUsers(connection, gameSessionId, jokerDevices));
    }
}
