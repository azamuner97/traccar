/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.traccar.model.AttributeMap;
import org.traccar.model.Position;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The one insertion path of mirror positions, used only by the ping engine (no client sends mirror positions): the
 * uniform payload the Tool always uploaded, whole-second fix times, the same column conversions as Traccar's
 * storage, and the device's {@code positionid} moved to the newest fix. Positions are published after commit through
 * a {@link Written} collector. It also reads a tracker's latest real position like {@code /api/positions}.
 */
public final class GamePositions {

    public static final String PROTOCOL = "osmand";

    /** A tracker's position as the engine uses it: the tracker, coordinate and fix time. */
    public record Fix(long deviceId, double latitude, double longitude, Instant fixTime) {

        static Fix of(Position position) {
            Date time = position.getFixTime() != null ? position.getFixTime() : position.getDeviceTime();
            return new Fix(position.getDeviceId(), position.getLatitude(), position.getLongitude(),
                    time == null ? null : time.toInstant());
        }
    }

    /** One mirror position written by a transaction. */
    public record Written(long deviceId, long positionId, boolean latest, String fixTime) {
    }

    /** What a transaction wrote, published to caches and WebSocket after commit. */
    public static final class Collector {
        private final Set<Long> devices = new LinkedHashSet<>();
        private final List<Position> latest = new ArrayList<>();

        public boolean isEmpty() {
            return devices.isEmpty();
        }

        /** Registers the publication after commit, once per transaction. */
        public void publish(GameTransaction transaction, GamePublisher publisher) {
            if (!devices.isEmpty()) {
                Set<Long> received = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(devices));
                List<Position> positions = List.copyOf(latest);
                transaction.afterCommit(() -> publisher.positions(received, positions));
            }
        }
    }

    private GamePositions() {
    }

    static void checkCoordinates(Double latitude, Double longitude) {
        if (latitude == null || longitude == null || latitude.isNaN() || longitude.isNaN()
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            throw GameErrors.invalid("Invalid position coordinates");
        }
    }

    /**
     * The fix time to store, in whole seconds. MySQL keeps {@code tc_positions.fixtime} to the second and rounds
     * fractions, so a millisecond fix time would be compared against a different stored value.
     */
    static Instant fixTime(Instant value, Instant now) {
        Instant parsed = value.truncatedTo(ChronoUnit.SECONDS);
        if (parsed.isAfter(now.plusSeconds(60)) || parsed.isBefore(GameTime.MIN)) {
            throw GameErrors.invalid("Position fix time is out of range");
        }
        return parsed;
    }

    /** The uniform mirror payload: every mirror position carries it, fake or real. */
    static Map<String, Object> attributes() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(Position.KEY_EVENT, "location");
        result.put(Position.KEY_MOTION, false);
        result.put(Position.KEY_ODOMETER, 0);
        result.put("activity", "still");
        result.put(Position.KEY_BATTERY_LEVEL, 100);
        return result;
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

    /** The fix time of a device's latest position (locking the device row), in whole seconds; null without one. */
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

    /**
     * Writes one mirror position with the uniform payload, moves the device's latest position when this fix is not
     * older, and collects it for publication after commit.
     */
    static Written write(GameTransaction transaction, ObjectMapper objectMapper, long deviceId, double latitude,
                         double longitude, Instant fix, Collector collector) throws Exception {
        Instant previous = latestFix(transaction, deviceId);
        Position position = new Position(PROTOCOL);
        position.setDeviceId(deviceId);
        Date fixDate = Date.from(fix);
        position.setServerTime(Date.from(transaction.now()));
        position.setDeviceTime(fixDate);
        position.setFixTime(fixDate);
        position.setValid(true);
        position.setLatitude(latitude);
        position.setLongitude(longitude);
        position.setAttributes(new AttributeMap(attributes()));
        position.setId(transaction.insert("INSERT INTO tc_positions (protocol, deviceid, servertime, devicetime,"
                + " fixtime, valid, latitude, longitude, altitude, speed, course, address, attributes, accuracy,"
                + " network, geofenceids) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, NULL, ?, 0, NULL, NULL)",
                PROTOCOL, deviceId, new Timestamp(position.getServerTime().getTime()),
                new Timestamp(fixDate.getTime()), new Timestamp(fixDate.getTime()), true, latitude, longitude,
                objectMapper.writeValueAsString(position.getAttributes())));
        boolean latest = previous == null || !fix.truncatedTo(ChronoUnit.SECONDS).isBefore(previous);
        if (latest) {
            transaction.update("UPDATE tc_devices SET positionid = ? WHERE id = ?", position.getId(), deviceId);
            collector.latest.add(position);
        }
        collector.devices.add(deviceId);
        return new Written(deviceId, position.getId(), latest, fix.toString());
    }

    /** A tracker's latest stored position, read like {@code /api/positions} (through {@code tc_devices}). */
    public static Optional<Fix> latest(GameTransaction transaction, long deviceId) throws SQLException {
        return transaction.first("SELECT p.latitude, p.longitude, p.fixtime FROM tc_positions p"
                + " JOIN tc_devices d ON d.positionid = p.id WHERE d.id = ?",
                rows -> new Fix(deviceId, rows.getDouble(1), rows.getDouble(2), rows.getTimestamp(3).toInstant()),
                deviceId);
    }

    /** The latest stored fixes of these trackers in one read per 500, by tracker; trackers without one are left out. */
    public static Map<Long, Fix> latest(GameTransaction transaction, Collection<Long> deviceIds) throws SQLException {
        Map<Long, Fix> result = new LinkedHashMap<>();
        List<Long> ids = List.copyOf(new LinkedHashSet<>(deviceIds));
        for (int from = 0; from < ids.size(); from += 500) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + 500));
            String marks = String.join(", ", Collections.nCopies(chunk.size(), "?"));
            for (Fix fix : transaction.list("SELECT d.id, p.latitude, p.longitude, p.fixtime FROM tc_positions p"
                    + " JOIN tc_devices d ON d.positionid = p.id WHERE d.id IN (" + marks + ")",
                    rows -> new Fix(rows.getLong(1), rows.getDouble(2), rows.getDouble(3),
                            rows.getTimestamp(4).toInstant()), chunk.toArray())) {
                result.put(fix.deviceId(), fix);
            }
        }
        return result;
    }

}
