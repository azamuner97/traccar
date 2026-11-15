/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The game state of a session (plan B0): {@code running} or {@code stopped}, stored in tc_game_session_state and
 * changed only with the Tool's Start and Stop game buttons. It decides whether the server executes the session and
 * whether members may submit requests; the controller lease decides only which Tool may make decisions. Losing,
 * releasing or taking over the lease never changes it, and it survives a server restart. A session without a row is
 * stopped; at most one session per server runs ({@code runningkey}).
 *
 * <p>Changing the state is fenced: it needs the controlling Tool's epoch. The one exception is a stop by any
 * administrator while no lease is active, so a game left behind by a crashed Tool can be stopped without taking
 * control first. Every change is recorded in the controller history ({@code gameStarted}, {@code gameStopped}),
 * written as a {@code game} execution event and announced to the members (their status shows running or
 * stopped).
 *
 * <p>Stopping the game, in the same transaction: open requests end ({@code pending} and {@code approved} ones are
 * cancelled, {@code executing} ones fail, their jokers return) and active live uploads and relays end
 * ({@code gameStopped}); timetable entries still waiting for a fresh fix are skipped ({@code stopped}). Break
 * deadlines do not move; slots that come due while the game is stopped are missed when it runs again.
 */
@Singleton
public class GameExecutionService {

    public static final String RUNNING = "running";
    public static final String STOPPED = "stopped";
    /** The reason open requests and live uploads end with when the game stops. */
    public static final String GAME_STOPPED = "gameStopped";
    public static final String STOPPED_MESSAGE = "The game was stopped";

    /** The game state of a session; {@code changedAt} is null while it was never started. */
    public record State(long gameSessionId, String state, String changedAt, String changedBy) {
        public boolean running() {
            return RUNNING.equals(state);
        }
    }

    private record Holder(long epoch, Long userId, String userLabel, String instanceId, String label) {
    }

    private final GameTransactions transactions;
    private final GameControllerService controller;
    private final GamePublisher publisher;

    @Inject
    public GameExecutionService(GameTransactions transactions, GameControllerService controller,
                                GamePublisher publisher) {
        this.transactions = transactions;
        this.controller = controller;
        this.publisher = publisher;
    }

    // Reads -------------------------------------------------------------------------------------------------

    static State read(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.first("SELECT state, changedat, changedby FROM tc_game_session_state"
                + " WHERE gamesessionid = ?", rows -> new State(sessionId, rows.getString("state"),
                        GameTime.text(GameTime.get(rows, "changedat")), rows.getString("changedby")), sessionId)
                .orElse(new State(sessionId, STOPPED, null, null));
    }

    /** Whether the session's game runs. */
    public static boolean running(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.exists("SELECT 1 FROM tc_game_session_state WHERE gamesessionid = ? AND runningkey = 1",
                sessionId);
    }

    /** The session whose game runs, if any (at most one per server). */
    public static Optional<Long> runningSession(GameTransaction transaction) throws SQLException {
        return transaction.first("SELECT gamesessionid FROM tc_game_session_state WHERE runningkey = 1",
                rows -> rows.getLong(1));
    }

    /** The running session, or null; read outside any transaction (fencing). */
    public Long runningSessionId() throws Exception {
        return transactions.read(transaction -> runningSession(transaction).orElse(null));
    }

    /** The game state of a session; 404 when the session does not exist. */
    public State get(long sessionId) throws Exception {
        return transactions.read(transaction -> {
            GameDocumentService.sessionExists(transaction, sessionId);
            return read(transaction, sessionId);
        });
    }

    // Changes -----------------------------------------------------------------------------------------------

