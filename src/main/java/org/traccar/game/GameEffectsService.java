/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.traccar.model.AttributeMap;
import org.traccar.model.Position;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fenced, idempotent effects deliveries of data model 2 (plan §3.1–3.2). One delivery is one game operation of the
 * controlling Tool: mirror positions, the typed state operations, request outcomes, operator joker uses, archived
 * notifications, queued pushes, logs and checkpoints, committed in one transaction under a durable delivery ID.
 *
 * <p>The order is the effects transaction's: validate, lock the session and then the touched member states in
 * ascending order, apply, advance the member and session revisions, commit, and only then publish positions,
 * invalidations and pushes through the {@link GamePublisher}. The transaction makes no network calls. Repeating a
 * delivery ID with the same content returns the recorded outcome, with the current push states, and applies
 * nothing; the same ID with other content answers 409.
 *
 * <p>Privacy, as in model 1: fake and real positions use one payload, the target's own speedhunt fake is applied
 * here so hunter-visible data never marks it, joker invalidations reach only the owners concerned (and the hunters
 * for their pool), and nothing names a speedhunt target to players and supporters.
 */
@Singleton
public class GameEffectsService {

    public static final String PROTOCOL = "osmand";
    public static final int MAX_POSITIONS = 200;
    public static final int MAX_OPERATIONS = 200;
    public static final int MAX_ALERTS = 100;
    public static final int MAX_PUSHES = 500;
    private static final int MAX_ATTRIBUTES = 64;

    /**
     * A mirror position. A position guarded by a timetable fake ({@code fakeReservationId}) carries the fake
     * coordinate and the real one as fallback: it delivers the fake only if the same delivery uses that fake
     * ({@code fakeUsed}), and the fallback otherwise, so an owner's cancellation never races the delivery.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PositionInput(Long deviceId, String uniqueId, Double latitude, Double longitude, String fixTime,
                                Map<String, Object> attributes, Long fakeReservationId, Double fallbackLatitude,
                                Double fallbackLongitude) {
    }

    public record NotificationInput(String generation, List<GameNotificationService.Alert> alerts) {
    }

    /** A push to a member; one with {@code alertId} is queued only when that alert is new with this delivery. */
    public record PushInput(Long memberId, String subject, String message, String notificator, String alertId) {
    }

    public record Delivery(String deliveryId, List<PositionInput> positions, List<GameOperation> operations,
                           NotificationInput notifications, List<PushInput> pushes,
                           List<GameLogService.LogInput> logs, List<GameRequestService.Completion> requests,
                           List<GameJokerService.Use> jokers) {
    }

    public record PositionOutcome(long deviceId, long positionId, boolean latest, String fixTime) {
    }

    public record AlertOutcome(String alertId, boolean created) {
    }

    public record PushOutcome(Long id, Long memberId, String status, String detail) {
    }

    public record RequestOutcome(long id, String status) {
    }

    /** The recorded outcome of a delivery; operator data. */
    public record Outcome(String deliveryId, boolean replayed, long epoch, List<PositionOutcome> positions,
                          List<GameOperations.Result> operations, String generation, List<AlertOutcome> alerts,
                          List<PushOutcome> pushes, List<RequestOutcome> requests,
                          List<GameJokerService.UseOutcome> jokers, List<GameOperations.FakeOutcome> fakes,
                          long sessionRevision) {
    }

    private record Mirror(long deviceId, long sourceMemberId, String destinationRole) {
    }

    private final GameTransactions transactions;
    private final ObjectMapper objectMapper;
    private final GamePublisher publisher;

    @Inject
    public GameEffectsService(GameTransactions transactions, ObjectMapper objectMapper, GamePublisher publisher) {
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.publisher = publisher;
    }

    private static <T> List<T> list(List<T> value) {
        return value == null ? List.of() : value;
    }

    private static boolean hasNull(List<?> value) {
        return value != null && value.stream().anyMatch(java.util.Objects::isNull);
    }

