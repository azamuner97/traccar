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

/** The published timetable (tc_game_timetables) and its entries (tc_game_timetable_entries). */
public final class GameTimetableRepository {

    /** The header row: revision, the stored headers document and the source document. */
    public record Header(long revision, String headers, String source, Instant updatedAt) {
    }

    /** One participant cell of one timed row, for one member. */
    public record Entry(long id, long memberId, Instant scheduledAt, String content, boolean active, long revision,
                        String status, Instant statusAt) {
    }

    private static final String ENTRIES = "SELECT id, memberid, scheduledat, content, active, revision, status,"
            + " statusat FROM tc_game_timetable_entries";

    private GameTimetableRepository() {
    }

    private static Entry entry(ResultSet rows) throws SQLException {
        return new Entry(rows.getLong("id"), rows.getLong("memberid"), GameTime.get(rows, "scheduledat"),
                rows.getString("content"), rows.getBoolean("active"), rows.getLong("revision"),
                rows.getString("status"), GameTime.get(rows, "statusat"));
    }

    public static Optional<Header> header(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.first("SELECT revision, headers, source, updatedat FROM tc_game_timetables"
                + " WHERE gamesessionid = ?", rows -> new Header(rows.getLong(1), rows.getString(2),
                        rows.getString(3), GameTime.get(rows, "updatedat")), sessionId);
    }

    public static void putHeader(GameTransaction transaction, long sessionId, long revision, String headers,
                                 String source) throws SQLException {
        if (transaction.update("UPDATE tc_game_timetables SET revision = ?, headers = ?, source = ?, updatedat = ?"
                + " WHERE gamesessionid = ?", revision, headers, source, transaction.now(), sessionId) == 0) {
            transaction.update("INSERT INTO tc_game_timetables (gamesessionid, revision, headers, source, updatedat)"
                    + " VALUES (?, ?, ?, ?, ?)", sessionId, revision, headers, source, transaction.now());
        }
    }

    public static List<Entry> active(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.list(ENTRIES + " WHERE gamesessionid = ? AND active = ? ORDER BY scheduledat, memberid",
                GameTimetableRepository::entry, sessionId, true);
    }

    public static Optional<Entry> find(GameTransaction transaction, long sessionId, long id) throws SQLException {
        return transaction.first(ENTRIES + " WHERE gamesessionid = ? AND id = ?", GameTimetableRepository::entry,
                sessionId, id);
    }

    /** Deactivates every active entry; publishing activates the entries of the new version again. */
    public static void deactivateAll(GameTransaction transaction, long sessionId, long revision) throws SQLException {
        transaction.update("UPDATE tc_game_timetable_entries SET active = ?, revision = ?"
                + " WHERE gamesessionid = ? AND active = ?", false, revision, sessionId, true);
    }

    /**
     * Activates the entry of a slot with new content, keeping its ID and progress, or inserts a scheduled one.
     */
    public static void upsert(GameTransaction transaction, long sessionId, Instant scheduledAt, long memberId,
                              String content, long revision) throws SQLException {
        if (transaction.update("UPDATE tc_game_timetable_entries SET content = ?, active = ?, revision = ?"
                + " WHERE gamesessionid = ? AND scheduledat = ? AND memberid = ?",
                content, true, revision, sessionId, scheduledAt, memberId) == 0) {
            transaction.insert("INSERT INTO tc_game_timetable_entries"
                    + " (gamesessionid, memberid, scheduledat, content, active, revision, status)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)", sessionId, memberId, scheduledAt, content, true, revision,
                    GameTimetableService.SCHEDULED);
        }
    }

    public static int progress(GameTransaction transaction, long sessionId, long id, String status)
            throws SQLException {
        return transaction.update("UPDATE tc_game_timetable_entries SET status = ?, statusat = ?"
                + " WHERE gamesessionid = ? AND id = ?", status, transaction.now(), sessionId, id);
    }

    /**
     * Active entries of active members around {@code now}: the past ones from {@code since} (newest first) or the
     * upcoming ones (oldest first), optionally of one member.
     */
    public static List<Entry> window(GameTransaction transaction, long sessionId, Long memberId, Instant since,
                                     Instant now, boolean past) throws SQLException {
        String sql = "SELECT e.id, e.memberid, e.scheduledat, e.content, e.active, e.revision, e.status, e.statusat"
                + " FROM tc_game_timetable_entries e JOIN tc_game_session_members m ON m.id = e.memberid"
                + " WHERE e.gamesessionid = ? AND e.active = ? AND m.state = ?"
                + (memberId == null ? "" : " AND e.memberid = ?")
                + (past ? " AND e.scheduledat >= ? AND e.scheduledat < ? ORDER BY e.scheduledat DESC, e.id DESC"
                        : " AND e.scheduledat >= ? ORDER BY e.scheduledat, e.id");
        List<Object> params = new java.util.ArrayList<>(List.of(sessionId, true, GameMember.ACTIVE));
        if (memberId != null) {
            params.add(memberId);
        }
        if (past) {
            params.add(since);
        }
        params.add(now);
        return transaction.list(sql, GameTimetableRepository::entry, params.toArray());
    }

    /** A member's active entries scheduled after {@code after}, oldest first. */
    public static List<Entry> upcoming(GameTransaction transaction, long sessionId, long memberId, Instant after)
            throws SQLException {
        return transaction.list(ENTRIES + " WHERE gamesessionid = ? AND memberid = ? AND active = ?"
                + " AND scheduledat > ? ORDER BY scheduledat", GameTimetableRepository::entry,
                sessionId, memberId, true, after);
    }

}