    /**
     * Starts or stops the game. With {@code epoch} the caller must hold the lease (the controller fence admitted
     * it). Without one only {@code stopped} is accepted, from an administrator, while no lease is active; the
     * shared fence is held meanwhile, so no Tool acquires control in between. Setting the state the session already
     * has changes nothing.
     */
    public State change(long sessionId, String requested, long userId, Long epoch) throws Exception {
        if (!RUNNING.equals(requested) && !STOPPED.equals(requested)) {
            throw GameErrors.invalid("state must be running or stopped");
        }
        if (epoch != null) {
            controller.check(userId, epoch);
            return apply(sessionId, requested, userId, epoch);
        }
        if (RUNNING.equals(requested)) {
            throw GameControllerService.locked("Starting the game requires control of the game ("
                    + GameControllerService.EPOCH_HEADER + " header missing)");
        }
        long stamp = controller.lockShared();
        try {
            if (controller.status().active()) {
                throw GameControllerService.locked("Tool controls the game; stop it in Tool, or release control first");
            }
            return apply(sessionId, requested, userId, null);
        } finally {
            controller.unlockShared(stamp);
        }
    }

    static String userLabel(GameTransaction transaction, long userId) throws SQLException {
        return transaction.first("SELECT name, email FROM tc_users WHERE id = ?", rows -> {
            String name = rows.getString("name");
            return GameJokerRepository.clip(name == null || name.isBlank() ? rows.getString("email") : name, 128);
        }, userId).orElse("administrator");
    }

    private static Holder holder(GameTransaction transaction) throws SQLException {
        return transaction.first("SELECT epoch, userid, userlabel, instanceid, label FROM tc_game_controller"
                + " WHERE id = 1", rows -> new Holder(rows.getLong("epoch"), GameTransaction.longOrNull(rows, "userid"),
                        rows.getString("userlabel"), rows.getString("instanceid"), rows.getString("label")))
                .orElseThrow(() -> new IllegalStateException("Game controller row is missing"));
    }

