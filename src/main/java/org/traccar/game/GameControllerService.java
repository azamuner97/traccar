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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
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

    /** The lease row (tc_game_controller). */
    record Row(long epoch, String state, Long userId, String userLabel, String instanceId, String label,
               Instant acquiredAt, Instant renewedAt, Instant expiresAt) {
        boolean active(Instant now) {
            return STATE_ACTIVE.equals(state) && expiresAt != null && expiresAt.isAfter(now);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(GameControllerService.class);

    private final GameTransactions transactions;
    private final int leaseSeconds;
    private final int renewSeconds;
    private final GameRequestService requests;
    private final GamePublisher publisher;
    private final StampedLock fence = new StampedLock();

    @Inject
    public GameControllerService(GameTransactions transactions, Config config, GameRequestService requests,
                                 GamePublisher publisher) {
        this(transactions, config.getInteger(Keys.GAME_CONTROLLER_LEASE_SECONDS),
                config.getInteger(Keys.GAME_CONTROLLER_RENEW_SECONDS), requests, publisher);
    }

    /** The lease's time comes from the clock of {@code transactions}; {@code requests} may be null in tests. */
    public GameControllerService(GameTransactions transactions, int leaseSeconds, int renewSeconds,
                                 GameRequestService requests, GamePublisher publisher) {
        if (leaseSeconds < 2 || renewSeconds < 1 || renewSeconds >= leaseSeconds) {
            throw new IllegalArgumentException("Invalid game controller lease timing");
        }
        this.transactions = transactions;
        this.leaseSeconds = leaseSeconds;
        this.renewSeconds = renewSeconds;
        this.requests = requests;
        this.publisher = publisher;
    }

    public static WebApplicationException locked(String message) {
        return new WebApplicationException(message, Response.status(LOCKED)
                .entity(message).type(MediaType.TEXT_PLAIN).build());
    }

    /**
     * Whether the account is an administrator. The controller filter checks the lease only for administrators, so
     * other accounts learn nothing about it from fencing answers (review SC-5, SC-6).
     */
    public boolean administrator(long userId) throws Exception {
        return transactions.read(transaction -> transaction.first("SELECT administrator FROM tc_users WHERE id = ?",
                rows -> rows.getBoolean(1), userId).orElse(false));
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

    private static final String HISTORY = "SELECT id, epoch, event, userid, userlabel, instanceid, label,"
            + " gamesessionid, detail, occurredat FROM tc_game_controller_history";

    /** The lease row; {@code lock} locks it until the transaction ends. */
    static Row row(GameTransaction transaction, boolean lock) throws SQLException {
        return transaction.first("SELECT epoch, state, userid, userlabel, instanceid, label, acquiredat, renewedat,"
                + " expiresat FROM tc_game_controller WHERE id = 1" + (lock ? " FOR UPDATE" : ""),
                rows -> new Row(rows.getLong("epoch"), rows.getString("state"),
                        GameTransaction.longOrNull(rows, "userid"), rows.getString("userlabel"),
                        rows.getString("instanceid"), rows.getString("label"), GameTime.get(rows, "acquiredat"),
                        GameTime.get(rows, "renewedat"), GameTime.get(rows, "expiresat")))
                .orElseThrow(() -> new IllegalStateException("Game controller row is missing"));
    }

    private Lease lease(Row row, Instant now) {
        boolean active = row.active(now);
        String state = STATE_ACTIVE.equals(row.state()) && !active ? "expired" : row.state();
        Holder holder = row.instanceId() == null ? null
                : new Holder(row.userId(), row.userLabel(), row.instanceId(), row.label());
        return new Lease(row.epoch(), state, active, holder, GameTime.text(row.acquiredAt()),
                GameTime.text(row.renewedAt()), GameTime.text(row.expiresAt()), now.toString(), leaseSeconds,
                renewSeconds);
    }

    private static long history(GameTransaction transaction, long epoch, String event, Long userId, String userLabel,
                                String instanceId, String label, String detail, Instant at) throws SQLException {
        return transaction.insert("INSERT INTO tc_game_controller_history"
                + " (epoch, event, userid, userlabel, instanceid, label, gamesessionid, detail, occurredat)"
                + " VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)", epoch, event, userId,
                GameJokerRepository.clip(userLabel, 128), instanceId, GameJokerRepository.clip(label, 128),
                GameJokerRepository.clip(detail, 512), at);
    }

    private static void historyFor(GameTransaction transaction, Row row, String event, String detail, Instant at)
            throws SQLException {
        history(transaction, row.epoch(), event, row.userId(), row.userLabel(), row.instanceId(), row.label(),
                detail, at);
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

    private static void end(GameTransaction transaction, String state, Instant at) throws SQLException {
        transaction.update("UPDATE tc_game_controller SET state = ?, endedat = ? WHERE id = 1", state, at);
    }

    private static String instanceId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new BadRequestException("Invalid controller instance ID");
        }
        return value;
    }

    private static HistoryEntry entry(ResultSet rows) throws SQLException {
        return new HistoryEntry(rows.getLong("id"), rows.getLong("epoch"), rows.getString("event"),
                GameTransaction.longOrNull(rows, "userid"), rows.getString("userlabel"),
                rows.getString("instanceid"), rows.getString("label"),
                GameTransaction.longOrNull(rows, "gamesessionid"), rows.getString("detail"),
                GameTime.text(GameTime.get(rows, "occurredat")));
    }

    /**
     * Unfenced administrator changes since the previous acquisition: edits of game-owned resources, and games
     * stopped without control.
     */
    private static List<HistoryEntry> drift(GameTransaction transaction, long acquisitionId) throws SQLException {
        long since = transaction.first("SELECT MAX(id) FROM tc_game_controller_history"
                + " WHERE id < ? AND event IN ('acquired','takeover')", rows -> rows.getLong(1), acquisitionId)
                .orElse(0L);
        return transaction.list(HISTORY + " WHERE (event = 'unfencedEdit' OR event = 'gameStopped'"
                + " AND instanceid IS NULL) AND id > ? AND id < ? ORDER BY id", 200, GameControllerService::entry,
                since, acquisitionId);
    }

    // --- lease operations ---------------------------------------------------------------------------------

    public Lease status() throws Exception {
        return transactions.read(transaction -> lease(row(transaction, false), transaction.now()));
    }

    /** Verifies that {@code epoch} is the active lease of {@code userId}; throws 423 otherwise. */
    public Lease check(long userId, Long epoch) throws Exception {
        if (epoch == null) {
            throw locked("This change requires the game controller lease (" + EPOCH_HEADER + " header missing)");
        }
        return transactions.read(transaction -> {
            Instant now = transaction.now();
            Row row = row(transaction, false);
            if (!row.active(now)) {
                throw locked("The game controller lease has expired or was released; resume control in Tool");
            }
            if (row.epoch() != epoch || row.userId() == null || row.userId() != userId) {
                throw locked("Another Tool instance controls the game (controller epoch " + row.epoch() + ")");
            }
            return lease(row, now);
        });
    }

    private Acquisition start(GameTransaction transaction, Row current, long userId, String instanceId, String label,
                              boolean force) throws Exception {
        Instant now = transaction.now();
        String event = "acquired";
        String detail = null;
        if (current.active(now)) {
            if (!force) {
                throw locked("The game is controlled by " + describe(current) + "; take over control to continue");
            }
            event = "takeover";
            detail = "Took over from " + describe(current);
            historyFor(transaction, current, "takenOver", "Taken over by " + GameJokerRepository.clip(label, 128),
                    now);
        } else if (STATE_ACTIVE.equals(current.state())) {
            historyFor(transaction, current, "expired", null, current.expiresAt());
        }
        long epoch = current.epoch() + 1;
        String user = GameMemberRepository.userLabel(transaction, userId).orElse(null);
        transaction.update("UPDATE tc_game_controller SET epoch = ?, state = ?, userid = ?, userlabel = ?,"
                + " instanceid = ?, label = ?, acquiredat = ?, renewedat = ?, expiresat = ?, endedat = NULL"
                + " WHERE id = 1", epoch, STATE_ACTIVE, userId, user, instanceId,
                GameJokerRepository.clip(label, 128), now, now, now.plusSeconds(leaseSeconds));
        long id = history(transaction, epoch, event, userId, user, instanceId, label, detail, now);
        return new Acquisition(lease(row(transaction, false), now), true, drift(transaction, id));
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
            Acquisition result = transactions.write(transaction -> {
                Instant now = transaction.now();
                Row current = row(transaction, true);
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
                return start(transaction, current, userId, instanceId, label, false);
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
            Acquisition result = transactions.write(transaction -> start(transaction, row(transaction, true), userId,
                    instanceId, label, true));
            changed(result.lease().epoch());
            return result;
        });
    }

    public Lease renew(long userId, long epoch, String instanceId) throws Exception {
        instanceId(instanceId);
        return transactions.write(transaction -> {
            Instant now = transaction.now();
            Row row = row(transaction, true);
            if (!row.active(now) || row.epoch() != epoch || row.userId() == null || row.userId() != userId
                    || !instanceId.equals(row.instanceId())) {
                throw locked(row.epoch() != epoch && row.active(now)
                        ? "The game controller lease was taken over by " + describe(row)
                        : "The game controller lease has expired; resume control in Tool");
            }
            transaction.update("UPDATE tc_game_controller SET renewedat = ?, expiresat = ? WHERE id = 1", now,
                    now.plusSeconds(leaseSeconds));
            return lease(row(transaction, false), now);
        });
    }

    /** Releases the lease if {@code epoch} still holds it; releasing an already ended lease is a no-op. */
    public Lease release(long userId, long epoch) throws Exception {
        return exclusive(() -> {
            boolean[] released = new boolean[1];
            Lease result = transactions.write(transaction -> {
                Instant now = transaction.now();
                Row row = row(transaction, true);
                if (row.active(now) && row.epoch() == epoch && row.userId() != null && row.userId() == userId) {
                    historyFor(transaction, row, "released", null, now);
                    end(transaction, "released", now);
                    released[0] = true;
                }
                return lease(row(transaction, false), now);
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
            boolean ended = transactions.write(transaction -> {
                Instant now = transaction.now();
                Row row = row(transaction, true);
                if (!STATE_ACTIVE.equals(row.state())) {
                    return false;
                }
                if (row.active(now)) {
                    historyFor(transaction, row, "serverRestart", null, now);
                    end(transaction, "restarted", now);
                } else {
                    historyFor(transaction, row, "expired", null, row.expiresAt());
                    end(transaction, "expired", row.expiresAt());
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
            boolean expired = transactions.write(transaction -> {
                Row row = row(transaction, true);
                if (!STATE_ACTIVE.equals(row.state()) || row.active(transaction.now())) {
                    return false;
                }
                historyFor(transaction, row, "expired", null, row.expiresAt());
                end(transaction, "expired", row.expiresAt());
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
        transactions.write(transaction -> history(transaction, row(transaction, false).epoch(), "unfencedEdit",
                userId, GameMemberRepository.userLabel(transaction, userId).orElse(null), null, null, detail,
                transaction.now()));
    }
}
