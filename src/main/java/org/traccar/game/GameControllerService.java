/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.StampedLock;

/**
 * The server-wide controller lease. One Tool instance, bound to an operator and a unique app instance, holds
 * it at a time. Each acquisition gets a larger {@code controllerEpoch}; fenced requests carry it in the
 * {@value #EPOCH_HEADER} header. Fenced requests hold the in-process fence shared for their whole duration,
 * and acquisition, release and takeover take it exclusively, so a request that passed the check completes
 * before the lease changes hands. Only one server instance uses the database.
 *
 * <p>The lease decides only which Tool may send fenced writes; whether a session's game runs is its game state
 * ({@link GameExecutionService}), which no change of control touches. A change of control ends no request: a new
 * epoch returns the requests still {@code executing} under an older epoch to {@code approved}
 * ({@link GameRequestService#returnExecuting}), after its commit and while acquisition and takeover still hold the
 * fence, so no fenced request of the new holder runs before. A request whose delivery was recorded already ended
 * with that delivery. Every change of the lease announces itself to operators ({@value #INVALIDATION}).
 *
 * <p>A Tool that lost the lease re-acquires it without a takeover by naming its last epoch ({@code resumeEpoch}):
 * granted while the lease is free and nobody acquired control since that epoch (a lease ended by a server restart is
 * free), refused otherwise.
 */
@Singleton
public class GameControllerService {

    public static final String EPOCH_HEADER = "X-Game-Controller-Epoch";
    public static final int LOCKED = 423;

    public static final String STATE_ACTIVE = "active";
    /** The operators' WebSocket invalidation of the lease. */
    public static final String INVALIDATION = "game-controller";

    public record Holder(Long userId, String userLabel, String instanceId, String label) { }
    public record Lease(long epoch, String state, boolean active, Holder holder, String acquiredAt,
                        String renewedAt, String expiresAt, String serverTime, int leaseSeconds, int renewSeconds) { }
    public record HistoryEntry(long id, long epoch, String event, Long userId, String userLabel,
                               String instanceId, String label, Long gameSessionId, String detail,
                               String occurredAt) { }
    public record Acquisition(Lease lease, boolean acquired, List<HistoryEntry> drift) { }
    public record HistoryPage(List<HistoryEntry> items, String nextCursor, boolean hasMore) { }

    private record Row(long epoch, String state, Long userId, String userLabel, String instanceId, String label,
                       Instant acquiredAt, Instant renewedAt, Instant expiresAt) {
        boolean active(Instant now) {
            return STATE_ACTIVE.equals(state) && expiresAt != null && expiresAt.isAfter(now);
        }
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws Exception;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(GameControllerService.class);

    private final DataSource dataSource;
    private final Clock clock;
    private final int leaseSeconds;
    private final int renewSeconds;
    private final GameRequestService requests;
    private final GamePublisher publisher;
    private final StampedLock fence = new StampedLock();

    @Inject
    public GameControllerService(DataSource dataSource, Config config, GameRequestService requests,
                                 GamePublisher publisher) {
        this(dataSource, Clock.systemUTC(), config.getInteger(Keys.GAME_CONTROLLER_LEASE_SECONDS),
                config.getInteger(Keys.GAME_CONTROLLER_RENEW_SECONDS), requests, publisher);
    }

    /** {@code requests} may be null in tests of the lease alone. */
    public GameControllerService(DataSource dataSource, Clock clock, int leaseSeconds, int renewSeconds,
                                 GameRequestService requests, GamePublisher publisher) {
        if (leaseSeconds < 2 || renewSeconds < 1 || renewSeconds >= leaseSeconds) {
            throw new IllegalArgumentException("Invalid game controller lease timing");
        }
        this.dataSource = dataSource;
        this.clock = clock;
        this.leaseSeconds = leaseSeconds;
        this.renewSeconds = renewSeconds;
        this.requests = requests;
        this.publisher = publisher;
    }

    public static WebApplicationException locked(String message) {
        return new WebApplicationException(message, Response.status(LOCKED)
                .entity(message).type(MediaType.TEXT_PLAIN).build());
    }

    /** Parses the epoch header; a malformed value is a client error, a missing one is {@code null}. */
    public static Long parseEpoch(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!value.trim().matches("[0-9]{1,18}")) {
            throw new BadRequestException("Invalid " + EPOCH_HEADER + " header");
        }
        return Long.parseLong(value.trim());
    }

