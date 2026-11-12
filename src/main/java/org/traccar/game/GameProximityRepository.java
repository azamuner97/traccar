/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Proximity rows of data model 2: the publication state (tc_game_proximity_state), temporary access grants
 * (tc_game_proximity_grants) and circles (tc_game_proximity_circles, tc_game_proximity_circle_members).
 *
 * <p>A grant names its recipient and target members and keeps the resolved {@code userid} and {@code deviceid}: the
 * access columns that Traccar's permission union reads.
 */
public final class GameProximityRepository {

    /**
     * The content hash of a state whose grants the server revoked after a binding change. It never equals the hash
     * of published content, so a retry of the publication made before the revocation cannot pass as already
     * applied.
     */
    public static final String REVOKED = "revoked";

    public record State(long revision, String contentHash, Instant updatedAt) {
    }

    public record Grant(String episodeId, long recipientMemberId, long targetMemberId, long userId, long deviceId,
                        Instant expiresAt) {
    }

    public record Circle(String circleId, double latitude, double longitude, double diameterMeters,
                         Instant expiresAt) {
    }

    private GameProximityRepository() {
    }

    public static Optional<State> state(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.first("SELECT revision, contenthash, updatedat FROM tc_game_proximity_state"
                + " WHERE gamesessionid = ?", rows -> new State(rows.getLong(1), rows.getString(2),
                        GameTime.get(rows, "updatedat")), sessionId);
    }

    public static void putState(GameTransaction transaction, long sessionId, long revision, String contentHash)
            throws SQLException {
        if (transaction.update("UPDATE tc_game_proximity_state SET revision = ?, contenthash = ?, updatedat = ?"
                + " WHERE gamesessionid = ?", revision, contentHash, transaction.now(), sessionId) == 0) {
            transaction.update("INSERT INTO tc_game_proximity_state (gamesessionid, revision, contenthash, updatedat)"
                    + " VALUES (?, ?, ?, ?)", sessionId, revision, contentHash, transaction.now());
        }
    }

    /** Grants of a session that have not expired at {@code now}, or all of them when {@code now} is null. */
    public static List<Grant> grants(GameTransaction transaction, long sessionId, Instant now) throws SQLException {
        return transaction.list("SELECT episodeid, recipientmemberid, targetmemberid, userid, deviceid, expiresat"
                + " FROM tc_game_proximity_grants WHERE gamesessionid = ?" + (now == null ? "" : " AND expiresat > ?")
                + " ORDER BY episodeid, recipientmemberid", rows -> new Grant(rows.getString(1), rows.getLong(2),
                        rows.getLong(3), rows.getLong(4), rows.getLong(5), GameTime.get(rows, "expiresat")),
                now == null ? new Object[] {sessionId} : new Object[] {sessionId, now});
    }

    public static List<Circle> circles(GameTransaction transaction, long sessionId, Instant now) throws SQLException {
        return transaction.list("SELECT circleid, latitude, longitude, diametermeters, expiresat"
                + " FROM tc_game_proximity_circles WHERE gamesessionid = ? AND expiresat > ? ORDER BY circleid",
                rows -> new Circle(rows.getString(1), rows.getDouble(2), rows.getDouble(3), rows.getDouble(4),
                        GameTime.get(rows, "expiresat")), sessionId, now);
    }

    public static List<Long> circleMembers(GameTransaction transaction, long sessionId, String circleId)
            throws SQLException {
        return transaction.list("SELECT memberid FROM tc_game_proximity_circle_members WHERE gamesessionid = ?"
                + " AND circleid = ? ORDER BY memberid", rows -> rows.getLong(1), sessionId, circleId);
    }

    /** Deletes the grants and circles of a session. */
    public static void clear(GameTransaction transaction, long sessionId) throws SQLException {
        transaction.update("DELETE FROM tc_game_proximity_grants WHERE gamesessionid = ?", sessionId);
        transaction.update("DELETE FROM tc_game_proximity_circle_members WHERE gamesessionid = ?", sessionId);
        transaction.update("DELETE FROM tc_game_proximity_circles WHERE gamesessionid = ?", sessionId);
    }

    public static void insertGrant(GameTransaction transaction, long sessionId, Grant grant) throws SQLException {
        transaction.update("INSERT INTO tc_game_proximity_grants (gamesessionid, recipientmemberid, targetmemberid,"
                + " episodeid, userid, deviceid, expiresat) VALUES (?, ?, ?, ?, ?, ?, ?)", sessionId,
                grant.recipientMemberId(), grant.targetMemberId(), grant.episodeId(), grant.userId(),
                grant.deviceId(), grant.expiresAt());
    }

    public static void insertCircle(GameTransaction transaction, long sessionId, Circle circle, List<Long> members)
            throws SQLException {
        transaction.update("INSERT INTO tc_game_proximity_circles (gamesessionid, circleid, latitude, longitude,"
                + " diametermeters, expiresat) VALUES (?, ?, ?, ?, ?, ?)", sessionId, circle.circleId(),
                circle.latitude(), circle.longitude(), circle.diameterMeters(), circle.expiresAt());
        for (long member : members) {
            transaction.update("INSERT INTO tc_game_proximity_circle_members (gamesessionid, circleid, memberid)"
                    + " VALUES (?, ?, ?)", sessionId, circle.circleId(), member);
        }
    }

    /** Recipient accounts of a session's grants. */
    public static List<Long> recipients(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.list("SELECT DISTINCT userid FROM tc_game_proximity_grants WHERE gamesessionid = ?",
                rows -> rows.getLong(1), sessionId);
    }

    /**
     * Revokes every grant in which a member is the recipient or the target, because the account or tracker those
     * grants resolved to is no longer the member's. When grants were revoked, the proximity state revision
     * advances and its content hash becomes {@link #REVOKED}, so the publisher's next write based on the old state
     * conflicts instead of overwriting, and a retry of an earlier write is not taken as already applied.
     *
     * @return the number of revoked grants
     */
    public static int revokeMember(GameTransaction transaction, long sessionId, long memberId) throws SQLException {
        int revoked = transaction.update("DELETE FROM tc_game_proximity_grants"
                + " WHERE gamesessionid = ? AND (recipientmemberid = ? OR targetmemberid = ?)",
                sessionId, memberId, memberId);
        if (revoked > 0) {
            transaction.update("UPDATE tc_game_proximity_state SET revision = revision + 1, contenthash = ?,"
                    + " updatedat = ? WHERE gamesessionid = ?", REVOKED, transaction.now(), sessionId);
        }
        return revoked;
    }

}
