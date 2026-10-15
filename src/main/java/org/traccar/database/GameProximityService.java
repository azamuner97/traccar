package org.traccar.database;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Singleton
public class GameProximityService {

    public record Grant(String episodeId, long recipientUserId, long sourceDeviceId,
                        long targetDeviceId, String expiresAt) { }
    public record Circle(String id, double latitude, double longitude, double diameterMeters,
                         int playerCount, int supporterCount, String expiresAt, List<Long> participantDeviceIds) { }
    public record PublicCircle(String id, double latitude, double longitude, double diameterMeters,
                               int playerCount, int supporterCount, String expiresAt) { }
    public record Snapshot(String generation, long revision, List<Grant> grants, List<Circle> circles) { }
    public record Change(long sessionDeviceId, Set<Long> userIds) { }

    private record Participant(long userId, long deviceId, String role) { }

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    @Inject
    public GameProximityService(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    private Map<String, Object> attributes(String json) throws Exception {
        return json == null || json.isBlank() ? Map.of()
                : objectMapper.readValue(json, new TypeReference<HashMap<String, Object>>() { });
    }

    private String generation(Connection connection, long sessionId, boolean lock) throws Exception {
        try (var query = connection.prepareStatement("SELECT attributes FROM tc_devices WHERE id = ?"
                + (lock ? " FOR UPDATE" : ""))) {
            query.setLong(1, sessionId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new NotFoundException("Session device not found");
                }
                var value = attributes(rows.getString(1));
                if (!Boolean.TRUE.equals(value.get("traccarToolGameState"))) {
                    throw new BadRequestException("Not a managed session device");
                }
                return (String) value.get(GameNotificationService.GENERATION_ATTRIBUTE);
            }
        }
    }

    public static Snapshot validate(Snapshot snapshot) {
        if (snapshot == null || snapshot.revision() < 1 || snapshot.grants() == null || snapshot.circles() == null
                || snapshot.grants().size() > 10000 || snapshot.circles().size() > 1000) {
            throw new BadRequestException("Invalid proximity snapshot");
        }
        GameNotificationService.generation(snapshot.generation());
        Set<String> keys = new HashSet<>();
        Map<String, List<Grant>> episodes = new HashMap<>();
        for (Grant grant : snapshot.grants()) {
            if (grant == null || grant.episodeId() == null || !grant.episodeId().matches("[A-Za-z0-9_-]{1,64}")
                    || grant.recipientUserId() <= 0 || grant.sourceDeviceId() <= 0 || grant.targetDeviceId() <= 0
                    || grant.sourceDeviceId() == grant.targetDeviceId()
                    || !keys.add(grant.recipientUserId() + ":" + grant.targetDeviceId())) {
                throw new BadRequestException("Invalid proximity grant");
            }
            timestamp(grant.expiresAt());
            episodes.computeIfAbsent(grant.episodeId(), ignored -> new ArrayList<>()).add(grant);
        }
        for (var grants : episodes.values()) {
            if (grants.size() != 2 || grants.get(0).sourceDeviceId() != grants.get(1).targetDeviceId()
                    || grants.get(1).sourceDeviceId() != grants.get(0).targetDeviceId()
                    || grants.get(0).recipientUserId() == grants.get(1).recipientUserId()
                    || !grants.get(0).expiresAt().equals(grants.get(1).expiresAt())) {
                throw new BadRequestException("Proximity grants must be reciprocal");
            }
        }
        keys.clear();
        for (Circle circle : snapshot.circles()) {
            if (circle == null || circle.id() == null || !circle.id().matches("[A-Za-z0-9_-]{1,64}")
                    || !keys.add(circle.id()) || !Double.isFinite(circle.latitude())
                    || Math.abs(circle.latitude()) > 90 || !Double.isFinite(circle.longitude())
                    || Math.abs(circle.longitude()) > 180 || !Double.isFinite(circle.diameterMeters())
                    || circle.diameterMeters() <= 0 || circle.playerCount() < 0 || circle.supporterCount() < 0
                    || circle.participantDeviceIds() == null || circle.participantDeviceIds().size() < 2
                    || circle.participantDeviceIds().stream().anyMatch(id -> id == null || id <= 0)
                    || new HashSet<>(circle.participantDeviceIds()).size() != circle.participantDeviceIds().size()
                    || (long) circle.playerCount() + circle.supporterCount() != circle.participantDeviceIds().size()) {
                throw new BadRequestException("Invalid proximity circle");
            }
            timestamp(circle.expiresAt());
        }
        return snapshot;
    }

