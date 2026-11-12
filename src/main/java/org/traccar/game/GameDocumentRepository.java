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
 * Revisioned session documents: credentials (tc_game_session_credentials), geofence plans (tc_game_geofence_plans)
 * and component checkpoints (tc_game_session_checkpoints). Revision 0 means that a document does not exist yet.
 */
public final class GameDocumentRepository {

    /** A stored document: its revision and its text (ciphertext for credentials). */
    public record Stored(long revision, String text) {
    }

    /** A stored checkpoint row. */
    public record CheckpointRow(String component, int schemaVersion, long revision, String document,
                                Instant updatedAt) {
    }

    private GameDocumentRepository() {
    }

    // Credentials -------------------------------------------------------------------------------------------

    public static Optional<Stored> credentials(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.first("SELECT revision, ciphertext FROM tc_game_session_credentials"
                + " WHERE gamesessionid = ?", rows -> new Stored(rows.getLong(1), rows.getString(2)), sessionId);
    }

    public static void putCredentials(GameTransaction transaction, long sessionId, long revision, String ciphertext)
            throws SQLException {
        if (transaction.update("UPDATE tc_game_session_credentials SET revision = ?, ciphertext = ?"
                + " WHERE gamesessionid = ?", revision, ciphertext, sessionId) == 0) {
            transaction.update("INSERT INTO tc_game_session_credentials (gamesessionid, revision, ciphertext)"
                    + " VALUES (?, ?, ?)", sessionId, revision, ciphertext);
        }
    }

    // Geofence plans ----------------------------------------------------------------------------------------

    public static Optional<Stored> geofencePlans(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.first("SELECT revision, data FROM tc_game_geofence_plans WHERE gamesessionid = ?",
                rows -> new Stored(rows.getLong(1), rows.getString(2)), sessionId);
    }

    public static void putGeofencePlans(GameTransaction transaction, long sessionId, long revision, String data)
            throws SQLException {
        if (transaction.update("UPDATE tc_game_geofence_plans SET revision = ?, data = ?, updatedat = ?"
                + " WHERE gamesessionid = ?", revision, data, transaction.now(), sessionId) == 0) {
            transaction.update("INSERT INTO tc_game_geofence_plans (gamesessionid, revision, data, updatedat)"
                    + " VALUES (?, ?, ?, ?)", sessionId, revision, data, transaction.now());
        }
    }

    // Checkpoints -------------------------------------------------------------------------------------------

    private static final String CHECKPOINTS = "SELECT component, schemaversion, revision, document, updatedat"
            + " FROM tc_game_session_checkpoints";

    private static CheckpointRow checkpoint(java.sql.ResultSet rows) throws SQLException {
        return new CheckpointRow(rows.getString("component"), rows.getInt("schemaversion"), rows.getLong("revision"),
                rows.getString("document"), GameTime.get(rows, "updatedat"));
    }

    public static Optional<CheckpointRow> checkpoint(GameTransaction transaction, long sessionId, String component)
            throws SQLException {
        return transaction.first(CHECKPOINTS + " WHERE gamesessionid = ? AND component = ?",
                GameDocumentRepository::checkpoint, sessionId, component);
    }

    public static List<CheckpointRow> checkpoints(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.list(CHECKPOINTS + " WHERE gamesessionid = ? ORDER BY component",
                GameDocumentRepository::checkpoint, sessionId);
    }

    public static void putCheckpoint(GameTransaction transaction, long sessionId, String component, long revision,
                                     int schemaVersion, String document) throws SQLException {
        if (transaction.update("UPDATE tc_game_session_checkpoints SET revision = ?, schemaversion = ?, document = ?,"
                + " updatedat = ? WHERE gamesessionid = ? AND component = ?",
                revision, schemaVersion, document, transaction.now(), sessionId, component) == 0) {
            transaction.update("INSERT INTO tc_game_session_checkpoints"
                    + " (gamesessionid, component, schemaversion, revision, document, updatedat)"
                    + " VALUES (?, ?, ?, ?, ?, ?)", sessionId, component, schemaVersion, revision, document,
                    transaction.now());
        }
    }

    public static int deleteCheckpoint(GameTransaction transaction, long sessionId, String component)
            throws SQLException {
        return transaction.update("DELETE FROM tc_game_session_checkpoints WHERE gamesessionid = ? AND component = ?",
                sessionId, component);
    }

}