    /** Checks the shape of a delivery before any transaction. */
    static void validate(Delivery delivery) {
        if (delivery == null || delivery.deliveryId() == null
                || !delivery.deliveryId().matches("[A-Za-z0-9_-]{8,64}")) {
            throw GameErrors.invalid("Invalid delivery ID");
        }
        if (list(delivery.positions()).size() > MAX_POSITIONS || list(delivery.operations()).size() > MAX_OPERATIONS
                || list(delivery.pushes()).size() > MAX_PUSHES || list(delivery.requests()).size() > MAX_OPERATIONS
                || list(delivery.jokers()).size() > MAX_OPERATIONS) {
            throw GameErrors.invalid("Delivery is too large");
        }
        if (hasNull(delivery.positions()) || hasNull(delivery.operations()) || hasNull(delivery.pushes())
                || hasNull(delivery.jokers()) || hasNull(delivery.requests())) {
            throw GameErrors.invalid("Invalid delivery item");
        }
        if (delivery.notifications() != null) {
            var input = delivery.notifications();
            GameNotificationService.generation(input.generation());
            if (input.alerts() == null || input.alerts().isEmpty() || input.alerts().size() > MAX_ALERTS) {
                throw GameErrors.invalid("Invalid delivery notifications");
            }
        }
        for (PushInput push : list(delivery.pushes())) {
            if (push.memberId() == null || push.message() == null || push.message().isBlank()
                    || push.message().length() > 8192 || push.subject() != null && push.subject().length() > 512
                    || push.notificator() != null && !push.notificator().matches("[A-Za-z0-9_]{1,64}")
                    || push.alertId() != null && !push.alertId().matches("[A-Za-z0-9_-]{1,64}")) {
                throw GameErrors.invalid("Invalid delivery push");
            }
        }
    }

