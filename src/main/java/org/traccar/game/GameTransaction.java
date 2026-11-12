/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * One database transaction of game data model 2, opened by {@link GameTransactions}.
 *
 * <p>It enforces the lock order of the effects transaction (plan §3.1): first the session row, then member state
 * rows in ascending member ID order, then dependent rows. Every writer of a session locks its session row first,
 * so writers of one session are serialized and no two of them can wait on each other. Dependent rows need no
 * explicit lock beyond that.
 *
 * <p>Revisions advance at most once per transaction: {@link #bumpSession()} and {@link #bumpMemberState(long)} may
 * be called by every part of an operation and still count the operation once.
 */
public final class GameTransaction {

    /** Maps the current row of a result. */
    @FunctionalInterface
    public interface Rows<T> {
        T map(ResultSet rows) throws SQLException;
    }

    private final Connection connection;
    private final Instant now;
    private final boolean readOnly;
    private final List<Runnable> afterCommit = new ArrayList<>();

    private Long sessionId;
    private long sessionRevision;
    private boolean sessionBumped;
    private final Set<Long> memberLocks = new HashSet<>();
    private final Map<Long, Long> memberStates = new HashMap<>();
    private final Set<Long> memberStatesBumped = new HashSet<>();
    private long highestMemberLock;

    GameTransaction(Connection connection, Instant now, boolean readOnly) {
        this.connection = connection;
        this.now = GameTime.truncate(now);
        this.readOnly = readOnly;
    }

    public Connection connection() {
        return connection;
    }

    /** One timestamp for everything this transaction writes. */
    public Instant now() {
        return now;
    }

    /** Runs after a successful commit, in registration order: publishing to caches and WebSocket (plan §3.1). */
    public void afterCommit(Runnable action) {
        afterCommit.add(action);
    }

    List<Runnable> afterCommitActions() {
        return Collections.unmodifiableList(afterCommit);
    }

    // Statements --------------------------------------------------------------------------------------------

    private static void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            if (params[i] instanceof Instant instant) {
                GameTime.set(statement, i + 1, instant);
            } else {
                statement.setObject(i + 1, params[i]);
            }
        }
    }

    private void writable() {
        if (readOnly) {
            throw new IllegalStateException("Read transaction cannot write game data");
        }
    }

    public int update(String sql, Object... params) throws SQLException {
        writable();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        }
    }

    /** Inserts one row and returns its generated key. */
    public long insert(String sql, Object... params) throws SQLException {
        writable();
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, params);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No generated key");
                }
                return keys.getLong(1);
            }
        }
    }

    public <T> List<T> list(String sql, Rows<T> mapper, Object... params) throws SQLException {
        return list(sql, 0, mapper, params);
    }

    /** At most {@code maxRows} rows (0 for all). */
    public <T> List<T> list(String sql, int maxRows, Rows<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setMaxRows(maxRows);
            bind(statement, params);
            try (ResultSet rows = statement.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(mapper.map(rows));
                }
                return result;
            }
        }
    }

    public <T> Optional<T> first(String sql, Rows<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setMaxRows(1);
            bind(statement, params);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(mapper.map(rows)) : Optional.empty();
            }
        }
    }

    public boolean exists(String sql, Object... params) throws SQLException {
        return first(sql, rows -> Boolean.TRUE, params).isPresent();
    }

    public static Long longOrNull(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    // Locks and revisions -----------------------------------------------------------------------------------

    /**
     * Locks the session row, which every writer of the session locks first, and returns its aggregate revision.
     * Answers 404 when the session does not exist.
     */
    public long lockSession(long id) throws SQLException {
        writable();
        if (sessionId != null) {
            if (sessionId != id) {
                throw new IllegalStateException("A transaction locks one game session");
            }
            return sessionRevision;
        }
        sessionRevision = first("SELECT revision FROM tc_game_sessions WHERE id = ? FOR UPDATE",
                rows -> rows.getLong(1), id).orElseThrow(() -> GameErrors.notFound("Game session not found"));
        sessionId = id;
        return sessionRevision;
    }

    /** The locked session, or an error when no session is locked. */
    public long session() {
        if (sessionId == null) {
            throw new IllegalStateException("Lock the game session first");
        }
        return sessionId;
    }

    /**
     * Locks the member state rows of these members, in ascending member ID order, after the session row. A
     * transaction can lock more members later only above the highest member it already locked, so every
     * transaction acquires member locks in one global order. Members without a state row (hunters, the spectator,
     * members of other sessions) are skipped.
     *
     * @return the current revision of each locked member state
     */
    public Map<Long, Long> lockMemberStates(Collection<Long> memberIds) throws SQLException {
        long session = session();
        TreeSet<Long> wanted = new TreeSet<>(memberIds);
        wanted.removeAll(memberLocks);
        if (!wanted.isEmpty() && wanted.first() <= highestMemberLock) {
            throw new IllegalStateException("Member state locks must be taken in ascending member ID order");
        }
        for (long memberId : wanted) {
            Optional<Long> revision = first("SELECT revision FROM tc_game_member_state"
                    + " WHERE gamesessionid = ? AND memberid = ? FOR UPDATE",
                    rows -> rows.getLong(1), session, memberId);
            memberLocks.add(memberId);
            highestMemberLock = memberId;
            revision.ifPresent(value -> memberStates.put(memberId, value));
        }
        Map<Long, Long> result = new HashMap<>();
        for (long memberId : memberIds) {
            if (memberStates.containsKey(memberId)) {
                result.put(memberId, memberStates.get(memberId));
            }
        }
        return result;
    }

    /** Registers a member that this transaction created, whose state row (if any) it holds locked already. */
    void createdMember(long memberId, Long stateRevision) {
        memberLocks.add(memberId);
        if (stateRevision != null) {
            memberStates.put(memberId, stateRevision);
        }
        highestMemberLock = Math.max(highestMemberLock, memberId);
    }

    /**
     * Advances a member state revision once in this transaction. The member state must be locked; members
     * without a state row have no revision, and this returns null for them.
     */
    public Long bumpMemberState(long memberId) throws SQLException {
        long session = session();
        if (!memberLocks.contains(memberId)) {
            throw new IllegalStateException("Lock the member state before advancing it");
        }
        Long current = memberStates.get(memberId);
        if (current == null) {
            return null;
        }
        if (memberStatesBumped.add(memberId)) {
            update("UPDATE tc_game_member_state SET revision = ?, updatedat = ?"
                    + " WHERE gamesessionid = ? AND memberid = ?", current + 1, now, session, memberId);
            memberStates.put(memberId, current + 1);
        }
        return memberStates.get(memberId);
    }

    /** Advances the session aggregate revision once in this transaction and returns the new revision. */
    public long bumpSession() throws SQLException {
        long session = session();
        if (!sessionBumped) {
            update("UPDATE tc_game_sessions SET revision = ?, updatedat = ? WHERE id = ?",
                    sessionRevision + 1, now, session);
            sessionRevision++;
            sessionBumped = true;
        }
        return sessionRevision;
    }

}
