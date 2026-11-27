package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        final List<GameEvent> events = new CopyOnWriteArrayList<>();

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

        @Override
        public void gameEvents(List<GameEvent> published) {
            events.addAll(published);
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
            events.clear();
        }

        List<Invalidation> topic(String topic) {
            return invalidations.stream().filter(item -> item.topic().equals(topic)).toList();
        }
    }

    /**
     * The game execution thread on virtual time: tasks run when a test calls {@link #runPending} or
     * {@link #advance}, which also moves the clock.
     */
    protected static final class ManualScheduler extends GameScheduler {
        private record Task(long at, long sequence, Runnable runnable, long period, boolean[] cancelled) {
        }

        private final TestClock clock;
        private final java.util.PriorityQueue<Task> tasks = new java.util.PriorityQueue<>(
                java.util.Comparator.comparingLong(Task::at).thenComparingLong(Task::sequence));
        private long sequence;

        public ManualScheduler(TestClock clock) {
            this.clock = clock;
        }

        private long now() {
            return clock.instant().toEpochMilli();
        }

        private synchronized Handle add(Runnable runnable, long delay, long period) {
            boolean[] cancelled = {false};
            tasks.add(new Task(now() + Math.max(0, delay), sequence++, runnable, period, cancelled));
            return () -> cancelled[0] = true;
        }

        @Override
        public void execute(Runnable task) {
            add(task, 0, 0);
        }

        @Override
        public Handle schedule(Runnable task, long delayMillis) {
            return add(task, delayMillis, 0);
        }

        @Override
        public Handle repeat(Runnable task, long delayMillis, long periodMillis) {
            return add(task, delayMillis, periodMillis);
        }

        private synchronized Task next(long until) {
            while (!tasks.isEmpty() && tasks.peek().cancelled()[0]) {
                tasks.poll();
            }
            return !tasks.isEmpty() && tasks.peek().at() <= until ? tasks.poll() : null;
        }

        /** Runs every task due now, including the ones they schedule for now. */
        public void runPending() {
            run(now());
        }

        private void run(long until) {
            for (Task task = next(until); task != null; task = next(until)) {
                if (task.at() > now()) {
                    clock.set(Instant.ofEpochMilli(task.at()));
                }
                if (task.period() > 0) {
                    synchronized (this) {
                        tasks.add(new Task(task.at() + task.period(), sequence++, task.runnable(), task.period(),
                                task.cancelled()));
                    }
                }
                task.runnable().run();
            }
        }

        /** Moves the clock forward, running each task at its time. */
        public void advance(Duration duration) {
            long target = now() + duration.toMillis();
            run(target);
            clock.set(Instant.ofEpochMilli(target));
        }

        /** Tasks still pending, cancelled ones excluded. */
        public synchronized int pending() {
            return (int) tasks.stream().filter(task -> !task.cancelled()[0]).count();
        }

        @Override
        public synchronized void shutdown() {
            tasks.clear();
        }
    }

    protected final ObjectMapper mapper = new ObjectMapper();
    protected final TestClock clock = new TestClock();
    protected final RecordingPublisher publisher = new RecordingPublisher();
    protected final ManualScheduler scheduler = new ManualScheduler(clock);
    protected final GamePositionWaiter waiter = new GamePositionWaiter();
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

    /** Every test starts at {@link #T0}: the clock is shared by the tests of a class. */
    @AfterEach
    void resetClock() {
        clock.set(T0);
    }

    @AfterAll
    void restoreZone() {
        TimeZone.setDefault(zone);
    }

    /** The ping engine on the test clock, the manual scheduler and the test waiter. */
    protected GamePingEngine engine() {
        return new GamePingEngine(transactions, mapper, publisher, waiter, scheduler);
    }

    /** Effects deliveries with the test engine. */
    protected GameEffectsService effectsService() {
        return new GameEffectsService(transactions, mapper, publisher, engine());
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

    /** Makes the controller lease active under {@code epoch}, and {@code session}'s game the running one. */
    protected void lease(long session, long epoch) throws SQLException {
        execute("DELETE FROM tc_game_controller WHERE id = 1");
        execute("INSERT INTO tc_game_controller (id, epoch, state, acquiredat, renewedat, expiresat)"
                + " VALUES (1, ?, 'active', ?, ?, ?)", epoch, T0, T0, T0.plus(Duration.ofDays(30)));
        startGame(session);
    }

    /**
     * Makes {@code session}'s game the running one since now, stopping any other, without the effects of a stop.
     */
    protected void startGame(long session) throws SQLException {
        execute("UPDATE tc_game_session_state SET state = 'stopped', runningkey = NULL WHERE runningkey = 1");
        if (execute("UPDATE tc_game_session_state SET state = 'running', runningkey = 1, changedat = ?"
                + " WHERE gamesessionid = ?", clock.instant(), session) == 0) {
            execute("INSERT INTO tc_game_session_state (gamesessionid, state, changedat, runningkey)"
                    + " VALUES (?, 'running', ?, 1)", session, clock.instant());
        }
    }

    /**
     * Runs the server's break timers once for {@code session}, as an execution tick does while its game runs: ends
     * breaks past their deadline, starts, waits or misses planned breaks at their start ({@link GameBreakRunner}).
     */
    protected void breakTick(long session) throws Exception {
        GameBreakRunner runner = new GameBreakRunner(transactions, publisher);
        for (GameBreakRunner.Work work : runner.due(session, clock.instant())) {
            runner.run(session, work);
        }
    }

    /** Marks {@code session}'s game stopped, without the effects of a stop. */
    protected void stopGame(long session) throws SQLException {
        execute("UPDATE tc_game_session_state SET state = 'stopped', runningkey = NULL WHERE gamesessionid = ?",
                session);
    }

    // Timetables --------------------------------------------------------------------------------------------

    /** A timetable row to add: its time and each member's cell. */
    protected record Row(Instant at, Map<Long, String> cells) {
    }

    /** A row with cells given as (member, content) pairs. */
    protected static Row row(Instant at, Object... cells) {
        Map<Long, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < cells.length; i += 2) {
            map.put(((GameMember) cells[i]).id(), (String) cells[i + 1]);
        }
        return new Row(at, map);
    }

    /**
     * Adds the columns that are not listed yet and the rows (keys {@code r0}, {@code r1}, …) with one changeset;
     * returns the slot ID of each row.
     */
    protected List<Long> timetable(GameTimetableService service, long session, List<GameMember> columns,
                                   Row... rows) throws Exception {
        var current = service.get(session);
        List<GameTimetableService.Change> changes = new ArrayList<>();
        for (GameMember column : columns) {
            if (current.participants().stream().noneMatch(item -> item.memberId() == column.id())) {
                changes.add(new GameTimetableService.Change(GameTimetableService.ADD_PARTICIPANT, null, null, null,
                        null, column.id(), null, null, null));
            }
        }
        for (int i = 0; i < rows.length; i++) {
            Map<String, String> cells = new java.util.LinkedHashMap<>();
            rows[i].cells().forEach((member, content) -> cells.put(Long.toString(member), content));
            changes.add(new GameTimetableService.Change(GameTimetableService.ADD_SLOT, "r" + i, null,
                    rows[i].at().toString(), cells, null, null, null, null));
        }
        var result = service.changes(session, new GameTimetableService.ChangeSet(
                "change-" + UUID.randomUUID(), current.revision(), changes));
        List<Long> slots = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            slots.add(result.slots().get("r" + i));
        }
        return slots;
    }

    /** The entry of a member in a slot. */
    protected GameTimetableService.Entry entry(GameTimetableService service, long session, long slotId,
                                               long memberId) throws Exception {
        return service.get(session).slots().stream().filter(slot -> slot.id() == slotId).findFirst().orElseThrow()
                .entries().stream().filter(item -> item.memberId() == memberId).findFirst().orElseThrow();
    }

    /** Stores a real tracker position and makes it the device's latest, as Traccar's storage would. */
    protected long trackerPosition(long deviceId, double latitude, double longitude, Instant fixTime)
            throws SQLException {
        java.sql.Timestamp fix = new java.sql.Timestamp(fixTime.toEpochMilli());
        long id;
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement("INSERT INTO tc_positions (protocol,"
                     + " deviceid, servertime, devicetime, fixtime, valid, latitude, longitude, altitude, speed,"
                     + " course, attributes, accuracy) VALUES ('osmand', ?, ?, ?, ?, TRUE, ?, ?, 0, 0, 0, '{}', 0)",
                     Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, deviceId);
            statement.setTimestamp(2, fix);
            statement.setTimestamp(3, fix);
            statement.setTimestamp(4, fix);
            statement.setDouble(5, latitude);
            statement.setDouble(6, longitude);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                id = keys.getLong(1);
            }
        }
        execute("UPDATE tc_devices SET positionid = ? WHERE id = ?", id, deviceId);
        return id;
    }

    /** A processed tracker position as {@code PostProcessHandler} hands it to observers. */
    protected static Position arriving(long deviceId, double latitude, double longitude, Instant fixTime) {
        Position position = new Position("osmand");
        position.setDeviceId(deviceId);
        position.setValid(true);
        position.setLatitude(latitude);
        position.setLongitude(longitude);
        position.setFixTime(java.util.Date.from(fixTime));
        position.setDeviceTime(java.util.Date.from(fixTime));
        position.setServerTime(java.util.Date.from(fixTime));
        return position;
    }

    /** Records an open catch of a member directly. */
    protected void caught(long session, GameMember member) throws SQLException {
        execute("INSERT INTO tc_game_catches (gamesessionid, memberid, caughtat, activememberid)"
                + " VALUES (?, ?, ?, ?)", session, member.id(), clock.instant(), member.id());
    }

    /** Starts a member's break directly, until {@code deadline}. */
    protected long onBreak(long session, GameMember member, Instant deadline) throws SQLException {
        return insert("INSERT INTO tc_game_breaks (gamesessionid, memberid, state, startedat, deadline, plannedby,"
                + " activememberid) VALUES (?, ?, 'active', ?, ?, 'participant', ?)", session, member.id(),
                clock.instant(), deadline, member.id());
    }

    /** Grants and arms a fake-ping joker of a member on a timetable entry, with its ledger; returns the reservation. */
    protected long armFake(long session, GameMember member, long entryId, double latitude, double longitude)
            throws Exception {
        return transactions.write(transaction -> {
            transaction.lockSession(session);
            int[] balance = GameJokerRepository.balance(transaction, session, member.id(),
                    GameJokerRepository.FAKE_PING);
            GameJokerRepository.store(transaction, session, member.id(), GameJokerRepository.FAKE_PING,
                    balance[0] + 1, balance[1]);
            GameJokerRepository.ledger(transaction, session, member.id(), GameJokerRepository.FAKE_PING,
                    GameJokerRepository.GRANT, 1, null, null, "Test", null);
            long reservation = GameJokerRepository.reserve(transaction, session, member.id(),
                    GameJokerRepository.FAKE_PING, null, false, "Test", null);
            GameJokerRepository.insertPlan(transaction, session, reservation, member.id(), latitude, longitude,
                    entryId, null, null);
            return reservation;
        });
    }

    protected String reservationStatus(long reservation) throws SQLException {
        return text("SELECT status FROM tc_game_joker_reservations WHERE id = ?", reservation);
    }

    protected record Discrepancy(Long memberId, String type, String field, long stored, long expected) {
    }

    /**
     * Checks that every joker balance equals the sum of its ledger entries (granted = grants − revocations, used =
     * uses + uses beyond the allocation) and that every use has its consumed reservation. Empty when consistent.
     */
    protected List<Discrepancy> reconcile(long session) throws Exception {
        return transactions.read(transaction -> {
            record Key(Long memberId, String type) {
            }
            Map<Key, long[]> stored = new HashMap<>();
            transaction.list("SELECT memberid, type, granted, used FROM tc_game_jokers WHERE gamesessionid = ?"
                    + " UNION ALL SELECT NULL, type, granted, used FROM tc_game_joker_pools WHERE gamesessionid = ?",
                    rows -> stored.put(new Key(GameTransaction.longOrNull(rows, "memberid"), rows.getString("type")),
                            new long[] {rows.getLong("granted"), rows.getLong("used")}), session, session);
            Map<Key, long[]> ledger = new HashMap<>();
            transaction.list("SELECT memberid, type, kind, SUM(quantity) AS total FROM tc_game_joker_ledger"
                    + " WHERE gamesessionid = ? GROUP BY memberid, type, kind", rows -> {
                        long[] sums = ledger.computeIfAbsent(new Key(GameTransaction.longOrNull(rows, "memberid"),
                                rows.getString("type")), key -> new long[2]);
                        long total = rows.getLong("total");
                        switch (rows.getString("kind")) {
                            case GameJokerRepository.GRANT -> sums[0] += total;
                            case GameJokerRepository.REVOKE -> sums[0] -= total;
                            case GameJokerRepository.USE, GameJokerRepository.OVERUSE -> sums[1] += total;
                            default -> {
                            }
                        }
                        return null;
                    }, session);
            Map<Key, Long> consumed = new HashMap<>();
            transaction.list("SELECT memberid, type, COUNT(*) AS total FROM tc_game_joker_reservations"
                    + " WHERE gamesessionid = ? AND status = ? GROUP BY memberid, type",
                    rows -> consumed.put(new Key(GameTransaction.longOrNull(rows, "memberid"), rows.getString("type")),
                            rows.getLong("total")), session, GameJokerRepository.CONSUMED);
            Set<Key> keys = new LinkedHashSet<>(stored.keySet());
            keys.addAll(ledger.keySet());
            keys.addAll(consumed.keySet());
            List<Discrepancy> result = new ArrayList<>();
            for (Key key : keys) {
                long[] balance = stored.getOrDefault(key, new long[2]);
                long[] sums = ledger.getOrDefault(key, new long[2]);
                if (balance[0] != sums[0]) {
                    result.add(new Discrepancy(key.memberId(), key.type(), "granted", balance[0], sums[0]));
                }
                if (balance[1] != sums[1]) {
                    result.add(new Discrepancy(key.memberId(), key.type(), "used", balance[1], sums[1]));
                }
                if (balance[1] != consumed.getOrDefault(key, 0L)) {
                    result.add(new Discrepancy(key.memberId(), key.type(), "consumed reservations", balance[1],
                            consumed.getOrDefault(key, 0L)));
                }
            }
            return result;
        });
    }

    /** The coordinate of a device's latest position, or null without one. */
    protected double[] latest(long deviceId) throws SQLException {
        long position = number("SELECT positionid FROM tc_devices WHERE id = ?", deviceId);
        if (position <= 0) {
            return null;
        }
        return new double[] {
            Double.parseDouble(text("SELECT latitude FROM tc_positions WHERE id = ?", position)),
            Double.parseDouble(text("SELECT longitude FROM tc_positions WHERE id = ?", position))};
    }

    protected long memberRevision(long memberId) throws SQLException {
        return number("SELECT revision FROM tc_game_member_state WHERE memberid = ?", memberId);
    }

}