    private String hash(Delivery delivery) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                objectMapper.writeValueAsString(delivery).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    /**
     * Applies a delivery that the controller fence admitted under {@code epoch} (the current lease epoch; live
     * uploads record it to detect stale rows after a handover).
     */
    public Outcome apply(long sessionId, long epoch, Delivery delivery) throws Exception {
        validate(delivery);
        String hash = hash(delivery);
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            var stored = transaction.first("SELECT id, gamesessionid, requesthash, outcome FROM tc_game_deliveries"
                    + " WHERE deliveryid = ?", rows -> new Object[] {rows.getLong(1), rows.getLong(2),
                            rows.getString(3), rows.getString(4)}, delivery.deliveryId());
            if (stored.isPresent()) {
                Object[] row = stored.get();
                if ((long) row[1] != sessionId || !hash.equals(row[2])) {
                    throw GameErrors.conflict("Delivery ID was already used for different effects");
                }
                return replay(transaction, (long) row[0], (String) row[3]);
            }
            long rowId = transaction.insert("INSERT INTO tc_game_deliveries (gamesessionid, deliveryid, epoch,"
                    + " requesthash, outcome, createdat) VALUES (?, ?, ?, ?, ?, ?)", sessionId, delivery.deliveryId(),
                    epoch, hash, "{}", transaction.now());
            Outcome outcome = apply(transaction, sessionId, epoch, delivery, rowId);
            transaction.update("UPDATE tc_game_deliveries SET outcome = ? WHERE id = ?",
                    objectMapper.writeValueAsString(outcome), rowId);
            return outcome;
        });
    }

    private Outcome replay(GameTransaction transaction, long rowId, String json) throws Exception {
        Outcome outcome = objectMapper.readValue(json, Outcome.class);
        Map<Long, PushOutcome> current = new LinkedHashMap<>();
        transaction.list("SELECT id, recipientmemberid, status, detail FROM tc_game_push_outbox"
                + " WHERE deliveryrowid = ?", rows -> current.put(rows.getLong(1), new PushOutcome(rows.getLong(1),
                        GameTransaction.longOrNull(rows, "recipientmemberid"), rows.getString(3),
                        rows.getString(4))), rowId);
        List<PushOutcome> pushes = new ArrayList<>();
        for (PushOutcome push : outcome.pushes()) {
            pushes.add(push.id() != null && current.containsKey(push.id()) ? current.get(push.id()) : push);
        }
        return new Outcome(outcome.deliveryId(), true, outcome.epoch(), outcome.positions(), outcome.operations(),
                outcome.generation(), outcome.alerts(), pushes, outcome.requests(), outcome.jokers(),
                outcome.fakes(), outcome.sessionRevision());
    }

    private Outcome apply(GameTransaction transaction, long sessionId, long epoch, Delivery delivery, long rowId)
            throws Exception {
        List<GameOperation> operations = list(delivery.operations());
        transaction.lockMemberStates(GameOperations.members(transaction, sessionId, operations));
        GameOperations applied = new GameOperations(transaction, sessionId, epoch, "Tool", objectMapper);

        List<RequestOutcome> requestOutcomes = new ArrayList<>();
        for (GameRequest request : GameRequestService.complete(transaction, sessionId, list(delivery.requests()),
                applied)) {
            requestOutcomes.add(new RequestOutcome(request.id(), request.status()));
        }
        for (int i = 0; i < operations.size(); i++) {
            applied.apply(i, operations.get(i));
        }
        for (long request : applied.succeededBreaks()) {
            if (!applied.immediateBreaks().contains(request)) {
                throw GameErrors.invalid("Break request " + request + " starts its break with its outcome");
            }
        }
        List<GameJokerService.UseOutcome> uses = new ArrayList<>();
        for (GameJokerService.Use use : list(delivery.jokers())) {
            GameJokerService.UseOutcome used = GameJokerService.use(transaction, sessionId, use, "Tool");
            uses.add(used);
            applied.jokerMembers().add(used.memberId());
        }

        Set<Long> devices = new LinkedHashSet<>();
        List<Position> latest = new ArrayList<>();
        List<PositionOutcome> positions = positions(transaction, sessionId, delivery, operations, applied, devices,
                latest);

        String generation = null;
        List<AlertOutcome> alerts = new ArrayList<>();
        Set<String> created = new LinkedHashSet<>();
        boolean sessionAlert = false;
        Set<Long> recipients = new LinkedHashSet<>();
        if (delivery.notifications() != null) {
            generation = delivery.notifications().generation();
            for (GameNotificationService.Alert alert : delivery.notifications().alerts()) {
                var creation = GameNotificationService.create(transaction, sessionId, generation, alert);
                alerts.add(new AlertOutcome(alert.id(), creation.created()));
                if (creation.created()) {
                    created.add(alert.id());
                    if (alert.recipientMemberId() == null) {
                        sessionAlert = true;
                    } else {
                        recipients.add(alert.recipientMemberId());
                    }
                }
            }
        }

        List<PushOutcome> pushes = new ArrayList<>();
        List<Long> queued = new ArrayList<>();
        for (PushInput push : list(delivery.pushes())) {
            if (push.alertId() != null && !created.contains(push.alertId())) {
                pushes.add(new PushOutcome(null, push.memberId(), GamePushOutbox.SKIPPED,
                        "The alert was archived before; its push is not sent again"));
                continue;
            }
            Optional<GameMember> member = GameMemberRepository.find(transaction, sessionId, push.memberId());
            if (member.isEmpty() || !member.get().active() || member.get().userId() == null) {
                pushes.add(new PushOutcome(null, push.memberId(), GamePushOutbox.REJECTED,
                        "The recipient is not a member of this session with an account"));
                continue;
            }
            long id = transaction.insert("INSERT INTO tc_game_push_outbox (deliveryrowid, gamesessionid,"
                    + " recipientmemberid, userid, recipientlabel, notificator, subject, message, status, createdat)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rowId, sessionId, member.get().id(),
                    member.get().userId(), member.get().displayName(),
                    push.notificator() == null ? "traccar" : push.notificator(),
                    push.subject() == null ? "" : push.subject(), push.message(), GamePushOutbox.QUEUED,
                    transaction.now());
            queued.add(id);
            pushes.add(new PushOutcome(id, member.get().id(), GamePushOutbox.QUEUED, null));
        }

        if (delivery.logs() != null && !delivery.logs().isEmpty()) {
            GameLogService.append(transaction, sessionId, delivery.logs());
        }

        long revision = transaction.bumpSession();
        GameStateService.publish(transaction, publisher, sessionId, applied);
        if (!devices.isEmpty()) {
            transaction.afterCommit(() -> publisher.positions(devices, latest));
        }
        if (sessionAlert || !recipients.isEmpty()) {
            Set<Long> members = sessionAlert ? null : Set.copyOf(recipients);
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(sessionId, GamePublisher.NOTIFICATIONS, members)));
        }
        if (!queued.isEmpty()) {
            transaction.afterCommit(() -> publisher.pushes(queued));
        }
        return new Outcome(delivery.deliveryId(), false, epoch, positions, applied.results(), generation, alerts,
                pushes, requestOutcomes, uses, applied.fakes(), revision);
    }

    // Positions ---------------------------------------------------------------------------------------------

    private List<PositionOutcome> positions(GameTransaction transaction, long sessionId, Delivery delivery,
                                            List<GameOperation> operations, GameOperations applied,
                                            Set<Long> devices, List<Position> latest) throws Exception {
        List<PositionInput> inputs = list(delivery.positions());
        List<PositionOutcome> result = new ArrayList<>();
        if (inputs.isEmpty()) {
            return result;
        }
        Set<Long> guarded = new LinkedHashSet<>();
        for (GameOperation operation : operations) {
            if (operation instanceof GameOperation.FakeUsed used) {
                guarded.add(used.reservationId());
            }
        }
        Map<Long, Mirror> mirrors = new HashMap<>();
        for (GameResource.Mirror mirror : GameResourceRepository.mirrors(transaction, sessionId)) {
            if (mirror.active() && mirror.deviceId() != null) {
                mirrors.put(mirror.deviceId(), new Mirror(mirror.deviceId(), mirror.sourceMemberId(),
                        mirror.destinationRole()));
            }
        }
        Instant now = transaction.now();
        for (PositionInput input : inputs) {
            long deviceId = device(transaction, input);
            Mirror mirror = mirrors.get(deviceId);
            if (mirror == null) {
                throw GameErrors.invalid("Device " + deviceId + " is not an active mirror of this game session");
            }
            double[] coordinate = coordinate(input, guarded, applied.consumedFakes());
            GameOperations.AppliedFake fake = applied.appliedFakes().get(mirror.sourceMemberId());
            if (fake != null && GameMember.HUNTER.equals(mirror.destinationRole())) {
                // The target's own speedhunt fake reaches hunter destinations only.
                coordinate = fakeCoordinate(fake.latitude(), fake.longitude(), fake.live());
            }
            Instant previous = latestFix(transaction, deviceId);
            Position position = insertPosition(transaction, deviceId, input, coordinate, now);
            boolean becomesLatest = previous == null || !position.getFixTime().toInstant().isBefore(previous);
            if (becomesLatest) {
                transaction.update("UPDATE tc_devices SET positionid = ? WHERE id = ?", position.getId(), deviceId);
                latest.add(position);
            }
            devices.add(deviceId);
            result.add(new PositionOutcome(deviceId, position.getId(), becomesLatest,
                    position.getFixTime().toInstant().toString()));
        }
        return result;
    }

    private static long device(GameTransaction transaction, PositionInput input) throws SQLException {
        if (input.deviceId() != null) {
            return input.deviceId();
        }
        if (input.uniqueId() == null || input.uniqueId().isBlank()) {
            throw GameErrors.invalid("Position destination required");
        }
        return transaction.first("SELECT id FROM tc_devices WHERE uniqueid = ?", rows -> rows.getLong(1),
                input.uniqueId()).orElseThrow(() -> GameErrors.invalid("Unknown position destination "
                        + input.uniqueId()));
    }

    private static void checkCoordinates(Double latitude, Double longitude) {
        if (latitude == null || longitude == null || latitude.isNaN() || longitude.isNaN()
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            throw GameErrors.invalid("Invalid position coordinates");
        }
    }

    /** The coordinate a position delivers: its own, or for a guarded fake the fallback unless the fake is used. */
    private static double[] coordinate(PositionInput input, Set<Long> guarded, Set<Long> consumed) {
        if (input.fakeReservationId() == null && input.fallbackLatitude() == null
                && input.fallbackLongitude() == null) {
            checkCoordinates(input.latitude(), input.longitude());
            return new double[] {input.latitude(), input.longitude()};
        }
        if (input.fakeReservationId() == null || !guarded.contains(input.fakeReservationId())) {
            throw GameErrors.invalid("A guarded position needs a matching fake settlement");
        }
        checkCoordinates(input.fallbackLatitude(), input.fallbackLongitude());
        if (consumed.contains(input.fakeReservationId())) {
            checkCoordinates(input.latitude(), input.longitude());
            return new double[] {input.latitude(), input.longitude()};
        }
        return new double[] {input.fallbackLatitude(), input.fallbackLongitude()};
    }

    /**
     * A joker fake's coordinate: as planned for a static ping, and moved by a new random 5–20 m in a random direction
     * on every upload of a live ping.
     */
    static double[] fakeCoordinate(double latitude, double longitude, boolean live) {
        if (!live) {
            return new double[] {latitude, longitude};
        }
        var random = ThreadLocalRandom.current();
        double angle = random.nextDouble(5, 20) / 6378137;
        double bearing = random.nextDouble(0, 2 * Math.PI);
        double lat = Math.toRadians(latitude);
        double sinLatitude = Math.sin(lat) * Math.cos(angle) + Math.cos(lat) * Math.sin(angle) * Math.cos(bearing);
        double newLat = Math.asin(Math.max(-1, Math.min(1, sinLatitude)));
        double newLon = longitude + Math.toDegrees(Math.atan2(Math.sin(bearing) * Math.sin(angle) * Math.cos(lat),
                Math.cos(angle) - Math.sin(lat) * Math.sin(newLat)));
        return new double[] {Math.toDegrees(newLat), Math.IEEEremainder(newLon, 360)};
    }

    /**
     * The fix time to store, in whole seconds. MySQL keeps {@code tc_positions.fixtime} to the second and rounds
     * fractions, so a millisecond fix time would be compared against a different stored value.
     */
    private static Instant fixTime(String value, Instant now) {
        if (value == null) {
            return now.truncatedTo(ChronoUnit.SECONDS);
        }
        try {
            Instant parsed = Instant.parse(value).truncatedTo(ChronoUnit.SECONDS);
            if (parsed.isAfter(now.plusSeconds(60)) || parsed.isBefore(GameTime.MIN)) {
                throw GameErrors.invalid("Position fix time is out of range");
            }
            return parsed;
        } catch (DateTimeParseException error) {
            throw GameErrors.invalid("Invalid position fix time");
        }
    }

    /** The uniform mirror payload the Tool has always uploaded, plus the caller's scalar extras. */
    private static Map<String, Object> attributes(Map<String, Object> extras) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(Position.KEY_EVENT, "location");
        result.put(Position.KEY_MOTION, false);
        result.put(Position.KEY_ODOMETER, 0);
        result.put("activity", "still");
        result.put(Position.KEY_BATTERY_LEVEL, 100);
        if (extras != null) {
            if (extras.size() > MAX_ATTRIBUTES) {
                throw GameErrors.invalid("Too many position attributes");
            }
            for (var entry : extras.entrySet()) {
                Object value = entry.getValue();
                if (entry.getKey() == null || !entry.getKey().matches("[A-Za-z0-9_.-]{1,64}")
                        || !(value instanceof String || value instanceof Number || value instanceof Boolean)
                        || value instanceof String text && text.length() > 2048) {
                    throw GameErrors.invalid("Invalid position attribute");
                }
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    private static Instant latestFix(GameTransaction transaction, long deviceId) throws SQLException {
        long positionId = transaction.first("SELECT positionid FROM tc_devices WHERE id = ? FOR UPDATE",
                rows -> rows.getLong(1), deviceId)
                .orElseThrow(() -> GameErrors.invalid("Position destination not found"));
        if (positionId <= 0) {
            return null;
        }
        // Whole seconds like the new fix time, also for rows stored with fractions (H2).
        return transaction.first("SELECT fixtime FROM tc_positions WHERE id = ?",
                rows -> rows.getTimestamp(1).toInstant().truncatedTo(ChronoUnit.SECONDS), positionId).orElse(null);
    }

    /** Inserts a position with the same column conversions as Traccar's storage, so it reads back like an upload. */
    private Position insertPosition(GameTransaction transaction, long deviceId, PositionInput input,
                                    double[] coordinate, Instant now) throws Exception {
        Position position = new Position(PROTOCOL);
        position.setDeviceId(deviceId);
        Date fix = Date.from(fixTime(input.fixTime(), now));
        position.setServerTime(Date.from(now));
        position.setDeviceTime(fix);
        position.setFixTime(fix);
        position.setValid(true);
        position.setLatitude(coordinate[0]);
        position.setLongitude(coordinate[1]);
        position.setAttributes(new AttributeMap(attributes(input.attributes())));
        position.setId(transaction.insert("INSERT INTO tc_positions (protocol, deviceid, servertime, devicetime,"
                + " fixtime, valid, latitude, longitude, altitude, speed, course, address, attributes, accuracy,"
                + " network, geofenceids) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, NULL, ?, 0, NULL, NULL)",
                PROTOCOL, deviceId, new Timestamp(position.getServerTime().getTime()), new Timestamp(fix.getTime()),
                new Timestamp(fix.getTime()), true, coordinate[0], coordinate[1],
                objectMapper.writeValueAsString(position.getAttributes())));
        return position;
    }

}
