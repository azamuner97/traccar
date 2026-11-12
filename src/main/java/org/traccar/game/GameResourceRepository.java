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

/** Resource rows: tc_game_mirrors, tc_game_session_groups and tc_game_session_geofences. */
public final class GameResourceRepository {

    private static final String MIRRORS = "SELECT id, gamesessionid, deviceid, sourcememberid, destinationrole,"
            + " destinationmemberid, state FROM tc_game_mirrors";
    private static final String GROUPS = "SELECT id, gamesessionid, groupid, purpose, memberid, state"
            + " FROM tc_game_session_groups";
    private static final String GEOFENCES = "SELECT id, gamesessionid, geofenceid, plankey, purpose, state"
            + " FROM tc_game_session_geofences";

    private GameResourceRepository() {
    }

    /** The row table, its Traccar ID column, and the Traccar table that column references. */
    private record Table(String name, String column, String traccarTable) {
    }

    private static Table table(GameResource.Kind kind) {
        return switch (kind) {
            case MIRROR -> new Table("tc_game_mirrors", "deviceid", "tc_devices");
            case GROUP -> new Table("tc_game_session_groups", "groupid", "tc_groups");
            case GEOFENCE -> new Table("tc_game_session_geofences", "geofenceid", "tc_geofences");
        };
    }

    private static GameResource.Mirror mirror(ResultSet rows) throws SQLException {
        return new GameResource.Mirror(rows.getLong("id"), rows.getLong("gamesessionid"),
                GameTransaction.longOrNull(rows, "deviceid"), rows.getLong("sourcememberid"),
                rows.getString("destinationrole"), GameTransaction.longOrNull(rows, "destinationmemberid"),
                rows.getString("state"));
    }

    private static GameResource.Group group(ResultSet rows) throws SQLException {
        return new GameResource.Group(rows.getLong("id"), rows.getLong("gamesessionid"),
                GameTransaction.longOrNull(rows, "groupid"), rows.getString("purpose"),
                GameTransaction.longOrNull(rows, "memberid"), rows.getString("state"));
    }

    private static GameResource.Geofence geofence(ResultSet rows) throws SQLException {
        return new GameResource.Geofence(rows.getLong("id"), rows.getLong("gamesessionid"),
                GameTransaction.longOrNull(rows, "geofenceid"), rows.getString("plankey"), rows.getString("purpose"),
                rows.getString("state"));
    }

    // Any kind ----------------------------------------------------------------------------------------------

    public static Optional<? extends GameResource> find(
            GameTransaction transaction, GameResource.Kind kind, long sessionId, long id) throws SQLException {
        String where = " WHERE gamesessionid = ? AND id = ?";
        return switch (kind) {
            case MIRROR -> transaction.first(MIRRORS + where, GameResourceRepository::mirror, sessionId, id);
            case GROUP -> transaction.first(GROUPS + where, GameResourceRepository::group, sessionId, id);
            case GEOFENCE -> transaction.first(GEOFENCES + where, GameResourceRepository::geofence, sessionId, id);
        };
    }

    /** The session row that owns a Traccar resource, in any session. */
    public static Optional<Long> owner(GameTransaction transaction, GameResource.Kind kind, long resourceId)
            throws SQLException {
        Table table = table(kind);
        return transaction.first("SELECT gamesessionid FROM " + table.name() + " WHERE " + table.column() + " = ?",
                rows -> rows.getLong(1), resourceId);
    }

    public static boolean traccarExists(GameTransaction transaction, GameResource.Kind kind, long resourceId)
            throws SQLException {
        return transaction.exists("SELECT 1 FROM " + table(kind).traccarTable() + " WHERE id = ?", resourceId);
    }

    public static void setState(GameTransaction transaction, GameResource.Kind kind, long sessionId, long id,
                                String state) throws SQLException {
        transaction.update("UPDATE " + table(kind).name() + " SET state = ? WHERE gamesessionid = ? AND id = ?",
                state, sessionId, id);
    }

    public static void setResource(GameTransaction transaction, GameResource.Kind kind, long sessionId, long id,
                                   long resourceId) throws SQLException {
        Table table = table(kind);
        transaction.update("UPDATE " + table.name() + " SET " + table.column() + " = ?"
                + " WHERE gamesessionid = ? AND id = ?", resourceId, sessionId, id);
    }

    public static void delete(GameTransaction transaction, GameResource.Kind kind, long sessionId, long id)
            throws SQLException {
        transaction.update("DELETE FROM " + table(kind).name() + " WHERE gamesessionid = ? AND id = ?",
                sessionId, id);
    }

