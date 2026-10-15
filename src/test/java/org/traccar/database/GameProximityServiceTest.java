package org.traccar.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.model.Device;
import org.traccar.model.User;
import org.traccar.storage.DatabaseStorage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class GameProximityServiceTest {
    private JdbcDataSource source;
    private GameProximityService service;
    private DatabaseStorage storage;
    private final String generation = UUID.randomUUID().toString();

    @BeforeEach
    void setup() throws Exception {
        source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE tc_users(id INT PRIMARY KEY, attributes TEXT, administrator BOOLEAN, disabled BOOLEAN)");
            statement.execute("CREATE TABLE tc_devices(id INT PRIMARY KEY, attributes TEXT, groupid INT)");
            statement.execute("CREATE TABLE tc_user_device(userid INT, deviceid INT, UNIQUE(userid,deviceid))");
            statement.execute("CREATE TABLE tc_groups(id INT PRIMARY KEY, groupid INT)");
            statement.execute("CREATE TABLE tc_user_group(userid INT, groupid INT)");
            statement.execute("CREATE TABLE tc_game_proximity_state(sessiondeviceid INT PRIMARY KEY, generation VARCHAR(36), revision BIGINT, snapshot TEXT)");
            statement.execute("CREATE TABLE tc_game_proximity_grants(sessiondeviceid INT, userid INT, sourcedeviceid INT, deviceid INT, expiresat BIGINT, data TEXT, PRIMARY KEY(sessiondeviceid,userid,deviceid))");
            statement.execute("CREATE TABLE tc_game_proximity_circles(sessiondeviceid INT, circleid VARCHAR(64), expiresat BIGINT, data TEXT, PRIMARY KEY(sessiondeviceid,circleid))");
            statement.execute("INSERT INTO tc_devices VALUES(900,'{\"traccarToolGameState\":true,\""
                    + GameNotificationService.GENERATION_ATTRIBUTE + "\":\"" + generation + "\"}',0)");
            statement.execute("INSERT INTO tc_devices VALUES(11,'{\"traccarToolRole\":\"player\"}',0),(12,'{\"traccarToolRole\":\"supporter\"}',0)");
            for (int deviceId : List.of(11, 12)) {
                statement.execute("INSERT INTO tc_users VALUES(" + (deviceId + 100)
                        + ",' {\"traccarToolUserRole\":\"" + (deviceId == 11 ? "player" : "supporter")
                        + "\",\"traccarToolSessionDeviceId\":900,\"traccarToolAssignedDeviceId\":"
                        + deviceId + "}',FALSE,FALSE)");
                statement.execute("INSERT INTO tc_user_device VALUES(" + (deviceId + 100) + "," + deviceId + ")");
                statement.execute("INSERT INTO tc_user_device VALUES(" + (deviceId + 100) + ",900)");
            }
        }
        service = new GameProximityService(source, new ObjectMapper());
        storage = new DatabaseStorage(new Config(), source, new ObjectMapper());
    }

    private GameProximityService.Snapshot snapshot(long revision, Instant expiry) {
        String expiresAt = expiry.toString();
        return new GameProximityService.Snapshot(generation, revision,
                List.of(new GameProximityService.Grant("pair", 111, 11, 12, expiresAt),
                        new GameProximityService.Grant("pair", 112, 12, 11, expiresAt)),
                List.of(new GameProximityService.Circle("circle", 47, 8, 500, 1, 1, expiresAt, List.of(11L, 12L))));
    }

    private List<Long> devices(long userId, boolean directOnly) throws Exception {
        var condition = new Condition.Permission(User.class, userId, Device.class);
        return storage.getObjects(Device.class, new Request(new Columns.Include("id"),
                directOnly ? condition.excludeGroups() : condition)).stream().map(Device::getId).sorted().toList();
    }

    @Test
    void reciprocalReadAccessIsSeparateFromPermanentPermissions() throws Exception {
        assertEquals(List.of(11L, 900L), devices(111, false));
        var snapshot = snapshot(1, Instant.now().plusSeconds(120));
        service.replace(900, snapshot);
        assertEquals(List.of(11L, 12L, 900L), devices(111, false));
        assertEquals(List.of(11L, 900L), devices(111, true));
        assertEquals(1, service.circles(900).size());
        assertFalse(new ObjectMapper().writeValueAsString(service.circles(900)).contains("participantDeviceIds"));
        service.replace(900, new GameProximityService.Snapshot(generation, 2, List.of(), List.of()));
        assertEquals(List.of(11L, 900L), devices(111, false));
        assertEquals(List.of(12L, 900L), devices(112, false));
    }

    @Test
    void identicalWritesAreIdempotentAndObsoleteRevisionsAreRejected() throws Exception {
        var snapshot = snapshot(1, Instant.now().plusSeconds(120));
        assertEquals(service.replace(900, snapshot), service.replace(900, snapshot));
        assertEquals(409, assertThrows(WebApplicationException.class,
                () -> service.replace(900, snapshot(1, Instant.now().plusSeconds(60)))).getResponse().getStatus());
        var obsolete = new GameProximityService.Snapshot(UUID.randomUUID().toString(), 2, List.of(), List.of());
        assertEquals(409, assertThrows(WebApplicationException.class,
                () -> service.replace(900, obsolete)).getResponse().getStatus());
    }

    @Test
    void expiryAndRestartRemoveOnlyTemporaryAccess() throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO tc_groups VALUES(1,0)");
            statement.execute("UPDATE tc_devices SET groupid=1 WHERE id=12");
            statement.execute("INSERT INTO tc_user_group VALUES(111,1)");
        }
        service.replace(900, snapshot(1, Instant.now().minusSeconds(1)));
        assertTrue(service.get(900).grants().isEmpty());
        assertTrue(service.circles(900).isEmpty());
        service = new GameProximityService(source, new ObjectMapper());
        assertFalse(service.reconcile().isEmpty());
        assertEquals(List.of(11L, 12L, 900L), devices(111, false));
        assertEquals(List.of(12L, 900L), devices(112, false));
    }

    @Test
    void roleBindingAndSourcePermissionChangesInvalidateLeases() throws Exception {
        service.replace(900, snapshot(1, Instant.now().plusSeconds(120)));
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM tc_user_device WHERE userid=112");
        }
        assertFalse(service.reconcile().isEmpty());
        assertTrue(service.get(900).grants().isEmpty());
        assertTrue(service.circles(900).isEmpty());
        assertThrows(ForbiddenException.class, () -> service.replace(900, snapshot(2, Instant.now().plusSeconds(120))));
    }

    @Test
    void removingSessionMembershipRevokesBothDirectionsAndCircles() throws Exception {
        service.replace(900, snapshot(1, Instant.now().plusSeconds(120)));
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM tc_user_device WHERE userid=112 AND deviceid=900");
        }
        service.reconcile();
        assertEquals(List.of(11L, 900L), devices(111, false));
        assertEquals(List.of(12L), devices(112, false));
        assertTrue(service.get(900).grants().isEmpty());
        assertTrue(service.circles(900).isEmpty());
    }

    @Test
    void malformedAndNonReciprocalRequestsDoNotChangeState() throws Exception {
        var valid = snapshot(1, Instant.now().plusSeconds(120));
        assertThrows(BadRequestException.class, () -> service.replace(900,
                new GameProximityService.Snapshot(generation, 1, valid.grants().subList(0, 1), valid.circles())));
        assertThrows(BadRequestException.class, () -> service.replace(900,
                new GameProximityService.Snapshot(generation, 1, valid.grants(), List.of(
                        new GameProximityService.Circle("circle", 91, 8, 500, 1, 1,
                                Instant.now().plusSeconds(120).toString(), List.of(11L, 12L))))));
        assertEquals(0, service.get(900).revision());
    }
}
