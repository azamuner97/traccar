package org.traccar.game;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rules of game data model 2 that the database itself enforces (changelog-game-model-2.xml). Every rule is
 * checked on H2 ({@link GameSchemaH2Test}) and, when a test server is configured, on MySQL
 * ({@link GameSchemaMySqlTest}), because the two engines differ on CHECK constraints.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class GameSchemaTest {

    private static final Instant T0 = Instant.parse("2026-01-15T12:00:00Z");
    private static final List<String> ACTIVE_TABLES = List.of(
            "tc_game_sessions", "tc_game_session_settings", "tc_game_session_credentials",
            "tc_game_session_checkpoints", "tc_game_session_logs", "tc_game_controller_history",
            "tc_game_session_members", "tc_game_mirrors", "tc_game_session_groups", "tc_game_geofence_plans",
            "tc_game_session_geofences", "tc_game_timetables", "tc_game_timetable_entries", "tc_game_requests",
            "tc_game_jokers", "tc_game_joker_pools", "tc_game_joker_reservations", "tc_game_joker_ledger",
            "tc_game_member_state", "tc_game_catches", "tc_game_speedhunts", "tc_game_fake_ping_plans",
            "tc_game_speedhunt_pings", "tc_game_live_uploads", "tc_game_breaks", "tc_game_notification_state",
            "tc_game_notifications", "tc_game_proximity_state", "tc_game_proximity_grants",
            "tc_game_proximity_circles", "tc_game_proximity_circle_members", "tc_game_deliveries",
            "tc_game_push_outbox", "tc_game_controller");

    private DataSource source;

    /** A new, empty database with the master changelog applied, and model 2 on top when asked. */
    protected abstract DataSource database(boolean model2) throws Exception;

    @BeforeAll
    void createDatabase() throws Exception {
        source = database(true);
    }

    // Helpers -----------------------------------------------------------------------------------------------

    private static void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            if (params[i] instanceof Instant instant) {
                GameTime.set(statement, i + 1, instant);
            } else {
                statement.setObject(i + 1, params[i]);
            }
        }
    }

    private static long insert(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, params);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    private static int execute(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        }
    }

    private static long number(Connection connection, String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), sql);
                long value = rows.getLong(1);
                return rows.wasNull() ? -1 : value;
            }
        }
    }

    private static boolean constraintViolation(SQLException e) {
        String state = e.getSQLState();
        // SQLState class 23 covers unique, foreign key and (H2) check violations; MySQL reports a violated
        // CHECK constraint as error 3819.
        return state != null && state.startsWith("23") || e.getErrorCode() == 3819;
    }

    private static void rejected(Connection connection, String sql, Object... params) {
        SQLException e = assertThrows(SQLException.class, () -> execute(connection, sql, params), sql);
        assertTrue(constraintViolation(e), () -> "Expected a constraint violation, got: " + e);
    }

    private static int user(Connection connection) throws SQLException {
        String name = "user-" + UUID.randomUUID();
        return (int) insert(connection, "INSERT INTO tc_users (name, email) VALUES (?, ?)", name, name);
    }

    private static int device(Connection connection) throws SQLException {
        String name = "device-" + UUID.randomUUID();
        return (int) insert(connection, "INSERT INTO tc_devices (name, uniqueid) VALUES (?, ?)", name, name);
    }

    private static long session(Connection connection) throws SQLException {
        return insert(connection, "INSERT INTO tc_game_sessions (name, schemaversion, revision, createdat, updatedat)"
                + " VALUES (?, 1, 0, ?, ?)", "session-" + UUID.randomUUID(), T0, T0);
    }

    private static long member(Connection connection, long session, String role, int number, Integer user,
                               Integer tracker) throws SQLException {
        return insert(connection, "INSERT INTO tc_game_session_members"
                + " (gamesessionid, role, number, displayname, userid, trackerid, state, createdat)"
                + " VALUES (?, ?, ?, ?, ?, ?, 'active', ?)",
                session, role, number, role + " " + number, user, tracker, T0);
    }

    private static long player(Connection connection, long session, int number) throws SQLException {
        return member(connection, session, "player", number, null, null);
    }

    private static final String CATCH = "INSERT INTO tc_game_catches"
            + " (gamesessionid, memberid, caughtat, releasedat, activememberid) VALUES (?, ?, ?, ?, ?)";
    private static final String RUN = "INSERT INTO tc_game_speedhunts (gamesessionid, targetmemberid, tohunters,"
            + " toplayers, tosupporters, startedat, endedat, outcome, activememberid)"
            + " VALUES (?, ?, TRUE, FALSE, FALSE, ?, ?, ?, ?)";
    private static final String PING = "INSERT INTO tc_game_speedhunt_pings"
            + " (gamesessionid, runid, number, mode, source, sentat, fakeplanid) VALUES (?, ?, ?, ?, ?, ?, ?)";
    private static final String RESERVATION = "INSERT INTO tc_game_joker_reservations"
            + " (gamesessionid, memberid, type, status, overallocated, createdat, updatedat)"
            + " VALUES (?, ?, ?, 'reserved', FALSE, ?, ?)";
    private static final String ENTRY = "INSERT INTO tc_game_timetable_entries"
            + " (gamesessionid, memberid, scheduledat, content, active, revision, status)"
            + " VALUES (?, ?, ?, '', TRUE, 1, 'scheduled')";
    private static final String FAKE_PLAN = "INSERT INTO tc_game_fake_ping_plans (reservationid, gamesessionid,"
            + " memberid, latitude, longitude, timetableentryid, pingnumber, boundrunid, openentryid, unboundmemberid)"
            + " VALUES (?, ?, ?, 47.0, 8.0, ?, ?, ?, ?, ?)";
    private static final String BREAK = "INSERT INTO tc_game_breaks (gamesessionid, memberid, state, plannedstart,"
            + " startedat, deadline, endedat, plannedby, activememberid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String MIRROR = "INSERT INTO tc_game_mirrors"
            + " (gamesessionid, deviceid, sourcememberid, destinationrole, destinationmemberid, state)"
            + " VALUES (?, ?, ?, ?, ?, 'active')";
    private static final String LIVE = "INSERT INTO tc_game_live_uploads (gamesessionid, purpose, sourcememberid,"
            + " recipientmemberid, pingid, tohunters, toplayers, tosupporters, intervalseconds, startedat, state,"
            + " controllerepoch) VALUES (?, ?, ?, ?, ?, TRUE, FALSE, FALSE, 10, ?, 'active', 1)";

    private long reservation(Connection connection, long session, Long member, String type) throws SQLException {
        return insert(connection, RESERVATION, session, member, type, T0, T0);
    }

    // Structure ---------------------------------------------------------------------------------------------

    @Test
    void createsTheModel2Tables() throws Exception {
        try (Connection connection = source.getConnection()) {
            for (String table : ACTIVE_TABLES) {
                assertTrue(number(connection, "SELECT COUNT(*) FROM " + table) >= 0, table);
            }
        }
    }

    @Test
    void timestampsKeepTheUtcWallClockInAnyJvmZone() throws Exception {
        TimeZone previous = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        try (Connection connection = source.getConnection()) {
            Instant value = Instant.parse("2026-01-15T12:34:56.789987Z");
            long session = insert(connection, "INSERT INTO tc_game_sessions"
                    + " (name, schemaversion, revision, createdat, updatedat) VALUES (?, 1, 0, ?, ?)",
                    "time-" + UUID.randomUUID(), value, value);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT createdat FROM tc_game_sessions WHERE id = ?")) {
                query.setLong(1, session);
                try (ResultSet rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(Instant.parse("2026-01-15T12:34:56.789Z"), GameTime.get(rows, "createdat"));
                    assertTrue(rows.getString("createdat").startsWith("2026-01-15 12:34:56.789"),
                            rows.getString("createdat"));
                }
            }
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    // Members -----------------------------------------------------------------------------------------------

    @Test
    void referencesNeverCrossSessions() throws Exception {
        try (Connection connection = source.getConnection()) {
            long first = session(connection);
            long second = session(connection);
            long member = player(connection, first, 1);
            rejected(connection, CATCH, second, member, T0, null, member);
            rejected(connection, MIRROR, second, null, member, "hunter", null);
            insert(connection, CATCH, first, member, T0, null, member);
        }
    }

    @Test
    void memberNumbersAreUniqueAndNeverReused() throws Exception {
        try (Connection connection = source.getConnection()) {
            long session = session(connection);
            long member = player(connection, session, 3);
            execute(connection, "UPDATE tc_game_session_members SET state = 'retired', retiredat = ? WHERE id = ?",
                    T0, member);
            rejected(connection, "INSERT INTO tc_game_session_members"
                    + " (gamesessionid, role, number, displayname, state, createdat)"
                    + " VALUES (?, 'player', 3, 'New player 3', 'active', ?)", session, T0);
            long supporter = member(connection, session, "supporter", 3, null, null);
            rejected(connection, "UPDATE tc_game_session_members SET state = 'retired' WHERE id = ?", supporter);
            rejected(connection, "INSERT INTO tc_game_session_members"
                    + " (gamesessionid, role, number, displayname, state, createdat)"
                    + " VALUES (?, 'referee', 1, 'Referee', 'active', ?)", session, T0);
            rejected(connection, "INSERT INTO tc_game_session_members"
                    + " (gamesessionid, role, number, displayname, state, createdat)"
                    + " VALUES (?, 'player', 0, 'Player 0', 'active', ?)", session, T0);
        }
    }

    @Test
    void aSessionHasOneSpectatorNumberedOne() throws Exception {
        try (Connection connection = source.getConnection()) {
            long session = session(connection);
            rejected(connection, "INSERT INTO tc_game_session_members"
                    + " (gamesessionid, role, number, displayname, state, createdat)"
                    + " VALUES (?, 'spectator', 2, 'Spectator', 'active', ?)", session, T0);
            member(connection, session, "spectator", 1, null, null);
            rejected(connection, "INSERT INTO tc_game_session_members"
                    + " (gamesessionid, role, number, displayname, state, createdat)"
                    + " VALUES (?, 'spectator', 1, 'Spectator', 'active', ?)", session, T0);
        }
    }

    @Test
    void anAccountJoinsOneSessionAndATrackerOncePerSession() throws Exception {
        try (Connection connection = source.getConnection()) {
            long first = session(connection);
            long second = session(connection);
            int account = user(connection);
            int tracker = device(connection);
            member(connection, first, "player", 1, account, tracker);
            rejected(connection, "INSERT INTO tc_game_session_members"
                    + " (gamesessionid, role, number, displayname, userid, state, createdat)"
                    + " VALUES (?, 'player', 1, 'Player 1', ?, 'active', ?)", second, account, T0);
            rejected(connection, "INSERT INTO tc_game_session_members"
                    + " (gamesessionid, role, number, displayname, trackerid, state, createdat)"
                    + " VALUES (?, 'player', 2, 'Player 2', ?, 'active', ?)", first, tracker, T0);
            member(connection, second, "player", 1, null, tracker);
        }
    }

    @Test
    void deletingTraccarAccountsOrTrackersKeepsTheMember() throws Exception {
        try (Connection connection = source.getConnection()) {
            long session = session(connection);
            int account = user(connection);
            int tracker = device(connection);
            long member = member(connection, session, "player", 1, account, tracker);
            execute(connection, "DELETE FROM tc_users WHERE id = ?", account);
            execute(connection, "DELETE FROM tc_devices WHERE id = ?", tracker);
            assertEquals(-1, number(connection, "SELECT userid FROM tc_game_session_members WHERE id = ?", member));
            assertEquals(-1, number(connection, "SELECT trackerid FROM tc_game_session_members WHERE id = ?", member));
        }
    }

    @Test
    void mirrorsHaveExactlyOneDestination() throws Exception {
        try (Connection connection = source.getConnection()) {
            long session = session(connection);
            long source = player(connection, session, 1);
            long hunter = member(connection, session, "hunter", 1, null, null);
            rejected(connection, MIRROR, session, null, source, null, null);
            rejected(connection, MIRROR, session, null, source, "hunter", hunter);
            rejected(connection, MIRROR, session, null, source, "spectator", null);
            insert(connection, MIRROR, session, device(connection), source, "hunter", null);
            insert(connection, MIRROR, session, device(connection), hunter, null, source);
            rejected(connection, MIRROR, session, device(connection), source, "hunter", null);
        }
    }

    // Game state --------------------------------------------------------------------------------------------

    @Test
    void aMemberHasOneOpenCatch() throws Exception {
        try (Connection connection = source.getConnection()) {
            long session = session(connection);
            long member = player(connection, session, 1);
            long other = player(connection, session, 2);
            long caught = insert(connection, CATCH, session, member, T0, null, member);
            rejected(connection, CATCH, session, member, T0.plusSeconds(60), null, member);
            rejected(connection, CATCH, session, other, T0, null, member);
            rejected(connection, CATCH, session, other, T0, null, null);
            rejected(connection, "UPDATE tc_game_catches SET releasedat = ? WHERE id = ?", T0.plusSeconds(60), caught);
            execute(connection, "UPDATE tc_game_catches SET releasedat = ?, activememberid = NULL WHERE id = ?",
                    T0.plusSeconds(60), caught);
            insert(connection, CATCH, session, member, T0.plusSeconds(120), null, member);
            rejected(connection, CATCH, session, other, T0, T0.minusSeconds(1), null);
        }
    }

    @Test
    void aTargetHasOneRunningSpeedhuntWithUniquePingNumbers() throws Exception {
        try (Connection connection = source.getConnection()) {
            long session = session(connection);
            long target = player(connection, session, 1);
            long run = insert(connection, RUN, session, target, T0, null, null, target);
            rejected(connection, RUN, session, target, T0, null, null, target);
            rejected(connection, RUN, session, target, T0, T0, null, null);
            insert(connection, PING, session, run, 1, "static", "real", T0, null);
            rejected(connection, PING, session, run, 1, "live", "real", T0, null);
            rejected(connection, PING, session, run, 0, "static", "real", T0, null);
            rejected(connection, PING, session, run, 2, "static", "jokerFake", T0, null);
            rejected(connection, PING, session, run, 2, "radio", "real", T0, null);
            execute(connection, "UPDATE tc_game_speedhunts SET endedat = ?, outcome = 'completed',"
                    + " activememberid = NULL WHERE id = ?", T0.plusSeconds(60), run);
            insert(connection, RUN, session, target, T0.plusSeconds(120), null, null, target);
        }
    }

    @Test
    void aMemberHasOneActiveBreakWithAConsistentLifecycle() throws Exception {
        try (Connection connection = source.getConnection()) {
            long session = session(connection);
            long member = player(connection, session, 1);
            Instant end = T0.plusSeconds(600);
            insert(connection, BREAK, session, member, "active", null, T0, end, null, "participant", member);
            rejected(connection, BREAK, session, member, "active", null, T0, end, null, "participant", member);
            insert(connection, BREAK, session, member, "planned", end, null, null, null, "operator", null);
            rejected(connection, BREAK, session, member, "planned", null, null, null, null, "operator", null);
            rejected(connection, BREAK, session, member, "ended", null, T0, null, end, "participant", null);
            rejected(connection, BREAK, session, member, "paused", end, null, null, null, "participant", null);
            rejected(connection, BREAK, session, member, "planned", end, null, null, null, "hunter", null);
            insert(connection, BREAK, session, member, "cancelled", end, null, null, T0, "participant", null);
        }
    }

    @Test
    void fakePlansTargetOnePingAndHoldItAlone() throws Exception {
        try (Connection connection = source.getConnection()) {
            long session = session(connection);
            long member = player(connection, session, 1);
            long entry = insert(connection, ENTRY, session, member, T0);
            long first = reservation(connection, session, member, "fakePing");
            long second = reservation(connection, session, member, "fakePing");
            long third = reservation(connection, session, member, "fakePing");
            rejected(connection, FAKE_PLAN, first, session, member, entry, 1, null, null, null);
            rejected(connection, FAKE_PLAN, first, session, member, null, null, null, null, null);
            rejected(connection, FAKE_PLAN, first, session, member, null, 0, null, null, null);
            insert(connection, FAKE_PLAN, first, session, member, entry, null, null, entry, null);
            rejected(connection, FAKE_PLAN, second, session, member, entry, null, null, entry, null);
            insert(connection, FAKE_PLAN, second, session, member, null, 2, null, null, member);
            rejected(connection, FAKE_PLAN, third, session, member, null, 1, null, null, member);
            long run = insert(connection, RUN, session, member, T0, null, null, member);
            rejected(connection, "UPDATE tc_game_fake_ping_plans SET boundrunid = ? WHERE reservationid = ?",
                    run, second);
            execute(connection, "UPDATE tc_game_fake_ping_plans SET boundrunid = ?, unboundmemberid = NULL"
                    + " WHERE reservationid = ?", run, second);
            insert(connection, FAKE_PLAN, third, session, member, null, 1, null, null, member);
            insert(connection, PING, session, run, 2, "static", "jokerFake", T0, second);
            rejected(connection, PING, session, run, 3, "static", "real", T0, second);
        }
    }

    @Test
    void poolJokersBelongToTheSessionAndMemberJokersToAMember() throws Exception {
        try (Connection connection = source.getConnection()) {
            long session = session(connection);
            long member = player(connection, session, 1);
            reservation(connection, session, null, "speedhunt");
            reservation(connection, session, member, "break");
            rejected(connection, RESERVATION, session, member, "speedhunt", T0, T0);
            rejected(connection, RESERVATION, session, null, "break", T0, T0);
            rejected(connection, "INSERT INTO tc_game_jokers (gamesessionid, memberid, type, granted, used, updatedat)"
                    + " VALUES (?, ?, 'speedhunt', 1, 0, ?)", session, member, T0);
            rejected(connection, "INSERT INTO tc_game_jokers (gamesessionid, memberid, type, granted, used, updatedat)"
                    + " VALUES (?, ?, 'break', -1, 0, ?)", session, member, T0);
            insert(connection, "INSERT INTO tc_game_joker_pools (gamesessionid, type, granted, used, updatedat)"
                    + " VALUES (?, 'speedhunt', 3, 0, ?)", session, T0);
            rejected(connection, "INSERT INTO tc_game_joker_pools (gamesessionid, type, granted, used, updatedat)"
                    + " VALUES (?, 'speedhunt', 1, 0, ?)", session, T0);
            rejected(connection, "INSERT INTO tc_game_joker_pools (gamesessionid, type, granted, used, updatedat)"
                    + " VALUES (?, 'break', 1, 0, ?)", session, T0);
            rejected(connection, "INSERT INTO tc_game_joker_ledger"
                    + " (gamesessionid, memberid, type, kind, quantity, occurredat)"
                    + " VALUES (?, ?, 'speedhunt', 'grant', 1, ?)", session, member, T0);
        }
    }

    @Test
    void liveUploadsMatchTheirPurpose() throws Exception {
        try (Connection connection = source.getConnection()) {
            long session = session(connection);
            long member = player(connection, session, 1);
            insert(connection, LIVE, session, "speedhuntPing", member, null, null, T0);
            insert(connection, LIVE, session, "hunterRelay", null, member, null, T0);
            rejected(connection, LIVE, session, "hunterRelay", member, null, null, T0);
            rejected(connection, LIVE, session, "manual", null, null, null, T0);
            long run = insert(connection, RUN, session, member, T0, null, null, member);
            long ping = insert(connection, PING, session, run, 1, "live", "real", T0, null);
            insert(connection, LIVE, session, "speedhuntPing", member, null, ping, T0);
            rejected(connection, LIVE, session, "geofenceAuto", member, null, ping, T0);
        }
    }

    // Access and deletion -----------------------------------------------------------------------------------

    @Test
    void proximityGrantsGoWithTheirAccountOrTracker() throws Exception {
        try (Connection connection = source.getConnection()) {
            long session = session(connection);
            int account = user(connection);
            int tracker = device(connection);
            long recipient = member(connection, session, "player", 1, account, null);
            long target = member(connection, session, "player", 2, null, tracker);
            String grant = "INSERT INTO tc_game_proximity_grants (gamesessionid, recipientmemberid, targetmemberid,"
                    + " episodeid, userid, deviceid, expiresat) VALUES (?, ?, ?, 'episode', ?, ?, ?)";
            insert(connection, grant, session, recipient, target, account, tracker, T0);
            rejected(connection, grant, session, recipient, target, account, tracker, T0);
            execute(connection, "DELETE FROM tc_users WHERE id = ?", account);
            assertEquals(0, number(connection,
                    "SELECT COUNT(*) FROM tc_game_proximity_grants WHERE gamesessionid = ?", session));
        }
    }

    @Test
    void sessionsAreDeletedChildrenFirst() throws Exception {
        try (Connection connection = source.getConnection()) {
            long session = session(connection);
            long member = player(connection, session, 1);
            int owner = user(connection);
            insert(connection, "INSERT INTO tc_drawings (ownerid, gamesessionid, type, geometry, color)"
                    + " VALUES (?, ?, 'line', 'LINESTRING (0 0, 1 1)', '#000000')", owner, session);
            rejected(connection, "DELETE FROM tc_game_sessions WHERE id = ?", session);
            execute(connection, "DELETE FROM tc_game_session_members WHERE id = ?", member);
            execute(connection, "DELETE FROM tc_game_sessions WHERE id = ?", session);
            assertEquals(0, number(connection, "SELECT COUNT(*) FROM tc_drawings WHERE ownerid = ?", owner));
        }
    }

    // Model 1 -----------------------------------------------------------------------------------------------

    @Test
    void model2DropsModel1GameDataAndKeepsTraccarData() throws Exception {
        DataSource database = database(false);
        int account;
        int tracker;
        try (Connection connection = database.getConnection()) {
            Timestamp now = Timestamp.from(T0);
            tracker = device(connection);
            account = user(connection);
            long model1 = insert(connection, "INSERT INTO tc_game_sessions (name, schemaversion, sessiondeviceid,"
                    + " configrevision, config, resourcesrevision, resources, runtimerevision, runtime, createdat,"
                    + " updatedat) VALUES ('Model 1', 1, ?, 1, '{}', 1, '{}', 1, '{}', ?, ?)", tracker, now, now);
            execute(connection, "INSERT INTO tc_game_session_members"
                    + " (gamesessionid, userid, role, number, login) VALUES (?, ?, 'player', 1, 'p1')", model1, account);
            insert(connection, "INSERT INTO tc_game_requests (gamesessionid, type, requesterrole, parameters, status,"
                    + " createdat, expiresat, updatedat) VALUES (?, 'jokerBreak', 'player', '{}', 'succeeded', ?, ?, ?)",
                    model1, now, now, now);
            long delivery = insert(connection, "INSERT INTO tc_game_deliveries"
                    + " (gamesessionid, deliveryid, epoch, requesthash, outcome, createdat)"
                    + " VALUES (?, 'd1', 7, 'hash', '{}', ?)", model1, now);
            insert(connection, "INSERT INTO tc_game_push_outbox (deliveryrowid, gamesessionid, notificator, subject,"
                    + " message, status, createdat) VALUES (?, ?, 'traccar', 'Subject', 'Message', 'queued', ?)",
                    delivery, model1, now);
            execute(connection, "UPDATE tc_game_controller SET epoch = 7, gamesessionid = ? WHERE id = 1", model1);
            insert(connection, "INSERT INTO tc_drawings (ownerid, sessiondeviceid, type, geometry, color)"
                    + " VALUES (?, ?, 'line', 'LINESTRING (0 0, 1 1)', '#000000')", account, tracker);
            insert(connection, "INSERT INTO tc_drawings (ownerid, type, geometry, color)"
                    + " VALUES (?, 'line', 'LINESTRING (0 0, 2 2)', '#000000')", account);
        }

        // The switchover: the master changelog runs only its model-2 part on a model-1 database.
        GameSchemaFixture.apply(database, GameSchemaFixture.MASTER);

        try (Connection connection = database.getConnection()) {
            for (String table : ACTIVE_TABLES) {
                if (!table.equals("tc_game_controller")) {
                    assertEquals(0, number(connection, "SELECT COUNT(*) FROM " + table), table);
                }
            }
            assertThrows(SQLException.class, () -> number(connection, "SELECT COUNT(*) FROM tc_game_session_status"));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM tc_users WHERE id = ?", account));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM tc_devices WHERE id = ?", tracker));
            assertEquals(7, number(connection, "SELECT epoch FROM tc_game_controller WHERE id = 1"));
            assertEquals(-1, number(connection, "SELECT gamesessionid FROM tc_game_controller WHERE id = 1"));
            assertEquals(1, number(connection, "SELECT COUNT(*) FROM tc_drawings WHERE ownerid = ?", account));
            assertThrows(SQLException.class,
                    () -> number(connection, "SELECT COUNT(sessiondeviceid) FROM tc_drawings"));

            long session = session(connection);
            execute(connection, "UPDATE tc_game_controller SET gamesessionid = ? WHERE id = 1", session);
        }
    }

}