    // Mirrors -----------------------------------------------------------------------------------------------

    public static List<GameResource.Mirror> mirrors(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.list(MIRRORS + " WHERE gamesessionid = ? ORDER BY id", GameResourceRepository::mirror,
                sessionId);
    }

    /** The mirror row of a device, in any session. */
    public static Optional<GameResource.Mirror> mirrorByDevice(GameTransaction transaction, long deviceId)
            throws SQLException {
        return transaction.first(MIRRORS + " WHERE deviceid = ?", GameResourceRepository::mirror, deviceId);
    }

    /** The mirror of a route: a source member to a role, or to one member. */
    public static Optional<GameResource.Mirror> mirrorByRoute(GameTransaction transaction, long sessionId,
                                                              long sourceMemberId, String destinationRole,
                                                              Long destinationMemberId) throws SQLException {
        return destinationRole != null
                ? transaction.first(MIRRORS + " WHERE gamesessionid = ? AND sourcememberid = ?"
                        + " AND destinationrole = ?", GameResourceRepository::mirror,
                        sessionId, sourceMemberId, destinationRole)
                : transaction.first(MIRRORS + " WHERE gamesessionid = ? AND sourcememberid = ?"
                        + " AND destinationmemberid = ?", GameResourceRepository::mirror,
                        sessionId, sourceMemberId, destinationMemberId);
    }

    public static long insertMirror(GameTransaction transaction, long sessionId, long deviceId, long sourceMemberId,
                                    String destinationRole, Long destinationMemberId) throws SQLException {
        return transaction.insert("INSERT INTO tc_game_mirrors"
                + " (gamesessionid, deviceid, sourcememberid, destinationrole, destinationmemberid, state)"
                + " VALUES (?, ?, ?, ?, ?, ?)", sessionId, deviceId, sourceMemberId, destinationRole,
                destinationMemberId, GameResource.ACTIVE);
    }

    // Groups ------------------------------------------------------------------------------------------------

    public static List<GameResource.Group> groups(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.list(GROUPS + " WHERE gamesessionid = ? ORDER BY id", GameResourceRepository::group,
                sessionId);
    }

    /**
     * The group of a purpose, and of a member for per-member purposes. The unique constraint covers only rows with
     * a member, because NULL never equals NULL; session-level purposes rely on this lookup under the session lock.
     */
    public static Optional<GameResource.Group> groupByPurpose(GameTransaction transaction, long sessionId,
                                                              String purpose, Long memberId) throws SQLException {
        return memberId == null
                ? transaction.first(GROUPS + " WHERE gamesessionid = ? AND purpose = ? AND memberid IS NULL",
                        GameResourceRepository::group, sessionId, purpose)
                : transaction.first(GROUPS + " WHERE gamesessionid = ? AND purpose = ? AND memberid = ?",
                        GameResourceRepository::group, sessionId, purpose, memberId);
    }

    public static long insertGroup(GameTransaction transaction, long sessionId, long groupId, String purpose,
                                   Long memberId) throws SQLException {
        return transaction.insert("INSERT INTO tc_game_session_groups"
                + " (gamesessionid, groupid, purpose, memberid, state) VALUES (?, ?, ?, ?, ?)",
                sessionId, groupId, purpose, memberId, GameResource.ACTIVE);
    }

    // Geofences ---------------------------------------------------------------------------------------------

    public static List<GameResource.Geofence> geofences(GameTransaction transaction, long sessionId)
            throws SQLException {
        return transaction.list(GEOFENCES + " WHERE gamesessionid = ? ORDER BY id",
                GameResourceRepository::geofence, sessionId);
    }

    public static Optional<GameResource.Geofence> geofenceByPlanKey(GameTransaction transaction, long sessionId,
                                                                    String planKey) throws SQLException {
        return transaction.first(GEOFENCES + " WHERE gamesessionid = ? AND plankey = ?",
                GameResourceRepository::geofence, sessionId, planKey);
    }

    public static long insertGeofence(GameTransaction transaction, long sessionId, long geofenceId, String planKey,
                                      String purpose) throws SQLException {
        return transaction.insert("INSERT INTO tc_game_session_geofences"
                + " (gamesessionid, geofenceid, plankey, purpose, state) VALUES (?, ?, ?, ?, ?)",
                sessionId, geofenceId, planKey, purpose, GameResource.ACTIVE);
    }

}