    private State apply(long sessionId, String requested, long userId, Long epoch) throws Exception {
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            State current = read(transaction, sessionId);
            if (current.state().equals(requested)) {
                return current;
            }
            boolean start = RUNNING.equals(requested);
            Holder lease = holder(transaction);
            String who = epoch != null && lease.userLabel() != null ? lease.userLabel()
                    : userLabel(transaction, userId);
            String label = epoch != null && lease.label() != null && !lease.label().isBlank()
                    ? who + " on " + lease.label() : who;
            if (start) {
                Optional<Long> other = runningSession(transaction);
                if (other.isPresent() && other.get() != sessionId) {
                    String name = transaction.first("SELECT name FROM tc_game_sessions WHERE id = ?",
                            rows -> rows.getString(1), other.get()).orElse("another session");
                    throw GameErrors.conflict("The game of " + name + " is running; stop it before starting this one");
                }
            }
            Instant now = transaction.now();
            String changedBy = GameJokerRepository.clip(label, 128);
            try {
                if (transaction.update("UPDATE tc_game_session_state SET state = ?, changedat = ?, changedby = ?,"
                        + " runningkey = ? WHERE gamesessionid = ?", requested, now, changedBy, start ? 1 : null,
                        sessionId) == 0) {
                    transaction.update("INSERT INTO tc_game_session_state (gamesessionid, state, changedat, changedby,"
                            + " runningkey) VALUES (?, ?, ?, ?, ?)", sessionId, requested, now, changedBy,
                            start ? 1 : null);
                }
            } catch (SQLException error) {
                if (GameErrors.constraintViolation(error)) {
                    throw GameErrors.conflict("Another game is running; stop it before starting this one");
                }
                throw error;
            }
            Stopped stopped = start ? new Stopped(0, 0, 0, false, Set.of(), false)
                    : stopEffects(transaction, publisher, sessionId);

            String detail = epoch == null ? "Stopped without control of the game" : null;
            transaction.insert("INSERT INTO tc_game_controller_history (epoch, event, userid, userlabel, instanceid,"
                    + " label, gamesessionid, detail, occurredat) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", lease.epoch(),
                    start ? "gameStarted" : "gameStopped", epoch != null ? lease.userId() : userId,
                    GameJokerRepository.clip(who, 128), epoch != null ? lease.instanceId() : null,
                    epoch != null ? GameJokerRepository.clip(lease.label(), 128) : null, sessionId, detail, now);

            ObjectNode data = JsonNodeFactory.instance.objectNode();
            data.put("type", start ? "gameStarted" : "gameStopped");
            data.put("state", requested);
            data.put("changedBy", changedBy);
            data.put("controlled", epoch != null);
            if (!start) {
                data.put("endedRequests", stopped.requests());
                data.put("endedLiveUploads", stopped.uploads());
                data.put("skippedTimetableEntries", stopped.timetableEntries());
            }
            GameLogService.event(transaction, publisher, sessionId, GameEvent.GAME,
                    (start ? "Game started by " : "Game stopped by ") + changedBy
                            + (epoch == null ? " (without control of the game)" : ""), data);

            transaction.bumpSession();
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(sessionId, GamePublisher.STATUS, null)));
            if (stopped.requestsChanged()) {
                transaction.afterCommit(() -> publisher.invalidate(
                        new GamePublisher.Invalidation(sessionId, GamePublisher.REQUESTS, null)));
            }
            Set<Long> owners = new HashSet<>(stopped.jokerMembers());
            if (stopped.pool()) {
                owners.addAll(GameStateService.hunters(transaction, sessionId));
            }
            if (!owners.isEmpty()) {
                Set<Long> recipients = Set.copyOf(owners);
                transaction.afterCommit(() -> publisher.invalidate(
                        new GamePublisher.Invalidation(sessionId, GamePublisher.JOKERS, recipients)));
            }
            transaction.afterCommit(() -> publisher.operators(GameSessionService.SESSIONS_INVALIDATION));
            return read(transaction, sessionId);
        });
    }

    /** What stopping the game ended. */
    private record Stopped(int requests, int uploads, int timetableEntries, boolean requestsChanged,
                           Set<Long> jokerMembers, boolean pool) {
    }

    /**
     * Ends the open requests and active live uploads of a session whose game stops, and skips ({@code stopped})
     * the timetable entries that wait for a fresh fix. Each live upload ends with its {@code ended} event; a live
     * speedhunt ping that never reached anyone returns its pool joker, one that was the last ping completes its
     * speedhunt (as when it stops).
     */
    private static Stopped stopEffects(GameTransaction transaction, GamePublisher publisher, long sessionId)
            throws Exception {
        List<GameState.LiveUpload> uploads = GameStateRepository.activeUploads(transaction, sessionId);
        Set<Long> members = new TreeSet<>();
        for (GameState.LiveUpload upload : uploads) {
            members.add(upload.memberId());
        }
        transaction.lockMemberStates(members);
        GameOperations ended = new GameOperations(transaction, sessionId, "Server", null, publisher);
        for (GameState.LiveUpload upload : uploads) {
            ended.endUpload(upload, GAME_STOPPED);
        }
        GameStateService.publish(transaction, publisher, sessionId, ended);
        int requests = 0;
        Set<Long> owners = new HashSet<>();
        boolean pool = false;
        for (GameRequest request : GameRequestRepository.open(transaction, sessionId)) {
            String status = GameRequest.EXECUTING.equals(request.status()) ? GameRequest.FAILED : GameRequest.CANCELLED;
            var settled = GameRequestRepository.finish(transaction, request, status, STOPPED_MESSAGE, null, false);
            owners.addAll(settled.members());
            pool |= settled.pool();
            requests++;
        }
        Set<Long> fakeOwners = new HashSet<>();
        int entries = GameTimetableService.stopWaiting(transaction, publisher, sessionId, fakeOwners);
        if (entries > 0) {
            owners.addAll(fakeOwners);
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(sessionId, GamePublisher.TIMETABLE, null)));
        }
        return new Stopped(requests, uploads.size(), entries, requests > 0, owners, pool);
    }

    // Test events -------------------------------------------------------------------------------------------

    /**
     * Writes a {@code game} test event to a session's log and pushes it to administrators: the phase-0 drill checks
     * that an event written while the Tool is disconnected appears in its upload log exactly once.
     */
    public GameEvent testEvent(long sessionId, String message, long userId) throws Exception {
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            String who = userLabel(transaction, userId);
            String text = message == null || message.isBlank() ? "Test event" : message.strip();
            if (text.length() > 512) {
                throw GameErrors.invalid("A test event message has at most 512 characters");
            }
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            data.put("type", "test");
            data.put("message", text);
            data.put("sentBy", who);
            return GameLogService.event(transaction, publisher, sessionId, GameEvent.GAME, text + " (sent by " + who
                    + ")", data);
        });
    }

}