    private static Instant timestamp(String value) {
        try {
            if (value == null || !value.endsWith("Z")) {
                throw new IllegalArgumentException();
            }
            Instant result = Instant.parse(value);
            if (result.toEpochMilli() < 1000) {
                throw new IllegalArgumentException();
            }
            return result;
        } catch (RuntimeException error) {
            throw new BadRequestException("Invalid proximity expiry");
        }
    }

    private Map<Long, Participant> participants(Connection connection, long sessionId) throws Exception {
        Map<Long, Participant> result = new HashMap<>();
        Set<Long> ambiguous = new HashSet<>();
        try (var query = connection.prepareStatement("SELECT id,attributes FROM tc_users"
                + " WHERE administrator = FALSE AND disabled = FALSE"); var rows = query.executeQuery()) {
            while (rows.next()) {
                var attributes = attributes(rows.getString(2));
                String role = String.valueOf(attributes.get("traccarToolUserRole"));
                String assigned = String.valueOf(attributes.get("traccarToolAssignedDeviceId"));
                if (Set.of("player", "supporter").contains(role)
                        && Long.toString(sessionId).equals(String.valueOf(attributes.get("traccarToolSessionDeviceId")))
                        && assigned.matches("[1-9][0-9]*")) {
                    long deviceId;
                    try {
                        deviceId = Long.parseLong(assigned);
                    } catch (NumberFormatException error) {
                        continue;
                    }
                    if (result.put(deviceId, new Participant(rows.getLong(1), deviceId, role)) != null) {
                        ambiguous.add(deviceId);
                    }
                }
            }
        }
        ambiguous.forEach(result::remove);
        for (var participant : List.copyOf(result.values())) {
            boolean valid = false;
            try (var query = connection.prepareStatement("SELECT d.attributes FROM tc_devices d"
                    + " INNER JOIN tc_user_device p ON p.deviceid = d.id WHERE d.id = ? AND p.userid = ?"
                    + " AND EXISTS (SELECT 1 FROM tc_user_device s WHERE s.userid = p.userid AND s.deviceid = ?)")) {
                query.setLong(1, participant.deviceId());
                query.setLong(2, participant.userId());
                query.setLong(3, sessionId);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        var value = attributes(rows.getString(1));
                        valid = !Boolean.TRUE.equals(value.get("traccarToolMirror"))
                                && !Boolean.TRUE.equals(value.get("traccarToolGameState"))
                                && participant.role().equals(value.get("traccarToolRole"));
                    }
                }
            }
            if (!valid) {
                result.remove(participant.deviceId());
            }
        }
        return result;
    }

    private boolean validGrant(Grant grant, Map<Long, Participant> participants) {
        var source = participants.get(grant.sourceDeviceId());
        return source != null && source.userId() == grant.recipientUserId()
                && participants.containsKey(grant.targetDeviceId());
    }

    private boolean validCircle(Circle circle, Map<Long, Participant> participants) {
        return circle.participantDeviceIds().stream().allMatch(participants::containsKey)
                && circle.participantDeviceIds().stream().filter(id -> "player".equals(participants.get(id).role()))
                .count() == circle.playerCount();
    }

    private Snapshot read(Connection connection, long sessionId, String generation) throws Exception {
        long revision = 0;
        try (var query = connection.prepareStatement("SELECT generation,revision FROM tc_game_proximity_state"
                + " WHERE sessiondeviceid = ?")) {
            query.setLong(1, sessionId);
            try (var rows = query.executeQuery()) {
                if (rows.next() && Objects.equals(rows.getString(1), generation)) {
                    revision = rows.getLong(2);
                }
            }
        }
        List<Grant> grants = new ArrayList<>();
        List<Circle> circles = new ArrayList<>();
        if (revision > 0) {
            for (String table : List.of("grants", "circles")) {
                try (var query = connection.prepareStatement("SELECT data FROM tc_game_proximity_" + table
                        + " WHERE sessiondeviceid = ? AND expiresat > ?")) {
                    query.setLong(1, sessionId);
                    query.setLong(2, System.currentTimeMillis());
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) {
                            if (table.equals("grants")) {
                                grants.add(objectMapper.readValue(rows.getString(1), Grant.class));
                            } else {
                                circles.add(objectMapper.readValue(rows.getString(1), Circle.class));
                            }
                        }
                    }
                }
            }
        }
        return new Snapshot(generation, revision, grants, circles);
    }

    public Snapshot get(long sessionId) throws Exception {
        try (var connection = dataSource.getConnection()) {
            return read(connection, sessionId, generation(connection, sessionId, false));
        }
    }

    public List<PublicCircle> circles(long sessionId) throws Exception {
        return get(sessionId).circles().stream().map(circle -> new PublicCircle(circle.id(), circle.latitude(),
                circle.longitude(), circle.diameterMeters(), circle.playerCount(), circle.supporterCount(),
                circle.expiresAt())).toList();
    }

    private void delete(Connection connection, long sessionId) throws Exception {
        for (String table : List.of("grants", "circles", "state")) {
            try (var query = connection.prepareStatement("DELETE FROM tc_game_proximity_" + table
                    + " WHERE sessiondeviceid = ?")) {
                query.setLong(1, sessionId);
                query.executeUpdate();
            }
        }
    }

    public Snapshot replace(long sessionId, Snapshot input) throws Exception {
        Snapshot snapshot = validate(input);
        String serialized = objectMapper.writeValueAsString(snapshot);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String generation = generation(connection, sessionId, true);
                if (!snapshot.generation().equals(generation)) {
                    throw new WebApplicationException("Proximity generation conflict", 409);
                }
                try (var query = connection.prepareStatement("SELECT generation,revision,snapshot"
                        + " FROM tc_game_proximity_state WHERE sessiondeviceid = ?")) {
                    query.setLong(1, sessionId);
                    try (var rows = query.executeQuery()) {
                        if (rows.next() && generation.equals(rows.getString(1))) {
                            if (rows.getLong(2) == snapshot.revision() && serialized.equals(rows.getString(3))) {
                                connection.commit();
                                return read(connection, sessionId, generation);
                            }
                            if (rows.getLong(2) >= snapshot.revision()) {
                                throw new WebApplicationException("Proximity revision conflict", 409);
                            }
                        }
                    }
                }
                var participants = participants(connection, sessionId);
                if (snapshot.grants().stream().anyMatch(grant -> !validGrant(grant, participants))
                        || snapshot.circles().stream().anyMatch(circle -> !validCircle(circle, participants))) {
                    throw new ForbiddenException("Proximity participants do not match this session");
                }
                delete(connection, sessionId);
                try (var query = connection.prepareStatement("INSERT INTO tc_game_proximity_state"
                        + " (sessiondeviceid,generation,revision,snapshot) VALUES (?,?,?,?)")) {
                    query.setLong(1, sessionId);
                    query.setString(2, generation);
                    query.setLong(3, snapshot.revision());
                    query.setString(4, serialized);
                    query.executeUpdate();
                }
                for (Grant grant : snapshot.grants()) {
                    try (var query = connection.prepareStatement("INSERT INTO tc_game_proximity_grants"
                            + " (sessiondeviceid,userid,sourcedeviceid,deviceid,expiresat,data)"
                            + " VALUES (?,?,?,?,?,?)")) {
                        query.setLong(1, sessionId);
                        query.setLong(2, grant.recipientUserId());
                        query.setLong(3, grant.sourceDeviceId());
                        query.setLong(4, grant.targetDeviceId());
                        query.setLong(5, timestamp(grant.expiresAt()).toEpochMilli());
                        query.setString(6, objectMapper.writeValueAsString(grant));
                        query.executeUpdate();
                    }
                }
                for (Circle circle : snapshot.circles()) {
                    try (var query = connection.prepareStatement("INSERT INTO tc_game_proximity_circles"
                            + " (sessiondeviceid,circleid,expiresat,data) VALUES (?,?,?,?)")) {
                        query.setLong(1, sessionId);
                        query.setString(2, circle.id());
                        query.setLong(3, timestamp(circle.expiresAt()).toEpochMilli());
                        query.setString(4, objectMapper.writeValueAsString(circle));
                        query.executeUpdate();
                    }
                }
                connection.commit();
                return read(connection, sessionId, generation);
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public List<Change> reconcile() throws Exception {
        List<Change> changes = new ArrayList<>();
        try (var connection = dataSource.getConnection()) {
            List<Long> sessions = new ArrayList<>();
            try (var query = connection.prepareStatement("SELECT sessiondeviceid FROM tc_game_proximity_state");
                    var rows = query.executeQuery()) {
                while (rows.next()) {
                    sessions.add(rows.getLong(1));
                }
            }
            for (long sessionId : sessions) {
                connection.setAutoCommit(false);
                try {
                    String generation = generation(connection, sessionId, true);
                    Set<Long> users = new HashSet<>();
                    var participants = participants(connection, sessionId);
                    boolean changed = false;
                    for (String table : List.of("grants", "circles")) {
                        List<String> invalid = new ArrayList<>();
                        try (var query = connection.prepareStatement("SELECT data FROM tc_game_proximity_" + table
                                + " WHERE sessiondeviceid = ?")) {
                            query.setLong(1, sessionId);
                            try (var rows = query.executeQuery()) {
                                while (rows.next()) {
                                    String data = rows.getString(1);
                                    if (table.equals("grants")) {
                                        Grant grant = objectMapper.readValue(data, Grant.class);
                                        if (!timestamp(grant.expiresAt()).isAfter(Instant.now())
                                                || !validGrant(grant, participants)) {
                                            invalid.add(data);
                                            users.add(grant.recipientUserId());
                                        }
                                    } else {
                                        Circle circle = objectMapper.readValue(data, Circle.class);
                                        if (!timestamp(circle.expiresAt()).isAfter(Instant.now())
                                                || !validCircle(circle, participants)) {
                                            invalid.add(data);
                                        }
                                    }
                                }
                            }
                        }
                        for (String data : invalid) {
                            try (var query = connection.prepareStatement("DELETE FROM tc_game_proximity_" + table
                                    + " WHERE sessiondeviceid = ? AND data = ?")) {
                                query.setLong(1, sessionId);
                                query.setString(2, data);
                                changed |= query.executeUpdate() > 0;
                            }
                        }
                    }
                    try (var query = connection.prepareStatement("SELECT generation FROM tc_game_proximity_state"
                            + " WHERE sessiondeviceid = ?")) {
                        query.setLong(1, sessionId);
                        try (var rows = query.executeQuery()) {
                            if (rows.next() && !Objects.equals(generation, rows.getString(1))) {
                                for (Grant grant : read(connection, sessionId, rows.getString(1)).grants()) {
                                    users.add(grant.recipientUserId());
                                }
                                delete(connection, sessionId);
                                changed = true;
                            }
                        }
                    }
                    connection.commit();
                    if (changed) {
                        changes.add(new Change(sessionId, users));
                    }
                } catch (Exception error) {
                    connection.rollback();
                    throw error;
                }
            }
        }
        return changes;
    }
}
