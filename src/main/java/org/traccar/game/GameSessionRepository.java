/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Session identity and settings rows (tc_game_sessions, tc_game_session_settings). */
public final class GameSessionRepository {

    /**
     * Every session-scoped game table, children before the tables they reference. Foreign keys between game tables
     * have no referential action, so deleting a session deletes its rows in this order. Rows outside this list
     * follow their own foreign keys: drawings cascade, and the lease and controller history keep their rows with
     * the session reference cleared.
     */
    public static final List<String> DELETE_ORDER = List.of(
            "tc_game_push_outbox",
            "tc_game_deliveries",
            "tc_game_live_uploads",
            "tc_game_speedhunt_pings",
            "tc_game_fake_ping_plans",
            "tc_game_speedhunts",
            "tc_game_breaks",
            "tc_game_catches",
            "tc_game_member_state",
            "tc_game_joker_ledger",
            "tc_game_joker_reservations",
            "tc_game_joker_pools",
            "tc_game_jokers",
            "tc_game_requests",
            "tc_game_timetable_entries",
            "tc_game_timetables",
            "tc_game_notifications",
            "tc_game_notification_state",
            "tc_game_proximity_circle_members",
            "tc_game_proximity_circles",
            "tc_game_proximity_grants",
            "tc_game_proximity_state",
            "tc_game_mirrors",
            "tc_game_session_groups",
            "tc_game_session_geofences",
            "tc_game_geofence_plans",
            "tc_game_session_members",
            "tc_game_session_checkpoints",
            "tc_game_session_credentials",
            "tc_game_session_logs",
            "tc_game_session_settings");

    /** A session row with its settings row. */
    public record Row(long id, String name, int schemaVersion, long revision, Instant createdAt, Instant updatedAt,
                      int settingsSchemaVersion, long settingsRevision, String settings, Instant settingsUpdatedAt) {
    }

    private static final String SELECT = "SELECT s.id, s.name, s.schemaversion, s.revision, s.createdat,"
            + " s.updatedat, t.schemaversion AS settingsschemaversion, t.revision AS settingsrevision, t.settings,"
            + " t.updatedat AS settingsupdatedat"
            + " FROM tc_game_sessions s JOIN tc_game_session_settings t ON t.gamesessionid = s.id";

    private GameSessionRepository() {
    }

    private static Row row(ResultSet rows) throws SQLException {
        return new Row(rows.getLong("id"), rows.getString("name"), rows.getInt("schemaversion"),
                rows.getLong("revision"), GameTime.get(rows, "createdat"), GameTime.get(rows, "updatedat"),
                rows.getInt("settingsschemaversion"), rows.getLong("settingsrevision"), rows.getString("settings"),
                GameTime.get(rows, "settingsupdatedat"));
    }

    public static Optional<Row> find(GameTransaction transaction, long id) throws SQLException {
        return transaction.first(SELECT + " WHERE s.id = ?", GameSessionRepository::row, id);
    }

    public static List<Row> list(GameTransaction transaction) throws SQLException {
        return transaction.list(SELECT + " ORDER BY s.name, s.id", GameSessionRepository::row);
    }

    /** The session that uses a name, other than the given one. */
    public static Optional<Long> named(GameTransaction transaction, String name, long except) throws SQLException {
        return transaction.first("SELECT id FROM tc_game_sessions WHERE name = ? AND id <> ?",
                rows -> rows.getLong(1), name, except);
    }

    /** Inserts a session at revision 1 with its settings at revision 1. */
    public static long insert(GameTransaction transaction, String name, int schemaVersion, int settingsSchemaVersion,
                              String settings) throws SQLException {
        Instant now = transaction.now();
        long id = transaction.insert("INSERT INTO tc_game_sessions"
                + " (name, schemaversion, revision, createdat, updatedat) VALUES (?, ?, 1, ?, ?)",
                name, schemaVersion, now, now);
        transaction.update("INSERT INTO tc_game_session_settings (gamesessionid, schemaversion, revision, settings,"
                + " updatedat) VALUES (?, ?, 1, ?, ?)", id, settingsSchemaVersion, settings, now);
        return id;
    }

    public static void rename(GameTransaction transaction, long id, String name) throws SQLException {
        transaction.update("UPDATE tc_game_sessions SET name = ? WHERE id = ?", name, id);
    }

    public static void updateSettings(GameTransaction transaction, long id, long revision, int schemaVersion,
                                      String settings) throws SQLException {
        transaction.update("UPDATE tc_game_session_settings SET schemaversion = ?, revision = ?, settings = ?,"
                + " updatedat = ? WHERE gamesessionid = ?", schemaVersion, revision, settings, transaction.now(), id);
    }

    /** Advances the settings revision without changing the settings, for configuration kept on the session row. */
    public static void advanceSettings(GameTransaction transaction, long id, long revision) throws SQLException {
        transaction.update("UPDATE tc_game_session_settings SET revision = ?, updatedat = ?"
                + " WHERE gamesessionid = ?", revision, transaction.now(), id);
    }

    /** Deletes every row of a locked session, in {@link #DELETE_ORDER}, and then the session. */
    public static void delete(GameTransaction transaction, long id) throws SQLException {
        if (transaction.session() != id) {
            throw new IllegalStateException("Lock the game session before deleting it");
        }
        for (String table : DELETE_ORDER) {
            transaction.update("DELETE FROM " + table + " WHERE gamesessionid = ?", id);
        }
        transaction.update("DELETE FROM tc_game_sessions WHERE id = ?", id);
    }

}
