package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.traccar.model.Position;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared set-up of the step-3 service tests of game data model 2: a database built from the real changelogs, every
 * service on one movable clock, a publisher that records what would be published after commit, and helpers that
 * create Traccar rows. Subclasses run on H2 and, when a test server is configured, on MySQL. The JVM runs in a
 * non-UTC zone, so a leaked time zone conversion shows.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class GameTestBase {

    protected static final Instant T0 = Instant.parse("2026-01-15T12:00:00Z");
    protected static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    /** A clock that tests move forward. */
    protected static final class TestClock extends Clock {
        private volatile Instant now = T0;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        public void set(Instant value) {
            now = value;
        }

        public void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    /** Records what the services publish after commit. */
    protected static final class RecordingPublisher implements GamePublisher {
        final List<Invalidation> invalidations = new CopyOnWriteArrayList<>();
        final List<Position> positions = new CopyOnWriteArrayList<>();
        final List<Long> devices = new CopyOnWriteArrayList<>();
        final List<Long> pushes = new CopyOnWriteArrayList<>();
        final List<Set<Long>> proximityAccess = new CopyOnWriteArrayList<>();
        final List<String> accountTopics = new CopyOnWriteArrayList<>();
        final List<Set<Long>> accounts = new CopyOnWriteArrayList<>();
        final List<String> operatorTopics = new CopyOnWriteArrayList<>();

        @Override
        public void positions(Collection<Long> received, List<Position> latest) {
            devices.addAll(received);
            positions.addAll(latest);
        }

        @Override
        public void invalidate(Invalidation invalidation) {
            invalidations.add(invalidation);
        }

        @Override
        public void pushes(List<Long> outboxIds) {
            pushes.addAll(outboxIds);
        }

        @Override
        public void proximityAccess(long gameSessionId, Set<Long> userIds) {
            proximityAccess.add(userIds);
        }

        @Override
        public void accounts(Set<Long> userIds, String topic) {
            accounts.add(userIds);
            accountTopics.add(topic);
        }

        @Override
        public void operators(String topic) {
            operatorTopics.add(topic);
        }

        void clear() {
            invalidations.clear();
            positions.clear();
            devices.clear();
            pushes.clear();
            proximityAccess.clear();
            accounts.clear();
            accountTopics.clear();
            operatorTopics.clear();
        }

        List<Invalidation> topic(String topic) {
            return invalidations.stream().filter(item -> item.topic().equals(topic)).toList();
        }
    }

    protected final ObjectMapper mapper = new ObjectMapper();
    protected final TestClock clock = new TestClock();
    protected final RecordingPublisher publisher = new RecordingPublisher();
    protected TimeZone zone;
    protected DataSource source;
    protected GameTransactions transactions;
    protected GameSessionService sessions;
    protected GameMemberService members;
    protected GameResourceService resources;

    /** A new database with the master changelog and model 2 applied. */
    protected abstract DataSource database() throws Exception;

    /** Binding hooks of the member service; none by default. */
    protected Set<GameBindingHook> hooks() {
        return Set.of();
    }

    /** Creates the services of the subclass after the shared ones exist. */
    protected abstract void createServices() throws Exception;

    @BeforeAll
    void createBase() throws Exception {
        zone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
        source = database();
        transactions = new GameTransactions(source, clock);
        sessions = new GameSessionService(transactions, mapper);
        members = new GameMemberService(transactions, hooks(), publisher);
        resources = new GameResourceService(transactions);
        createServices();
    }

    @AfterAll
    void restoreZone() {
        TimeZone.setDefault(zone);
    }

    protected GameCredentialCipher cipher() {
        return new GameCredentialCipher(KEY);
    }

    // Database helpers --------------------------------------------------------------------------------------

    private static void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            if (params[i] instanceof Instant instant) {
                GameTime.set(statement, i + 1, instant);
            } else {
                statement.setObject(i + 1, params[i]);
            }
        }
    }

    protected long insert(String sql, Object... params) throws SQLException {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, params);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    protected int execute(String sql, Object... params) throws SQLException {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        }
    }

    /** The first column of the first row, or -1 when it is NULL. */
    protected long number(String sql, Object... params) throws SQLException {
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

    protected String text(String sql, Object... params) throws SQLException {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), sql);
                return rows.getString(1);
            }
        }
    }

    protected List<String> strings(String sql, Object... params) throws SQLException {
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rows = statement.executeQuery()) {
                List<String> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(rows.getString(1));
                }
                return result;
            }
        }
    }

    protected static int status(Executable executable) {
        WebApplicationException error = assertThrows(WebApplicationException.class, executable);
        return error.getResponse().getStatus();
    }

    protected static void assertStatus(int expected, Executable executable) {
        assertEquals(expected, status(executable));
    }

    protected int user() throws SQLException {
        String name = "user-" + UUID.randomUUID();
        return (int) insert("INSERT INTO tc_users (name, email) VALUES (?, ?)", name, name);
    }

    protected int device() throws SQLException {
        return device("device-" + UUID.randomUUID());
    }

    protected int device(String name) throws SQLException {
        return (int) insert("INSERT INTO tc_devices (name, uniqueid) VALUES (?, ?)", name, UUID.randomUUID().toString());
    }

    protected int group() throws SQLException {
        return (int) insert("INSERT INTO tc_groups (name) VALUES (?)", "group-" + UUID.randomUUID());
    }

    protected JsonNode json(String value) throws Exception {
        return mapper.readTree(value);
    }

    protected long session() throws Exception {
        return session("{}");
    }

    /** A session whose settings document is {@code {"game_settings": gameSettings}}. */
    protected long session(String gameSettings) throws Exception {
        return sessions.create("session-" + UUID.randomUUID(), 5, 1,
                json("{\"game_settings\": " + gameSettings + "}")).id();
    }

    protected long revision(long session) throws Exception {
        return sessions.get(session).revision();
    }

    protected GameMember plan(long session, String role) throws Exception {
        return members.plan(session, role, null, role + " " + UUID.randomUUID().toString().substring(0, 8));
    }

    protected GameMember plan(long session, String role, String displayName) throws Exception {
        return members.plan(session, role, null, displayName);
    }

    /** A planned member bound to a new account and, except the spectator, a new tracker. */
    protected GameMember bound(long session, String role) throws Exception {
        GameMember member = plan(session, role);
        member = members.bindAccount(session, member.id(), user());
        if (!GameMember.SPECTATOR.equals(role)) {
            member = members.bindTracker(session, member.id(), device());
        }
        return member;
    }

    /** Makes the controller lease active, executing {@code session} under {@code epoch}. */
    protected void lease(long session, long epoch) throws SQLException {
        execute("DELETE FROM tc_game_controller WHERE id = 1");
        execute("INSERT INTO tc_game_controller (id, epoch, state, gamesessionid, acquiredat, renewedat, expiresat)"
                + " VALUES (1, ?, 'active', ?, ?, ?, ?)", epoch, session, T0, T0, T0.plus(Duration.ofDays(30)));
    }

    protected long memberRevision(long memberId) throws SQLException {
        return number("SELECT revision FROM tc_game_member_state WHERE memberid = ?", memberId);
    }

}
