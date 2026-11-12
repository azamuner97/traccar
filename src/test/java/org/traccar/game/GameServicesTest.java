package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The step-2 services of game data model 2 (sessions, members, resource rows, ownership) and their transaction
 * helper, on the real changelogs. Runs on H2 ({@link GameServicesH2Test}) and, when a test server is configured, on
 * MySQL ({@link GameServicesMySqlTest}). The JVM runs in a non-UTC zone, so a leaked time zone conversion shows.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class GameServicesTest {

    private static final Instant T0 = Instant.parse("2026-01-15T12:00:00Z");
    private static final String GRANT = "INSERT INTO tc_game_proximity_grants (gamesessionid, recipientmemberid,"
            + " targetmemberid, episodeid, userid, deviceid, expiresat) VALUES (?, ?, ?, 'episode', ?, ?, ?)";

    private final ObjectMapper mapper = new ObjectMapper();
    private TimeZone zone;
    private DataSource source;
    private GameTransactions transactions;
    private GameSessionService sessions;
    private GameMemberService members;
    private GameResourceService resources;
    private GameOwnershipService ownership;

    /** A new database with the master changelog and model 2 applied. */
    protected abstract DataSource database() throws Exception;

    @BeforeAll
    void createServices() throws Exception {
        zone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
        source = database();
        transactions = new GameTransactions(source);
        sessions = new GameSessionService(transactions, mapper);
        members = new GameMemberService(transactions);
        resources = new GameResourceService(transactions);
        ownership = new GameOwnershipService(transactions);
    }

    @AfterAll
    void restoreZone() {
        TimeZone.setDefault(zone);
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

    private long insert(String sql, Object... params) throws SQLException {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, params);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    private int execute(String sql, Object... params) throws SQLException {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        }
    }

    /** The first column of the first row, or -1 when it is NULL. */
    private long number(String sql, Object... params) throws SQLException {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), sql);
                long value = rows.getLong(1);
                return rows.wasNull() ? -1 : value;
            }
        }
    }

    private static int status(Executable executable) {
        WebApplicationException error = assertThrows(WebApplicationException.class, executable);
        return error.getResponse().getStatus();
    }

    private int user() throws SQLException {
        String name = "user-" + UUID.randomUUID();
        return (int) insert("INSERT INTO tc_users (name, email) VALUES (?, ?)", name, name);
    }

    private int device() throws SQLException {
        String name = "device-" + UUID.randomUUID();
        return (int) insert("INSERT INTO tc_devices (name, uniqueid) VALUES (?, ?)", name, name);
    }

    private int group() throws SQLException {
        return (int) insert("INSERT INTO tc_groups (name) VALUES (?)", "group-" + UUID.randomUUID());
    }

    private int geofence() throws SQLException {
        return (int) insert("INSERT INTO tc_geofences (name, area) VALUES (?, 'CIRCLE (47 8, 100)')",
                "geofence-" + UUID.randomUUID());
    }

    private JsonNode settings(String value) throws Exception {
        return mapper.readTree("{\"value\": \"" + value + "\"}");
    }

    private long session() throws Exception {
        return sessions.create("session-" + UUID.randomUUID(), 5, 1, settings("initial")).id();
    }

    private long revision(long session) throws Exception {
        return sessions.get(session).revision();
    }

    private GameMember plan(long session, String role) throws Exception {
        return members.plan(session, role, null, role + " " + UUID.randomUUID().toString().substring(0, 8));
    }

    private long grants(long session) throws SQLException {
        return number("SELECT COUNT(*) FROM tc_game_proximity_grants WHERE gamesessionid = ?", session);
    }

    // Transactions ------------------------------------------------------------------------------------------

    @Test
    void transactionsLockTheSessionThenMemberStatesInAscendingOrder() throws Exception {
        long session = session();
        long other = session();
        long first = plan(session, "player").id();
        long second = plan(session, "supporter").id();
        long hunter = plan(session, "hunter").id();

        assertThrows(IllegalStateException.class, () -> transactions.write(transaction -> {
            return transaction.lockMemberStates(List.of(first));
        }));
        assertThrows(IllegalStateException.class, () -> transactions.write(transaction -> {
            transaction.lockSession(session);
            transaction.lockMemberStates(List.of(second));
            return transaction.lockMemberStates(List.of(first));
        }));
        assertThrows(IllegalStateException.class, () -> transactions.write(transaction -> {
            transaction.lockSession(session);
            return transaction.lockSession(other);
        }));
        assertThrows(IllegalStateException.class, () -> transactions.write(transaction -> {
            transaction.lockSession(session);
            return transaction.bumpMemberState(first);
        }));
        assertThrows(IllegalStateException.class, () -> transactions.read(transaction -> {
            return transaction.lockSession(session);
        }));
        assertEquals(404, status(() -> transactions.write(transaction -> transaction.lockSession(-1))));

        Map<Long, Long> locked = transactions.write(transaction -> {
            transaction.lockSession(session);
            Map<Long, Long> result = transaction.lockMemberStates(List.of(hunter, second, first));
            assertEquals(result, transaction.lockMemberStates(List.of(first, second)));
            return result;
        });
        assertEquals(Map.of(first, 1L, second, 1L), locked);
    }

    @Test
    void revisionsAdvanceOncePerTransaction() throws Exception {
        long session = session();
        long player = plan(session, "player").id();
        long hunter = plan(session, "hunter").id();
        long before = revision(session);

        transactions.write(transaction -> {
            transaction.lockSession(session);
            transaction.lockMemberStates(List.of(player, hunter));
            assertEquals(2L, transaction.bumpMemberState(player));
            assertEquals(2L, transaction.bumpMemberState(player));
            assertNull(transaction.bumpMemberState(hunter));
            assertEquals(before + 1, transaction.bumpSession());
            assertEquals(before + 1, transaction.bumpSession());
            return null;
        });

        assertEquals(before + 1, revision(session));
        assertEquals(2L, members.get(session, player).stateRevision());
        assertNull(members.get(session, hunter).stateRevision());
    }

    @Test
    void constraintViolationsRollBackAnswer409AndPublishNothing() throws Exception {
        long session = session();
        String taken = sessions.get(session).name();
        long before = revision(session);
        List<String> published = new ArrayList<>();

        assertEquals(409, status(() -> transactions.write(transaction -> {
            transaction.lockSession(session);
            transaction.bumpSession();
            transaction.afterCommit(() -> published.add("rolled back"));
            return transaction.insert("INSERT INTO tc_game_sessions (name, schemaversion, revision, createdat,"
                    + " updatedat) VALUES (?, 1, 1, ?, ?)", taken, transaction.now(), transaction.now());
        })));
        assertEquals(before, revision(session));
        assertTrue(published.isEmpty());

        transactions.write(transaction -> {
            transaction.afterCommit(() -> published.add("committed"));
            assertTrue(published.isEmpty());
            return null;
        });
        assertEquals(List.of("committed"), published);
    }

    // Sessions ----------------------------------------------------------------------------------------------

    @Test
    void createsSessionsWithUniqueNamesAndUtcTimestamps() throws Exception {
        String name = "session-" + UUID.randomUUID();
        GameSessionService.Session created = sessions.create(name, 5, 2, settings("a"));
        assertEquals(name, created.name());
        assertEquals(5, created.schemaVersion());
        assertEquals(1, created.revision());
        assertEquals(2, created.settings().schemaVersion());
        assertEquals(1, created.settings().revision());
        assertEquals(settings("a"), created.settings().settings());
        assertTrue(Duration.between(created.createdAt(), Instant.now()).abs().toMinutes() < 1,
                () -> "Stored time shifted: " + created.createdAt());
        assertEquals(created, sessions.get(created.id()));
        assertTrue(sessions.list().stream().anyMatch(summary -> summary.id() == created.id()
                && summary.name().equals(name) && summary.settingsRevision() == 1));

        assertEquals(409, status(() -> sessions.create(name, 5, 1, settings("b"))));
        assertEquals(400, status(() -> sessions.create("a/b", 5, 1, settings("b"))));
        assertEquals(400, status(() -> sessions.create(" ", 5, 1, settings("b"))));
        assertEquals(400, status(() -> sessions.create("valid-" + UUID.randomUUID(), 0, 1, settings("b"))));
        assertEquals(400, status(() -> sessions.create("valid-" + UUID.randomUUID(), 5, 1,
                mapper.readTree("[]"))));
        assertEquals(404, status(() -> sessions.get(-1)));
    }

    @Test
    void renamesWithTheSettingsRevision() throws Exception {
        long session = session();
        String taken = sessions.get(session()).name();
        String name = "renamed-" + UUID.randomUUID();

        assertEquals(409, status(() -> sessions.rename(session, 2, name)));
        assertEquals(409, status(() -> sessions.rename(session, 1, taken)));
        GameSessionService.Session renamed = sessions.rename(session, 1, name);
        assertEquals(name, renamed.name());
        assertEquals(2, renamed.settings().revision());
        assertEquals(settings("initial"), renamed.settings().settings());
        assertEquals(2, renamed.revision());

        assertEquals(renamed, sessions.rename(session, 1, name));
        assertEquals(renamed, sessions.rename(session, 2, name));
        assertEquals(409, status(() -> sessions.rename(session, 1, "other-" + UUID.randomUUID())));
        assertEquals(404, status(() -> sessions.rename(-1, 1, name)));
    }

    @Test
    void updatesSettingsWithTheExpectedRevision() throws Exception {
        long session = session();

        GameSessionService.Session updated = sessions.updateSettings(session, 1, 3, settings("second"));
        assertEquals(2, updated.settings().revision());
        assertEquals(3, updated.settings().schemaVersion());
        assertEquals(settings("second"), updated.settings().settings());
        assertEquals(2, updated.revision());

        assertEquals(updated, sessions.updateSettings(session, 1, 3, settings("second")));
        assertEquals(409, status(() -> sessions.updateSettings(session, 1, 3, settings("third"))));
        assertEquals(409, status(() -> sessions.updateSettings(session, 5, 3, settings("third"))));
        assertEquals(400, status(() -> sessions.updateSettings(session, 2, 3, mapper.readTree("1"))));
        assertEquals(3, sessions.updateSettings(session, 2, 3, settings("third")).settings().revision());
        assertEquals(3, revision(session));
    }

    /** Fills every session-scoped table for a session and returns its controller history row. */
    private long populate(long session) throws Exception {
        int account = user();
        int hunterAccount = user();
        int tracker = device();
        long player = plan(session, "player").id();
        members.bindAccount(session, player, account);
        members.bindTracker(session, player, tracker);
        long supporter = plan(session, "supporter").id();
        long hunter = plan(session, "hunter").id();
        members.bindAccount(session, hunter, hunterAccount);
        plan(session, "spectator");
        resources.addMirror(session, device(), player, "hunter", null);
        resources.addGroup(session, group(), "player", null);
        resources.addGeofence(session, geofence(), "planned:" + UUID.randomUUID(), "planned");

        execute("INSERT INTO tc_game_session_checkpoints (gamesessionid, component, schemaversion, revision, document,"
                + " updatedat) VALUES (?, 'monitoring', 1, 1, '{}', ?)", session, T0);
        execute("INSERT INTO tc_game_session_credentials (gamesessionid, revision, ciphertext) VALUES (?, 1, 'x')",
                session);
        execute("INSERT INTO tc_game_session_logs (gamesessionid, category, occurredat, message)"
                + " VALUES (?, 'game', ?, 'message')", session, T0);
        execute("INSERT INTO tc_game_geofence_plans (gamesessionid, revision, data, updatedat) VALUES (?, 1, '{}', ?)",
                session, T0);
        execute("INSERT INTO tc_game_timetables (gamesessionid, revision, headers, source, updatedat)"
                + " VALUES (?, 1, '[]', '{}', ?)", session, T0);
        execute("INSERT INTO tc_game_timetable_entries (gamesessionid, memberid, scheduledat, content, active, revision,"
                + " status) VALUES (?, ?, ?, 'content', TRUE, 1, 'scheduled')", session, player, T0);
        long request = insert("INSERT INTO tc_game_requests (gamesessionid, type, requestermemberid, requesterrole,"
                + " targetmemberid, parameters, status, createdat, expiresat, updatedat)"
                + " VALUES (?, 'speedhunt', ?, 'hunter', ?, '{}', 'succeeded', ?, ?, ?)",
                session, hunter, player, T0, T0, T0);
        execute("INSERT INTO tc_game_jokers (gamesessionid, memberid, type, granted, used, updatedat)"
                + " VALUES (?, ?, 'break', 1, 1, ?)", session, supporter, T0);
        execute("INSERT INTO tc_game_joker_pools (gamesessionid, type, granted, used, updatedat)"
                + " VALUES (?, 'speedhunt', 3, 1, ?)", session, T0);
        String reservation = "INSERT INTO tc_game_joker_reservations (gamesessionid, memberid, type, requestid,"
                + " status, overallocated, createdat, updatedat) VALUES (?, ?, ?, ?, 'reserved', FALSE, ?, ?)";
        long fake = insert(reservation, session, player, "fakePing", request, T0, T0);
        long pool = insert(reservation, session, null, "speedhunt", request, T0, T0);
        long pause = insert(reservation, session, supporter, "break", null, T0, T0);
        execute("INSERT INTO tc_game_joker_ledger (gamesessionid, memberid, type, kind, quantity, requestid,"
                + " reservationid, occurredat) VALUES (?, NULL, 'speedhunt', 'use', 1, ?, ?, ?)",
                session, request, pool, T0);
        execute("INSERT INTO tc_game_catches (gamesessionid, memberid, caughtat, activememberid) VALUES (?, ?, ?, ?)",
                session, supporter, T0, supporter);
        long run = insert("INSERT INTO tc_game_speedhunts (gamesessionid, targetmemberid, tohunters, toplayers,"
                + " tosupporters, startedat, requestid, reservationid, activememberid)"
                + " VALUES (?, ?, TRUE, FALSE, FALSE, ?, ?, ?, ?)", session, player, T0, request, pool, player);
        execute("INSERT INTO tc_game_fake_ping_plans (reservationid, gamesessionid, memberid, latitude, longitude,"
                + " pingnumber, boundrunid) VALUES (?, ?, ?, 47.0, 8.0, 1, ?)", fake, session, player, run);
        long ping = insert("INSERT INTO tc_game_speedhunt_pings (gamesessionid, runid, number, mode, source, sentat,"
                + " requestid, fakeplanid) VALUES (?, ?, 1, 'live', 'jokerFake', ?, ?, ?)",
                session, run, T0, request, fake);
        execute("INSERT INTO tc_game_live_uploads (gamesessionid, purpose, sourcememberid, pingid, tohunters,"
                + " toplayers, tosupporters, intervalseconds, startedat, state, controllerepoch, requestid,"
                + " reservationid) VALUES (?, 'speedhuntPing', ?, ?, TRUE, FALSE, FALSE, 10, ?, 'active', 1, ?, ?)",
                session, player, ping, T0, request, pool);
        execute("INSERT INTO tc_game_breaks (gamesessionid, memberid, state, plannedstart, plannedby, reservationid)"
                + " VALUES (?, ?, 'planned', ?, 'participant', ?)", session, supporter, T0, pause);
        execute("INSERT INTO tc_game_notification_state (gamesessionid, generation, revision) VALUES (?, 'g1', 1)",
                session);
        execute("INSERT INTO tc_game_notifications (gamesessionid, generation, alertid, type, audience,"
                + " recipientmemberid, sourcememberid, occurredat, timezone, message, createdat)"
                + " VALUES (?, 'g1', 'a1', 'playerCaught', 'member', ?, ?, ?, 'UTC', 'message', ?)",
                session, hunter, supporter, T0, T0);
        execute("INSERT INTO tc_game_proximity_state (gamesessionid, revision, contenthash, updatedat)"
                + " VALUES (?, 1, 'hash', ?)", session, T0);
        execute(GRANT, session, hunter, player, hunterAccount, tracker, T0);
        execute("INSERT INTO tc_game_proximity_circles (gamesessionid, circleid, latitude, longitude, diametermeters,"
                + " expiresat) VALUES (?, 'c1', 47.0, 8.0, 100, ?)", session, T0);
        execute("INSERT INTO tc_game_proximity_circle_members (gamesessionid, circleid, memberid) VALUES (?, 'c1', ?)",
                session, player);
        long delivery = insert("INSERT INTO tc_game_deliveries (gamesessionid, deliveryid, epoch, requesthash,"
                + " outcome, createdat) VALUES (?, ?, 1, 'hash', '{}', ?)", session, UUID.randomUUID().toString(), T0);
        execute("INSERT INTO tc_game_push_outbox (deliveryrowid, gamesessionid, recipientmemberid, userid,"
                + " notificator, subject, message, status, createdat)"
                + " VALUES (?, ?, ?, ?, 'traccar', 'subject', 'message', 'queued', ?)",
                delivery, session, player, account, T0);
        execute("INSERT INTO tc_drawings (ownerid, gamesessionid, type, geometry, color)"
                + " VALUES (?, ?, 'line', 'LINESTRING (0 0, 1 1)', '#000000')", account, session);
        return insert("INSERT INTO tc_game_controller_history (epoch, event, gamesessionid, occurredat)"
                + " VALUES (1, 'acquired', ?, ?)", session, T0);
    }

    private long rows(String table, long session) throws SQLException {
        return number("SELECT COUNT(*) FROM " + table + " WHERE gamesessionid = ?", session);
    }

    @Test
    void deletesEveryRowOfAFullyPopulatedSession() throws Exception {
        long deleted = session();
        long kept = session();
        long history = populate(deleted);
        populate(kept);
        execute("UPDATE tc_game_controller SET gamesessionid = ? WHERE id = 1", deleted);
        for (String table : GameSessionRepository.DELETE_ORDER) {
            assertTrue(rows(table, deleted) > 0, table);
        }
        long users = number("SELECT COUNT(*) FROM tc_users");

        sessions.delete(deleted);

        for (String table : GameSessionRepository.DELETE_ORDER) {
            assertEquals(0, rows(table, deleted), table);
            assertTrue(rows(table, kept) > 0, table);
        }
        assertEquals(0, number("SELECT COUNT(*) FROM tc_game_sessions WHERE id = ?", deleted));
        assertEquals(0, rows("tc_drawings", deleted));
        assertEquals(1, rows("tc_drawings", kept));
        assertEquals(-1, number("SELECT gamesessionid FROM tc_game_controller WHERE id = 1"));
        assertEquals(-1, number("SELECT gamesessionid FROM tc_game_controller_history WHERE id = ?", history));
        assertEquals(users, number("SELECT COUNT(*) FROM tc_users"));
        assertEquals(404, status(() -> sessions.delete(deleted)));
        assertNotNull(sessions.get(kept));
    }

    @Test
    void theDeleteOrderCoversEverySessionScopedTable() throws Exception {
        Set<String> scoped = new TreeSet<>();
        try (Connection connection = source.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet columns = metadata.getColumns(connection.getCatalog(), connection.getSchema(), "%", "%")) {
                while (columns.next()) {
                    String table = columns.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                    if (columns.getString("COLUMN_NAME").equalsIgnoreCase("gamesessionid")) {
                        scoped.add(table);
                    }
                }
            }
        }
        // These follow their foreign keys: drawings cascade, the lease and controller history clear the reference.
        scoped.removeAll(Set.of("tc_drawings", "tc_game_controller", "tc_game_controller_history"));
        assertEquals(scoped, new TreeSet<>(GameSessionRepository.DELETE_ORDER));
        assertEquals(GameSessionRepository.DELETE_ORDER.size(),
                new HashSet<>(GameSessionRepository.DELETE_ORDER).size());
    }

    // Members -----------------------------------------------------------------------------------------------

    @Test
    void plansMembersWithNumbersThatAreNeverReused() throws Exception {
        long session = session();
        long before = revision(session);

        GameMember first = members.plan(session, "player", null, "Alice");
        assertEquals(1, first.number());
        assertEquals("Alice", first.displayName());
        assertEquals(GameMember.ACTIVE, first.state());
        assertNull(first.userId());
        assertNull(first.trackerId());
        assertEquals(1L, first.stateRevision());
        assertEquals(before + 1, revision(session));

        GameMember third = members.plan(session, "player", 3, "Carol");
        assertEquals(4, members.plan(session, "player", null, "Dave").number());
        assertEquals(1L, members.plan(session, "supporter", 3, "Supporter").stateRevision());
        assertNull(members.plan(session, "hunter", null, "Hunter").stateRevision());

        members.retire(session, third.id());
        assertEquals(409, status(() -> members.plan(session, "player", 3, "Carol again")));
        assertEquals(5, members.plan(session, "player", null, "Eve").number());
        assertEquals(400, status(() -> members.plan(session, "referee", null, "Referee")));
        assertEquals(400, status(() -> members.plan(session, "player", 0, "Zero")));
        assertEquals(400, status(() -> members.plan(session, "player", null, " ")));
        assertEquals(404, status(() -> members.plan(-1, "player", null, "Nobody")));
        assertEquals(6, members.list(session).size());
    }

    @Test
    void aSessionHasOneSpectatorWithoutTrackerThatIsNeverRetired() throws Exception {
        long session = session();
        assertEquals(400, status(() -> members.plan(session, "spectator", 2, "Spectator")));
        GameMember spectator = members.plan(session, "spectator", null, "Spectator");
        assertEquals(1, spectator.number());
        assertNull(spectator.stateRevision());
        assertEquals(409, status(() -> members.plan(session, "spectator", 1, "Second spectator")));
        assertEquals(409, status(() -> members.bindTracker(session, spectator.id(), device())));
        assertEquals(409, status(() -> members.retire(session, spectator.id())));
        assertEquals(409, status(() -> members.changeRole(session, spectator.id(), "player", null, "Player")));

        int account = user();
        assertEquals(Long.valueOf(account), members.bindAccount(session, spectator.id(), account).userId());
        assertNull(members.unbindAccount(session, spectator.id()).userId());
        assertEquals(GameMember.ACTIVE, members.get(session, spectator.id()).state());
    }

    @Test
    void anAccountJoinsOneSessionAndATrackerIsBoundOncePerSession() throws Exception {
        long first = session();
        long second = session();
        GameMember player = plan(first, "player");
        GameMember hunter = plan(first, "hunter");
        GameMember elsewhere = plan(second, "player");
        int account = user();
        int tracker = device();

        members.bindAccount(first, player.id(), account);
        members.bindTracker(first, player.id(), tracker);
        assertEquals(409, status(() -> members.bindAccount(second, elsewhere.id(), account)));
        assertEquals(409, status(() -> members.bindAccount(first, hunter.id(), account)));
        assertEquals(409, status(() -> members.bindTracker(first, hunter.id(), tracker)));
        assertEquals(Long.valueOf(tracker), members.bindTracker(second, elsewhere.id(), tracker).trackerId());

        assertEquals(400, status(() -> members.bindAccount(first, hunter.id(), -1)));
        assertEquals(400, status(() -> members.bindTracker(first, hunter.id(), -1)));
        assertEquals(404, status(() -> members.bindAccount(second, player.id(), user())));
        int mirror = device();
        resources.addMirror(first, mirror, player.id(), "hunter", null);
        assertEquals(409, status(() -> members.bindTracker(first, hunter.id(), mirror)));
        assertEquals(409, status(() -> members.bindTracker(second, elsewhere.id(), mirror)));

        members.unbindAccount(first, player.id());
        assertEquals(Long.valueOf(account), members.bindAccount(second, elsewhere.id(), account).userId());
    }

    @Test
    void rebindingRevokesGrantsAndAdvancesRevisions() throws Exception {
        long session = session();
        int firstAccount = user();
        int secondAccount = user();
        int hunterAccount = user();
        int firstTracker = device();
        int secondTracker = device();
        GameMember first = plan(session, "player");
        GameMember second = plan(session, "supporter");
        GameMember hunter = plan(session, "hunter");
        members.bindAccount(session, first.id(), firstAccount);
        members.bindTracker(session, first.id(), firstTracker);
        members.bindAccount(session, second.id(), secondAccount);
        members.bindTracker(session, second.id(), secondTracker);
        members.bindAccount(session, hunter.id(), hunterAccount);
        execute("INSERT INTO tc_game_proximity_state (gamesessionid, revision, contenthash, updatedat)"
                + " VALUES (?, 5, 'hash', ?)", session, T0);
        execute(GRANT, session, first.id(), second.id(), firstAccount, secondTracker, T0);
        execute(GRANT, session, second.id(), first.id(), secondAccount, firstTracker, T0);
        execute(GRANT, session, hunter.id(), second.id(), hunterAccount, secondTracker, T0);
        long sessionBefore = revision(session);
        long firstBefore = members.get(session, first.id()).stateRevision();
        long secondBefore = members.get(session, second.id()).stateRevision();

        int replacement = device();
        GameMember rebound = members.bindTracker(session, first.id(), replacement);
        assertEquals(Long.valueOf(replacement), rebound.trackerId());
        assertEquals(firstBefore + 1, rebound.stateRevision());
        assertEquals(secondBefore, members.get(session, second.id()).stateRevision());
        assertEquals(sessionBefore + 1, revision(session));
        assertEquals(1, grants(session));
        assertEquals(6, number("SELECT revision FROM tc_game_proximity_state WHERE gamesessionid = ?", session));

        assertEquals(rebound, members.bindTracker(session, first.id(), replacement));
        assertEquals(sessionBefore + 1, revision(session));

        GameMember unbound = members.unbindAccount(session, first.id());
        assertNull(unbound.userId());
        assertEquals(firstBefore + 2, unbound.stateRevision());
        assertEquals(sessionBefore + 2, revision(session));
        assertEquals(6, number("SELECT revision FROM tc_game_proximity_state WHERE gamesessionid = ?", session));

        GameMember hunterRebound = members.bindAccount(session, hunter.id(), user());
        assertNull(hunterRebound.stateRevision());
        assertEquals(0, grants(session));
        assertEquals(sessionBefore + 3, revision(session));
        assertEquals(7, number("SELECT revision FROM tc_game_proximity_state WHERE gamesessionid = ?", session));
    }

    @Test
    void bindingChangesTellTheSessionAndBothAccountsAfterCommit() throws Exception {
        var publisher = new GameTestBase.RecordingPublisher();
        var published = new GameMemberService(transactions, Set.of(), publisher);
        var settings = new GameSessionService(transactions, mapper, publisher);
        long session = session();
        int oldAccount = user();
        int newAccount = user();
        int tracker = device();
        GameMember player = published.plan(session, "player", null, "Player");
        GameMember supporter = published.plan(session, "supporter", null, "Supporter");
        published.bindAccount(session, player.id(), oldAccount);
        published.bindTracker(session, player.id(), tracker);
        published.bindAccount(session, supporter.id(), user());
        published.bindTracker(session, supporter.id(), device());
        execute(GRANT, session, supporter.id(), player.id(), number(
                "SELECT userid FROM tc_game_session_members WHERE id = ?", supporter.id()), tracker, T0.plusSeconds(
                3600));
        long supporterAccount = number("SELECT userid FROM tc_game_session_members WHERE id = ?", supporter.id());
        publisher.clear();

        // Rebinding the account: the session's members, the account it removed and the one it bound read their
        // status again; the revoked grant's recipient has its device access recomputed.
        published.bindAccount(session, player.id(), newAccount);
        assertEquals(List.of(new GamePublisher.Invalidation(session, GamePublisher.STATUS, null)),
                publisher.topic(GamePublisher.STATUS));
        assertEquals(List.of(Set.of((long) oldAccount, (long) newAccount)), publisher.accounts);
        assertEquals(List.of(GamePublisher.STATUS), publisher.accountTopics);
        assertEquals(List.of(Set.of(supporterAccount)), publisher.proximityAccess);
        assertEquals(1, publisher.topic(GamePublisher.PROXIMITY).size());

        // A failed binding publishes nothing.
        publisher.clear();
        assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> published.bindAccount(session, player.id(), supporterAccount));
        assertTrue(publisher.invalidations.isEmpty());
        assertTrue(publisher.accounts.isEmpty());

        // Retiring tells the account it no longer takes part; settings change everybody's status.
        published.retire(session, player.id());
        assertEquals(List.of(Set.of((long) newAccount)), publisher.accounts);
        publisher.clear();
        settings.updateSettings(session, 1, 1, mapper.readTree("{\"game_settings\": {}}"));
        assertEquals(List.of(new GamePublisher.Invalidation(session, GamePublisher.STATUS, null)),
                publisher.topic(GamePublisher.STATUS));

        // Deleting the session tells its remaining accounts.
        publisher.clear();
        settings.delete(session);
        assertEquals(List.of(Set.of(supporterAccount)), publisher.accounts);
        assertEquals(List.of(GameSessionService.SESSIONS_INVALIDATION), publisher.operatorTopics);
    }

    @Test
    void bindingHooksRunInsideTheBindingTransaction() throws Exception {
        long session = session();
        int account = user();
        int tracker = device();
        GameMember player = plan(session, "player");
        GameMember target = plan(session, "player");
        members.bindAccount(session, player.id(), account);
        members.bindTracker(session, target.id(), tracker);
        execute(GRANT, session, player.id(), target.id(), account, tracker, T0);
        long before = revision(session);
        Long state = members.get(session, player.id()).stateRevision();

        List<String> seen = new ArrayList<>();
        GameMemberService recording = new GameMemberService(transactions, Set.of((transaction, old, current) -> {
            seen.add(old.userId() + " -> " + current.userId());
            assertEquals(0, transaction.first("SELECT COUNT(*) FROM tc_game_proximity_grants WHERE gamesessionid = ?",
                    rows -> rows.getLong(1), session).orElseThrow());
        }));
        GameMemberService failing = new GameMemberService(transactions, Set.of((transaction, old, current) -> {
            throw new IllegalStateException("Hook failed");
        }));

        assertThrows(IllegalStateException.class, () -> failing.unbindAccount(session, player.id()));
        assertEquals(Long.valueOf(account), members.get(session, player.id()).userId());
        assertEquals(1, grants(session));
        assertEquals(before, revision(session));
        assertEquals(state, members.get(session, player.id()).stateRevision());

        recording.unbindAccount(session, player.id());
        assertEquals(List.of(account + " -> null"), seen);
        recording.retire(session, target.id());
        assertEquals(List.of(account + " -> null", "null -> null"), seen);
        assertEquals(before + 2, revision(session));
    }

    @Test
    void retiringClearsBindingsAndKeepsTheMember() throws Exception {
        long session = session();
        int account = user();
        int tracker = device();
        GameMember player = plan(session, "player");
        GameMember other = plan(session, "player");
        members.bindAccount(session, player.id(), account);
        members.bindTracker(session, player.id(), tracker);
        execute(GRANT, session, other.id(), player.id(), user(), tracker, T0);
        long before = revision(session);
        long state = members.get(session, player.id()).stateRevision();

        GameMember retired = members.retire(session, player.id());
        assertEquals(GameMember.RETIRED, retired.state());
        assertNotNull(retired.retiredAt());
        assertNull(retired.userId());
        assertNull(retired.trackerId());
        assertEquals(state + 1, retired.stateRevision());
        assertEquals(0, grants(session));
        assertEquals(before + 1, revision(session));

        assertEquals(retired, members.retire(session, player.id()));
        assertEquals(before + 1, revision(session));
        assertEquals(409, status(() -> members.bindAccount(session, player.id(), account)));
        assertEquals(409, status(() -> members.bindTracker(session, player.id(), tracker)));
        assertEquals(409, status(() -> members.rename(session, player.id(), "New name")));
        assertEquals(Long.valueOf(account), members.bindAccount(session, other.id(), account).userId());
        assertEquals(Long.valueOf(tracker), members.bindTracker(session, other.id(), tracker).trackerId());
        assertEquals(retired, members.get(session, player.id()));
    }

    @Test
    void aRoleChangeRetiresTheMemberAndCreatesANewOne() throws Exception {
        long session = session();
        int account = user();
        int tracker = device();
        GameMember player = plan(session, "player");
        members.bindAccount(session, player.id(), account);
        members.bindTracker(session, player.id(), tracker);
        long before = revision(session);

        GameMember supporter = members.changeRole(session, player.id(), "supporter", null, "Supporter");
        assertEquals("supporter", supporter.role());
        assertEquals(1, supporter.number());
        assertEquals(Long.valueOf(account), supporter.userId());
        assertEquals(Long.valueOf(tracker), supporter.trackerId());
        assertEquals(before + 1, revision(session));
        GameMember old = members.get(session, player.id());
        assertEquals(GameMember.RETIRED, old.state());
        assertEquals("player", old.role());
        assertEquals(409, status(() -> members.changeRole(session, player.id(), "hunter", null, "Hunter")));
        assertEquals(409, status(() -> members.changeRole(session, supporter.id(), "supporter", null, "Same")));

        GameMember spectator = members.changeRole(session, supporter.id(), "spectator", null, "Spectator");
        assertEquals(Long.valueOf(account), spectator.userId());
        assertNull(spectator.trackerId());
        assertEquals(spectator.id(), members.membership(account).memberId());
    }

    @Test
    void renamesDisplayNames() throws Exception {
        long session = session();
        GameMember member = plan(session, "hunter");
        long before = revision(session);
        assertEquals("Renamed", members.rename(session, member.id(), "Renamed").displayName());
        assertEquals(before + 1, revision(session));
        members.rename(session, member.id(), "Renamed");
        assertEquals(before + 1, revision(session));
        assertEquals(400, status(() -> members.rename(session, member.id(), "x".repeat(129))));
        assertEquals(404, status(() -> members.rename(session, -1, "Nobody")));
    }

    @Test
    void looksUpMembershipByAccount() throws Exception {
        long session = session();
        int account = user();
        int tracker = device();
        GameMember hunter = members.plan(session, "hunter", 2, "Hunter Two");
        members.bindAccount(session, hunter.id(), account);
        members.bindTracker(session, hunter.id(), tracker);

        assertEquals(new GameMemberService.Membership(session, hunter.id(), "hunter", 2, (long) tracker, "Hunter Two"),
                members.membership(account));
        int stranger = user();
        assertTrue(members.find(stranger).isEmpty());
        assertThrows(ForbiddenException.class, () -> members.membership(stranger));

        members.retire(session, hunter.id());
        assertTrue(members.find(account).isEmpty());
    }

    // Resource rows -----------------------------------------------------------------------------------------

    @Test
    void mirrorsFollowTheMirroringTopology() throws Exception {
        long session = session();
        long other = session();
        GameMember player = plan(session, "player");
        GameMember supporter = plan(session, "supporter");
        GameMember hunter = plan(session, "hunter");
        GameMember spectator = plan(session, "spectator");
        GameMember foreign = plan(other, "player");
        long before = revision(session);

        GameResource.Mirror toHunters = resources.addMirror(session, device(), player.id(), "hunter", null);
        assertEquals(GameResource.ACTIVE, toHunters.state());
        assertEquals("hunter", toHunters.destinationRole());
        assertEquals(before + 1, revision(session));
        GameResource.Mirror toPlayer = resources.addMirror(session, device(), hunter.id(), null, player.id());
        assertEquals(player.id(), toPlayer.destinationMemberId());
        resources.addMirror(session, device(), supporter.id(), "supporter", null);
        resources.addMirror(session, device(), hunter.id(), null, supporter.id());

        assertEquals(400, status(() -> resources.addMirror(session, device(), player.id(), null, null)));
        assertEquals(400, status(() -> resources.addMirror(session, device(), player.id(), "hunter", hunter.id())));
        assertEquals(400, status(() -> resources.addMirror(session, device(), player.id(), "spectator", null)));
        assertEquals(400, status(() -> resources.addMirror(session, device(), player.id(), null, supporter.id())));
        assertEquals(400, status(() -> resources.addMirror(session, device(), hunter.id(), "player", null)));
        assertEquals(400, status(() -> resources.addMirror(session, device(), hunter.id(), null, spectator.id())));
        assertEquals(400, status(() -> resources.addMirror(session, device(), spectator.id(), "hunter", null)));
        assertEquals(400, status(() -> resources.addMirror(session, device(), foreign.id(), "hunter", null)));
        assertEquals(400, status(() -> resources.addMirror(session, -1, supporter.id(), "hunter", null)));
        assertEquals(409, status(() -> resources.addMirror(session, device(), player.id(), "hunter", null)));
        assertEquals(409, status(() -> resources.addMirror(other, toHunters.deviceId(), foreign.id(), "hunter",
                null)));
        int tracker = device();
        members.bindTracker(other, foreign.id(), tracker);
        assertEquals(409, status(() -> resources.addMirror(session, tracker, supporter.id(), "hunter", null)));
        members.retire(session, supporter.id());
        assertEquals(409, status(() -> resources.addMirror(session, device(), supporter.id(), "player", null)));
        assertEquals(4, resources.mirrors(session).size());
    }

    @Test
    void groupPurposesAreValidatedAndUniquePerSession() throws Exception {
        long session = session();
        long other = session();
        GameMember player = plan(session, "player");
        GameMember supporter = plan(session, "supporter");
        GameMember hunter = plan(session, "hunter");

        for (String purpose : GameResourceService.SESSION_GROUP_PURPOSES) {
            GameResource.Group added = resources.addGroup(session, group(), purpose, null);
            assertEquals(purpose, added.purpose());
            assertNull(added.memberId());
        }
        assertEquals(409, status(() -> resources.addGroup(session, group(), "player", null)));
        assertEquals(409, status(() -> resources.addGroup(session, group(), "supporterToSupporter", null)));
        resources.addGroup(other, group(), "player", null);

        // The unique constraint cannot see duplicates without a member, so the service has to.
        execute("INSERT INTO tc_game_session_groups (gamesessionid, purpose, state) VALUES (?, 'player', 'active')",
                other);

        GameResource.Group perPlayer = resources.addGroup(session, group(), "hunterToPlayer", player.id());
        assertEquals(player.id(), perPlayer.memberId());
        resources.addGroup(session, group(), "hunterToSupporter", supporter.id());
        assertEquals(409, status(() -> resources.addGroup(session, group(), "hunterToPlayer", player.id())));
        assertEquals(400, status(() -> resources.addGroup(session, group(), "hunterToPlayer", supporter.id())));
        assertEquals(400, status(() -> resources.addGroup(session, group(), "hunterToPlayer", hunter.id())));
        assertEquals(400, status(() -> resources.addGroup(session, group(), "hunterToPlayer", null)));
        assertEquals(400, status(() -> resources.addGroup(session, group(), "player", player.id())));
        assertEquals(400, status(() -> resources.addGroup(session, group(), "referees", null)));
        assertEquals(400, status(() -> resources.addGroup(session, -1, "hunterToPlayer", plan(session, "player")
                .id())));
        assertEquals(409, status(() -> resources.addGroup(session, perPlayer.groupId().intValue(), "hunterToPlayer",
                plan(session, "player").id())));
        GameMember retired = plan(session, "player");
        members.retire(session, retired.id());
        assertEquals(409, status(() -> resources.addGroup(session, group(), "hunterToPlayer", retired.id())));
        assertEquals(GameResourceService.SESSION_GROUP_PURPOSES.size() + 2, resources.groups(session).size());
    }

    @Test
    void geofencesHaveUniquePlanKeys() throws Exception {
        long session = session();
        long other = session();
        int managed = geofence();

        GameResource.Geofence added = resources.addGeofence(session, managed, "configured:area", "configured");
        assertEquals("configured:area", added.planKey());
        assertEquals("configured", added.purpose());
        resources.addGeofence(other, geofence(), "configured:area", "configured");
        assertEquals(409, status(() -> resources.addGeofence(session, geofence(), "configured:area", "planned")));
        assertEquals(409, status(() -> resources.addGeofence(other, managed, "planned:1", "planned")));
        assertEquals(400, status(() -> resources.addGeofence(session, geofence(), "planned:2", "forbidden")));
        assertEquals(400, status(() -> resources.addGeofence(session, geofence(), " ", "planned")));
        assertEquals(400, status(() -> resources.addGeofence(session, -1, "planned:3", "planned")));
        assertEquals(List.of(added), resources.geofences(session));
    }

    @Test
    void resourceRowsRetireAndAreRemovedAfterCleanup() throws Exception {
        long session = session();
        GameMember player = plan(session, "player");
        int deletedGroup = group();
        List<GameResource> rows = List.of(
                resources.addGroup(session, deletedGroup, "hunter", null),
                resources.addMirror(session, device(), player.id(), "hunter", null),
                resources.addGeofence(session, geofence(), "planned:key", "planned"));

        for (GameResource row : rows) {
            long before = revision(session);
            assertEquals(409, status(() -> resources.remove(row.kind(), session, row.id())), row.toString());
            assertEquals(GameResource.RETIRING, resources.retire(row.kind(), session, row.id()).state());
            assertEquals(before + 1, revision(session));
            resources.retire(row.kind(), session, row.id());
            assertEquals(before + 1, revision(session));
            assertEquals(409, status(() -> resources.rebind(row.kind(), session, row.id(), 1)));
            assertEquals(row, resources.reactivate(row.kind(), session, row.id()));
            resources.reactivate(row.kind(), session, row.id());
            assertEquals(before + 2, revision(session));
            resources.retire(row.kind(), session, row.id());
            assertEquals(404, status(() -> resources.retire(row.kind(), session + 1_000_000, row.id())));
        }

        // A retiring row keeps its place until cleanup removes it.
        assertEquals(409, status(() -> resources.addGroup(session, group(), "hunter", null)));
        assertEquals(409, status(() -> resources.addMirror(session, device(), player.id(), "hunter", null)));
        assertEquals(409, status(() -> resources.addGeofence(session, geofence(), "planned:key", "planned")));

        execute("DELETE FROM tc_groups WHERE id = ?", deletedGroup);
        assertEquals(409, status(() -> resources.reactivate(GameResource.Kind.GROUP, session, rows.get(0).id())));
        for (GameResource row : rows) {
            resources.remove(row.kind(), session, row.id());
            assertEquals(404, status(() -> resources.remove(row.kind(), session, row.id())));
        }
        assertTrue(resources.groups(session).isEmpty());
        assertTrue(resources.mirrors(session).isEmpty());
        assertTrue(resources.geofences(session).isEmpty());

        GameResource.Group group = resources.addGroup(session, group(), "hunter", null);
        GameResource.Mirror mirror = resources.addMirror(session, device(), player.id(), "hunter", null);
        resources.addGeofence(session, geofence(), "planned:key", "planned");

        int replacement = group();
        long before = revision(session);
        assertEquals(Long.valueOf(replacement),
                resources.rebind(GameResource.Kind.GROUP, session, group.id(), replacement).resourceId());
        assertEquals(before + 1, revision(session));
        resources.rebind(GameResource.Kind.GROUP, session, group.id(), replacement);
        assertEquals(before + 1, revision(session));
        int tracker = device();
        members.bindTracker(session, player.id(), tracker);
        assertEquals(409, status(() -> resources.rebind(GameResource.Kind.MIRROR, session, mirror.id(), tracker)));
        assertEquals(409, status(() -> resources.rebind(GameResource.Kind.GROUP, session, group.id(),
                resources.addGroup(session, group(), "player", null).groupId())));
        assertEquals(400, status(() -> resources.rebind(GameResource.Kind.GEOFENCE, session,
                resources.geofences(session).get(0).id(), -1)));

        members.retire(session, player.id());
        resources.retire(GameResource.Kind.MIRROR, session, mirror.id());
        assertEquals(409, status(() -> resources.reactivate(GameResource.Kind.MIRROR, session, mirror.id())));
    }

    // Ownership ---------------------------------------------------------------------------------------------

    @Test
    void ownershipCoversBindingsAndResourceRowsIncludingRetiringOnes() throws Exception {
        long first = session();
        long second = session();
        int account = user();
        int tracker = device();
        int mirrorDevice = device();
        int ownedGroup = group();
        int ownedGeofence = geofence();
        GameMember player = plan(first, "player");
        GameMember elsewhere = plan(second, "player");
        members.bindAccount(first, player.id(), account);
        members.bindTracker(first, player.id(), tracker);
        members.bindTracker(second, elsewhere.id(), tracker);
        GameResource.Mirror mirror = resources.addMirror(first, mirrorDevice, player.id(), "hunter", null);
        GameResource.Group group = resources.addGroup(first, ownedGroup, "player", null);
        resources.addGeofence(first, ownedGeofence, "configured:area", "configured");
        resources.retire(GameResource.Kind.GROUP, first, group.id());
        resources.retire(GameResource.Kind.MIRROR, first, mirror.id());
        int unrelated = device();

        GameOwnershipService.Owned owned = ownership.owned();
        assertTrue(owned.any());
        assertTrue(owned.devices().containsAll(Set.of((long) tracker, (long) mirrorDevice)));
        assertFalse(owned.devices().contains((long) unrelated));
        assertTrue(owned.users().contains((long) account));
        assertTrue(owned.groups().contains((long) ownedGroup));
        assertTrue(owned.geofences().contains((long) ownedGeofence));

        resources.remove(GameResource.Kind.GROUP, first, group.id());
        members.retire(first, player.id());
        owned = ownership.owned();
        assertFalse(owned.groups().contains((long) ownedGroup));
        assertFalse(owned.users().contains((long) account));
        assertTrue(owned.devices().contains((long) tracker));
        assertTrue(owned.devices().contains((long) mirrorDevice));

        members.unbindTracker(second, elsewhere.id());
        execute("DELETE FROM tc_geofences WHERE id = ?", ownedGeofence);
        owned = ownership.owned();
        assertFalse(owned.devices().contains((long) tracker));
        assertFalse(owned.geofences().contains((long) ownedGeofence));
        assertEquals(1, resources.geofences(first).size());
    }

}
