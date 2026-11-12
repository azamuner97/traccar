/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Member rows (tc_game_session_members) with their member state rows (tc_game_member_state). */
public final class GameMemberRepository {

    private static final String SELECT = "SELECT m.id, m.gamesessionid, m.role, m.number, m.displayname, m.userid,"
            + " m.trackerid, m.state, m.createdat, m.retiredat, st.revision AS staterevision"
            + " FROM tc_game_session_members m LEFT JOIN tc_game_member_state st ON st.memberid = m.id";

    private GameMemberRepository() {
    }

    private static GameMember row(ResultSet rows) throws SQLException {
        return new GameMember(rows.getLong("id"), rows.getLong("gamesessionid"), rows.getString("role"),
                rows.getInt("number"), rows.getString("displayname"), GameTransaction.longOrNull(rows, "userid"),
                GameTransaction.longOrNull(rows, "trackerid"), rows.getString("state"),
                GameTime.get(rows, "createdat"), GameTime.get(rows, "retiredat"),
                GameTransaction.longOrNull(rows, "staterevision"));
    }

    public static Optional<GameMember> find(GameTransaction transaction, long sessionId, long memberId)
            throws SQLException {
        return transaction.first(SELECT + " WHERE m.gamesessionid = ? AND m.id = ?", GameMemberRepository::row,
                sessionId, memberId);
    }

    /**
     * The calling member, read again inside a write that holds the session lock: still active and still bound to the
     * account it was resolved from. A rebinding or retirement that committed meanwhile leaves the former account
     * without membership (403), so it never acts for a member it no longer has.
     */
    public static GameMember caller(GameTransaction transaction, GameMember resolved) throws SQLException {
        return find(transaction, resolved.gameSessionId(), resolved.id())
                .filter(member -> member.active() && member.userId() != null
                        && member.userId().equals(resolved.userId()))
                .orElseThrow(() -> new jakarta.ws.rs.ForbiddenException("This account does not take part in a game"));
    }

    public static List<GameMember> list(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.list(SELECT + " WHERE m.gamesessionid = ? ORDER BY m.id", GameMemberRepository::row,
                sessionId);
    }

    public static List<Long> memberIds(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.list("SELECT id FROM tc_game_session_members WHERE gamesessionid = ? ORDER BY id",
                rows -> rows.getLong(1), sessionId);
    }

    /** The member an account is bound to, in any session; at most one exists. */
    public static Optional<GameMember> byUser(GameTransaction transaction, long userId) throws SQLException {
        return transaction.first(SELECT + " WHERE m.userid = ?", GameMemberRepository::row, userId);
    }

    /** The member of a session a tracker is bound to; at most one exists. */
    public static Optional<GameMember> byTracker(GameTransaction transaction, long sessionId, long trackerId)
            throws SQLException {
        return transaction.first(SELECT + " WHERE m.gamesessionid = ? AND m.trackerid = ?",
                GameMemberRepository::row, sessionId, trackerId);
    }

    /** Whether a role number was ever used in a session, by an active or a retired member. */
    public static boolean numberUsed(GameTransaction transaction, long sessionId, String role, int number)
            throws SQLException {
        return transaction.exists("SELECT 1 FROM tc_game_session_members"
                + " WHERE gamesessionid = ? AND role = ? AND number = ?", sessionId, role, number);
    }

    /** The next number of a role that was never used in a session. */
    public static int nextNumber(GameTransaction transaction, long sessionId, String role) throws SQLException {
        return transaction.first("SELECT MAX(number) FROM tc_game_session_members WHERE gamesessionid = ? AND role = ?",
                rows -> rows.getInt(1), sessionId, role).orElse(0) + 1;
    }

    /** Inserts an active member without bindings, with a member state row for players and supporters. */
    public static long insert(GameTransaction transaction, long sessionId, String role, int number, String displayName)
            throws SQLException {
        long id = transaction.insert("INSERT INTO tc_game_session_members"
                + " (gamesessionid, role, number, displayname, state, createdat) VALUES (?, ?, ?, ?, ?, ?)",
                sessionId, role, number, displayName, GameMember.ACTIVE, transaction.now());
        Long revision = null;
        if (GameMember.hasState(role)) {
            transaction.update("INSERT INTO tc_game_member_state (memberid, gamesessionid, revision, updatedat)"
                    + " VALUES (?, ?, 1, ?)", id, sessionId, transaction.now());
            revision = 1L;
        }
        transaction.createdMember(id, revision);
        return id;
    }

    public static void setUser(GameTransaction transaction, long sessionId, long memberId, Long userId)
            throws SQLException {
        transaction.update("UPDATE tc_game_session_members SET userid = ? WHERE gamesessionid = ? AND id = ?",
                userId, sessionId, memberId);
    }

    public static void setTracker(GameTransaction transaction, long sessionId, long memberId, Long trackerId)
            throws SQLException {
        transaction.update("UPDATE tc_game_session_members SET trackerid = ? WHERE gamesessionid = ? AND id = ?",
                trackerId, sessionId, memberId);
    }

    public static void setDisplayName(GameTransaction transaction, long sessionId, long memberId, String displayName)
            throws SQLException {
        transaction.update("UPDATE tc_game_session_members SET displayname = ? WHERE gamesessionid = ? AND id = ?",
                displayName, sessionId, memberId);
    }

    /** Retires a member: its bindings are cleared, its state and history stay. */
    public static void retire(GameTransaction transaction, long sessionId, long memberId) throws SQLException {
        transaction.update("UPDATE tc_game_session_members SET state = ?, retiredat = ?, userid = NULL,"
                + " trackerid = NULL WHERE gamesessionid = ? AND id = ?",
                GameMember.RETIRED, transaction.now(), sessionId, memberId);
    }

    public static boolean userExists(GameTransaction transaction, long userId) throws SQLException {
        return transaction.exists("SELECT 1 FROM tc_users WHERE id = ?", userId);
    }

    public static boolean deviceExists(GameTransaction transaction, long deviceId) throws SQLException {
        return transaction.exists("SELECT 1 FROM tc_devices WHERE id = ?", deviceId);
    }

}