    // --- in-process fence -------------------------------------------------------------------------------

    /** Holds the fence shared; release the returned stamp with {@link #unlockShared(long)}. */
    public long lockShared() {
        return fence.readLock();
    }

    public void unlockShared(long stamp) {
        fence.unlockRead(stamp);
    }

    private <T> T exclusive(Callable<T> work) throws Exception {
        long stamp = fence.writeLock();
        try {
            return work.call();
        } finally {
            fence.unlockWrite(stamp);
        }
    }

    /**
     * Runs an unfenced change while no lease is active (423 otherwise), holding the fence exclusively: fenced requests
     * admitted before the lease lapsed finish first, and no Tool acquires control until the change is done. The
     * caller must not hold the fence shared (unfenced requests never do).
     */
    public <T> T whileUncontrolled(Callable<T> work) throws Exception {
        return exclusive(() -> {
            if (status().active()) {
                throw locked("Tool controls the game; stop it in Tool, or release control first");
            }
            return work.call();
        });
    }

    // --- persistence helpers ------------------------------------------------------------------------------

    private <T> T transaction(Work<T> work) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column, utc());
        return value == null ? null : value.toInstant();
    }

    private static String iso(Instant value) {
        return value == null ? null : value.toString();
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static void setTime(java.sql.PreparedStatement query, int index, Instant value) throws SQLException {
        if (value == null) {
            query.setNull(index, Types.TIMESTAMP);
        } else {
            query.setTimestamp(index, Timestamp.from(value), utc());
        }
    }

    private static void setLong(java.sql.PreparedStatement query, int index, Long value) throws SQLException {
        if (value == null) {
            query.setNull(index, Types.BIGINT);
        } else {
            query.setLong(index, value);
        }
    }

    private static String text(String value, int length) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("\\p{Cntrl}", " ").trim();
        return cleaned.length() > length ? cleaned.substring(0, length) : cleaned;
    }

    private Row row(Connection connection, boolean lock) throws SQLException {
        try (var query = connection.prepareStatement("SELECT * FROM tc_game_controller WHERE id = 1"
                + (lock ? " FOR UPDATE" : ""))) {
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("Game controller row is missing");
                }
                return new Row(rows.getLong("epoch"), rows.getString("state"), nullableLong(rows, "userid"),
                        rows.getString("userlabel"), rows.getString("instanceid"), rows.getString("label"),
                        instant(rows, "acquiredat"),
                        instant(rows, "renewedat"), instant(rows, "expiresat"));
            }
        }
    }

    private Lease lease(Row row, Instant now) {
        boolean active = row.active(now);
        String state = STATE_ACTIVE.equals(row.state()) && !active ? "expired" : row.state();
        Holder holder = row.instanceId() == null ? null
                : new Holder(row.userId(), row.userLabel(), row.instanceId(), row.label());
        return new Lease(row.epoch(), state, active, holder, iso(row.acquiredAt()), iso(row.renewedAt()),
                iso(row.expiresAt()), now.toString(), leaseSeconds, renewSeconds);
    }

    private static String userLabel(Connection connection, long userId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT name,email FROM tc_users WHERE id = ?")) {
            query.setLong(1, userId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                String label = rows.getString("name");
                return text(label == null || label.isBlank() ? rows.getString("email") : label, 128);
            }
        }
    }

    private long history(Connection connection, long epoch, String event, Long userId, String userLabel,
                         String instanceId, String label, Long gameSessionId, String detail, Instant at)
            throws SQLException {
        try (var query = connection.prepareStatement("INSERT INTO tc_game_controller_history"
                + " (epoch,event,userid,userlabel,instanceid,label,gamesessionid,detail,occurredat)"
                + " VALUES (?,?,?,?,?,?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
            query.setLong(1, epoch);
            query.setString(2, event);
            setLong(query, 3, userId);
            query.setString(4, text(userLabel, 128));
            query.setString(5, instanceId);
            query.setString(6, text(label, 128));
            setLong(query, 7, gameSessionId);
            query.setString(8, text(detail, 512));
            setTime(query, 9, at);
            query.executeUpdate();
            try (var keys = query.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    private void historyFor(Connection connection, Row row, String event, String detail, Instant at)
            throws SQLException {
        history(connection, row.epoch(), event, row.userId(), row.userLabel(), row.instanceId(), row.label(),
                null, detail, at);
    }

    /**
     * After a committed change of control: a new {@code epoch} returns the requests still executing under an older
     * one to {@code approved}; then the change is announced. Failures are logged; the lease change itself is
     * committed, and the next acquisition returns what this one could not.
     */
    private void changed(Long epoch) {
        if (epoch != null && requests != null) {
            try {
                requests.returnExecuting(epoch);
            } catch (Exception error) {
                LOGGER.warn("Executing game requests could not be returned after a change of control", error);
            }
        }
        if (publisher != null) {
            publisher.operators(INVALIDATION);
        }
    }

    private void end(Connection connection, String state, Instant at) throws SQLException {
        try (var query = connection.prepareStatement("UPDATE tc_game_controller SET state = ?,"
                + " endedat = ? WHERE id = 1")) {
            query.setString(1, state);
            setTime(query, 2, at);
            query.executeUpdate();
        }
    }

    private static String instanceId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new BadRequestException("Invalid controller instance ID");
        }
        return value;
    }

    private static HistoryEntry entry(ResultSet rows) throws SQLException {
        return new HistoryEntry(rows.getLong("id"), rows.getLong("epoch"), rows.getString("event"),
                nullableLong(rows, "userid"), rows.getString("userlabel"), rows.getString("instanceid"),
                rows.getString("label"), nullableLong(rows, "gamesessionid"), rows.getString("detail"),
                iso(instant(rows, "occurredat")));
    }

    /**
     * Unfenced administrator changes since the previous acquisition: edits of game-owned resources, and games
     * stopped without control.
     */
    private List<HistoryEntry> drift(Connection connection, long acquisitionId) throws SQLException {
        long since = 0;
        try (var query = connection.prepareStatement("SELECT MAX(id) FROM tc_game_controller_history"
                + " WHERE id < ? AND event IN ('acquired','takeover')")) {
            query.setLong(1, acquisitionId);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    since = rows.getLong(1);
                }
            }
        }
        List<HistoryEntry> result = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT * FROM tc_game_controller_history"
                + " WHERE (event = 'unfencedEdit' OR event = 'gameStopped' AND instanceid IS NULL)"
                + " AND id > ? AND id < ? ORDER BY id")) {
            query.setLong(1, since);
            query.setLong(2, acquisitionId);
            query.setMaxRows(200);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(entry(rows));
                }
            }
        }
        return result;
    }

    // --- lease operations ---------------------------------------------------------------------------------

    public Lease status() throws Exception {
        return transaction(connection -> lease(row(connection, false), now()));
    }

    /** Verifies that {@code epoch} is the active lease of {@code userId}; throws 423 otherwise. */
    public Lease check(long userId, Long epoch) throws Exception {
        if (epoch == null) {
            throw locked("This change requires the game controller lease (" + EPOCH_HEADER + " header missing)");
        }
        return transaction(connection -> {
            Instant now = now();
            Row row = row(connection, false);
            if (!row.active(now)) {
                throw locked("The game controller lease has expired or was released; resume control in Tool");
            }
            if (row.epoch() != epoch || row.userId() == null || row.userId() != userId) {
                throw locked("Another Tool instance controls the game (controller epoch " + row.epoch() + ")");
            }
            return lease(row, now);
        });
    }

    private Acquisition start(Connection connection, Row current, long userId, String instanceId, String label,
                              boolean force, Instant now) throws Exception {
        String event = "acquired";
        String detail = null;
        if (current.active(now)) {
            if (!force) {
                throw locked("The game is controlled by " + describe(current) + "; take over control to continue");
            }
            event = "takeover";
            detail = "Took over from " + describe(current);
            historyFor(connection, current, "takenOver", "Taken over by " + text(label, 128), now);
        } else if (STATE_ACTIVE.equals(current.state())) {
            historyFor(connection, current, "expired", null, current.expiresAt());
        }
        long epoch = current.epoch() + 1;
        String user = userLabel(connection, userId);
        Instant expires = now.plusSeconds(leaseSeconds);
        try (var query = connection.prepareStatement("UPDATE tc_game_controller SET epoch = ?, state = ?,"
                + " userid = ?, userlabel = ?, instanceid = ?, label = ?, acquiredat = ?,"
                + " renewedat = ?, expiresat = ?, endedat = NULL WHERE id = 1")) {
            query.setLong(1, epoch);
            query.setString(2, STATE_ACTIVE);
            query.setLong(3, userId);
            query.setString(4, user);
            query.setString(5, instanceId);
            query.setString(6, text(label, 128));
            setTime(query, 7, now);
            setTime(query, 8, now);
            setTime(query, 9, expires);
            query.executeUpdate();
        }
        long id = history(connection, epoch, event, userId, user, instanceId, label, null, detail, now);
        Row updated = row(connection, false);
        return new Acquisition(lease(updated, now), true, drift(connection, id));
    }

    private static String describe(Row row) {
        String who = row.userLabel() != null ? row.userLabel() : "another operator";
        return row.label() == null || row.label().isBlank() ? who : who + " on " + row.label();
    }

    public Acquisition acquire(long userId, String instanceId, String label) throws Exception {
        return acquire(userId, instanceId, label, null);
    }

    /**
     * Acquires a free or expired lease. Repeating the call from the instance that already holds the active
     * lease returns that lease unchanged, so a lost response can be retried safely.
     *
     * <p>With {@code resumeEpoch} (the epoch this instance held when it lost control) the lease is granted only if
     * nobody acquired control since: the lease still carries that epoch, of this instance and account, and is no
     * longer active (expired, released or ended by a server restart). Otherwise it answers 409, and the operator
     * resumes or takes over explicitly.
     */
    public Acquisition acquire(long userId, String instanceId, String label, Long resumeEpoch) throws Exception {
        instanceId(instanceId);
        return exclusive(() -> {
            Acquisition result = transaction(connection -> {
                Instant now = now();
                Row current = row(connection, true);
                if (current.active(now) && instanceId.equals(current.instanceId())
                        && current.userId() != null && current.userId() == userId) {
                    return new Acquisition(lease(current, now), false, List.of());
                }
                if (resumeEpoch != null && !current.active(now) && (current.epoch() != resumeEpoch
                        || !instanceId.equals(current.instanceId()) || current.userId() == null
                        || current.userId() != userId)) {
                    throw GameErrors.conflict("Control of the game changed since controller epoch " + resumeEpoch
                            + " (now " + current.epoch() + "); resume control explicitly");
                }
                return start(connection, current, userId, instanceId, label, false, now);
            });
            if (result.acquired()) {
                changed(result.lease().epoch());
            }
            return result;
        });
    }

    /** Forced takeover: always starts a new epoch; the previous holder's requests are rejected from now on. */
    public Acquisition takeover(long userId, String instanceId, String label) throws Exception {
        instanceId(instanceId);
        return exclusive(() -> {
            Acquisition result = transaction(connection -> {
                Row current = row(connection, true);
                return start(connection, current, userId, instanceId, label, true, now());
            });
            changed(result.lease().epoch());
            return result;
        });
    }

    public Lease renew(long userId, long epoch, String instanceId) throws Exception {
        instanceId(instanceId);
        return transaction(connection -> {
            Instant now = now();
            Row row = row(connection, true);
            if (!row.active(now) || row.epoch() != epoch || row.userId() == null || row.userId() != userId
                    || !instanceId.equals(row.instanceId())) {
                throw locked(row.epoch() != epoch && row.active(now)
                        ? "The game controller lease was taken over by " + describe(row)
                        : "The game controller lease has expired; resume control in Tool");
            }
            try (var query = connection.prepareStatement(
                    "UPDATE tc_game_controller SET renewedat = ?, expiresat = ? WHERE id = 1")) {
                setTime(query, 1, now);
                setTime(query, 2, now.plusSeconds(leaseSeconds));
                query.executeUpdate();
            }
            return lease(row(connection, false), now);
        });
    }

    /** Releases the lease if {@code epoch} still holds it; releasing an already ended lease is a no-op. */
    public Lease release(long userId, long epoch) throws Exception {
        return exclusive(() -> {
            boolean[] released = new boolean[1];
            Lease result = transaction(connection -> {
                Instant now = now();
                Row row = row(connection, true);
                if (row.active(now) && row.epoch() == epoch && row.userId() != null && row.userId() == userId) {
                    historyFor(connection, row, "released", null, now);
                    end(connection, "released", now);
                    released[0] = true;
                }
                return lease(row(connection, false), now);
            });
            if (released[0]) {
                changed(null);
            }
            return result;
        });
    }

    /**
     * Ends a lease that survived a server restart. Its holder re-acquires it automatically ({@code resumeEpoch}),
     * since nobody acquired control meanwhile.
     */
    public boolean endAfterRestart() throws Exception {
        return exclusive(() -> {
            boolean ended = transaction(connection -> {
                Instant now = now();
                Row row = row(connection, true);
                if (!STATE_ACTIVE.equals(row.state())) {
                    return false;
                }
                if (row.active(now)) {
                    historyFor(connection, row, "serverRestart", null, now);
                    end(connection, "restarted", now);
                } else {
                    historyFor(connection, row, "expired", null, row.expiresAt());
                    end(connection, "expired", row.expiresAt());
                }
                return true;
            });
            if (ended) {
                changed(null);
            }
            return ended;
        });
    }

    /**
     * Retries returning requests still executing under an older epoch than the lease's (every second): the return
     * after a change of control may have failed. Returns whether a request was returned.
     */
    public boolean returnStaleExecuting() throws Exception {
        return requests != null && requests.returnStale();
    }

    /** Records the expiry of a lease that was not renewed in time; returns whether anything changed. */
    public boolean recordExpiry() throws Exception {
        Lease lease = status();
        if (!"expired".equals(lease.state())) {
            return false;
        }
        return exclusive(() -> {
            boolean expired = transaction(connection -> {
                Row row = row(connection, true);
                if (!STATE_ACTIVE.equals(row.state()) || row.active(now())) {
                    return false;
                }
                historyFor(connection, row, "expired", null, row.expiresAt());
                end(connection, "expired", row.expiresAt());
                return true;
            });
            if (expired) {
                changed(null);
            }
            return expired;
        });
    }

    /** Records an administrator edit of a game-owned resource made while no lease was held. */
    public void recordUnfencedEdit(long userId, String detail) throws Exception {
        transaction(connection -> {
            Row row = row(connection, false);
            history(connection, row.epoch(), "unfencedEdit", userId, userLabel(connection, userId), null, null,
                    null, detail, now());
            return null;
        });
    }

    public HistoryPage history(String cursor, int requestedLimit) throws Exception {
        int limit = requestedLimit == 0 ? 100 : requestedLimit;
        if (limit < 1 || limit > 500 || cursor != null && !cursor.matches("[0-9]{1,19}")) {
            throw new BadRequestException("Invalid history pagination");
        }
        long before = cursor == null ? Long.MAX_VALUE : Long.parseLong(cursor);
        return transaction(connection -> {
            List<HistoryEntry> items = new ArrayList<>();
            boolean more = false;
            try (var query = connection.prepareStatement(
                    "SELECT * FROM tc_game_controller_history WHERE id < ? ORDER BY id DESC")) {
                query.setLong(1, before);
                query.setMaxRows(limit + 1);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        if (items.size() == limit) {
                            more = true;
                            break;
                        }
                        items.add(entry(rows));
                    }
                }
            }
            String next = items.isEmpty() ? null : Long.toString(items.getLast().id());
            return new HistoryPage(items, next, more);
        });
    }
}
