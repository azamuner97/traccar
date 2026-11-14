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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The server-run timetable (plan 1.1): the header with the plan revision and the execution toggle
 * (tc_game_timetables), the participant columns (tc_game_timetable_participants), the slots
 * (tc_game_timetable_slots), the cells (tc_game_timetable_entries) and the recorded changesets
 * (tc_game_timetable_changes).
 */
public final class GameTimetableRepository {

    public static final String SCHEDULED = "scheduled";
    public static final String CLAIMED = "claimed";
    public static final String DONE = "done";
    public static final String REMOVED = "removed";

    public static final String SENT = "sent";
    public static final String SKIPPED = "skipped";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";

    /** The header: plan revision and execution toggle; a session without a row has revision 0 and is off. */
    public record Header(long revision, boolean enabled, Instant enabledChangedAt, Instant updatedAt) {
        static final Header EMPTY = new Header(0, false, null, null);
    }

    public record Participant(long memberId, int ordinal) {
    }

    public record Slot(long id, Instant scheduledAt, String state, Instant claimedAt, Instant finishedAt) {
        public boolean scheduled() {
            return SCHEDULED.equals(state);
        }
    }

    /** One cell with its slot's time and state. */
    public record Entry(long id, long slotId, long memberId, String content, String status, Instant statusAt,
                        String reason, String detail, Instant scheduledAt, String slotState) {

        /** Not cancelled, in a slot that is not removed: a cell that exists. */
        public boolean present() {
            return !CANCELLED.equals(status) && !REMOVED.equals(slotState);
        }

        /** Still waiting for its outcome. */
        public boolean open() {
            return SCHEDULED.equals(status);
        }
    }

    private static final String ENTRIES = "SELECT e.id, e.slotid, e.memberid, e.content, e.status, e.statusat,"
            + " e.reason, e.detail, s.scheduledat, s.state AS slotstate FROM tc_game_timetable_entries e"
            + " JOIN tc_game_timetable_slots s ON s.gamesessionid = e.gamesessionid AND s.id = e.slotid";

    private static final String SLOTS = "SELECT id, scheduledat, state, claimedat, finishedat"
            + " FROM tc_game_timetable_slots";

    private GameTimetableRepository() {
    }

    private static Entry entry(ResultSet rows) throws SQLException {
        return new Entry(rows.getLong("id"), rows.getLong("slotid"), rows.getLong("memberid"),
                rows.getString("content"), rows.getString("status"), GameTime.get(rows, "statusat"),
                rows.getString("reason"), rows.getString("detail"), GameTime.get(rows, "scheduledat"),
                rows.getString("slotstate"));
    }

    private static Slot slot(ResultSet rows) throws SQLException {
        return new Slot(rows.getLong("id"), GameTime.get(rows, "scheduledat"), rows.getString("state"),
                GameTime.get(rows, "claimedat"), GameTime.get(rows, "finishedat"));
    }

    // Header ------------------------------------------------------------------------------------------------

    public static Header header(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.first("SELECT revision, enabled, enabledchangedat, updatedat FROM tc_game_timetables"
                + " WHERE gamesessionid = ?", rows -> new Header(rows.getLong(1), rows.getBoolean(2),
                        GameTime.get(rows, "enabledchangedat"), GameTime.get(rows, "updatedat")), sessionId)
                .orElse(Header.EMPTY);
    }

    private static void ensureHeader(GameTransaction transaction, long sessionId) throws SQLException {
        if (!transaction.exists("SELECT 1 FROM tc_game_timetables WHERE gamesessionid = ?", sessionId)) {
            transaction.update("INSERT INTO tc_game_timetables (gamesessionid, revision, enabled, updatedat)"
                    + " VALUES (?, 0, ?, ?)", sessionId, false, transaction.now());
        }
    }

    /** Advances the plan revision (edits only, never execution) and returns it. */
    public static long bumpRevision(GameTransaction transaction, long sessionId) throws SQLException {
        ensureHeader(transaction, sessionId);
        long revision = header(transaction, sessionId).revision() + 1;
        transaction.update("UPDATE tc_game_timetables SET revision = ?, updatedat = ? WHERE gamesessionid = ?",
                revision, transaction.now(), sessionId);
        return revision;
    }

    public static void setEnabled(GameTransaction transaction, long sessionId, boolean enabled) throws SQLException {
        ensureHeader(transaction, sessionId);
        transaction.update("UPDATE tc_game_timetables SET enabled = ?, enabledchangedat = ?"
                + " WHERE gamesessionid = ?", enabled, transaction.now(), sessionId);
    }

    // Participants ------------------------------------------------------------------------------------------

    public static List<Participant> participants(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.list("SELECT memberid, ordinal FROM tc_game_timetable_participants"
                + " WHERE gamesessionid = ? ORDER BY ordinal, memberid",
                rows -> new Participant(rows.getLong(1), rows.getInt(2)), sessionId);
    }

    /** Stores the column order: each member at its index. */
    public static void order(GameTransaction transaction, long sessionId, List<Long> memberIds) throws SQLException {
        List<Long> current = participants(transaction, sessionId).stream().map(Participant::memberId).toList();
        for (long memberId : current) {
            if (!memberIds.contains(memberId)) {
                transaction.update("DELETE FROM tc_game_timetable_participants WHERE gamesessionid = ?"
                        + " AND memberid = ?", sessionId, memberId);
            }
        }
        for (int i = 0; i < memberIds.size(); i++) {
            if (current.contains(memberIds.get(i))) {
                transaction.update("UPDATE tc_game_timetable_participants SET ordinal = ? WHERE gamesessionid = ?"
                        + " AND memberid = ?", i, sessionId, memberIds.get(i));
            } else {
                transaction.update("INSERT INTO tc_game_timetable_participants (gamesessionid, memberid, ordinal)"
                        + " VALUES (?, ?, ?)", sessionId, memberIds.get(i), i);
            }
        }
    }

    // Slots -------------------------------------------------------------------------------------------------

    public static Optional<Slot> slot(GameTransaction transaction, long sessionId, long slotId) throws SQLException {
        return transaction.first(SLOTS + " WHERE gamesessionid = ? AND id = ?", GameTimetableRepository::slot,
                sessionId, slotId);
    }

    /** Slots that are not removed, by time. */
    public static List<Slot> slots(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.list(SLOTS + " WHERE gamesessionid = ? AND state <> ? ORDER BY scheduledat, id",
                GameTimetableRepository::slot, sessionId, REMOVED);
    }

    /** Slots in one state, by time. */
    public static List<Slot> slots(GameTransaction transaction, long sessionId, String state) throws SQLException {
        return transaction.list(SLOTS + " WHERE gamesessionid = ? AND state = ? ORDER BY scheduledat, id",
                GameTimetableRepository::slot, sessionId, state);
    }

    /** The earliest scheduled slot. */
    public static Optional<Slot> nextScheduled(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.first(SLOTS + " WHERE gamesessionid = ? AND state = ? ORDER BY scheduledat, id",
                GameTimetableRepository::slot, sessionId, SCHEDULED);
    }

    /** Whether a slot other than {@code except} is live (not removed) at this time. */
    public static boolean timeTaken(GameTransaction transaction, long sessionId, Instant time, Long except)
            throws SQLException {
        return transaction.exists("SELECT 1 FROM tc_game_timetable_slots WHERE gamesessionid = ? AND activeat = ?"
                + (except == null ? "" : " AND id <> ?"), except == null ? new Object[] {sessionId, time}
                : new Object[] {sessionId, time, except});
    }

    public static long insertSlot(GameTransaction transaction, long sessionId, Instant scheduledAt)
            throws SQLException {
        return transaction.insert("INSERT INTO tc_game_timetable_slots (gamesessionid, scheduledat, state, activeat)"
                + " VALUES (?, ?, ?, ?)", sessionId, scheduledAt, SCHEDULED, scheduledAt);
    }

    public static void moveSlot(GameTransaction transaction, long sessionId, long slotId, Instant scheduledAt)
            throws SQLException {
        transaction.update("UPDATE tc_game_timetable_slots SET scheduledat = ?, activeat = ? WHERE gamesessionid = ?"
                + " AND id = ?", scheduledAt, scheduledAt, sessionId, slotId);
    }

    public static void removeSlot(GameTransaction transaction, long sessionId, long slotId) throws SQLException {
        transaction.update("UPDATE tc_game_timetable_slots SET state = ?, activeat = NULL, finishedat = ?"
                + " WHERE gamesessionid = ? AND id = ?", REMOVED, transaction.now(), sessionId, slotId);
    }

    /** Claims a scheduled slot for execution; false when it is no longer scheduled. */
    public static boolean claim(GameTransaction transaction, long sessionId, long slotId) throws SQLException {
        return transaction.update("UPDATE tc_game_timetable_slots SET state = ?, claimedat = ? WHERE gamesessionid = ?"
                + " AND id = ? AND state = ?", CLAIMED, transaction.now(), sessionId, slotId, SCHEDULED) == 1;
    }

    /** Marks a scheduled or claimed slot done. */
    public static void finish(GameTransaction transaction, long sessionId, long slotId) throws SQLException {
        transaction.update("UPDATE tc_game_timetable_slots SET state = ?, finishedat = ? WHERE gamesessionid = ?"
                + " AND id = ? AND state IN (?, ?)", DONE, transaction.now(), sessionId, slotId, SCHEDULED, CLAIMED);
    }

    // Entries -----------------------------------------------------------------------------------------------

    public static Optional<Entry> find(GameTransaction transaction, long sessionId, long entryId)
            throws SQLException {
        return transaction.first(ENTRIES + " WHERE e.gamesessionid = ? AND e.id = ?", GameTimetableRepository::entry,
                sessionId, entryId);
    }

    public static Optional<Entry> cell(GameTransaction transaction, long sessionId, long slotId, long memberId)
            throws SQLException {
        return transaction.first(ENTRIES + " WHERE e.gamesessionid = ? AND e.slotid = ? AND e.memberid = ?",
                GameTimetableRepository::entry, sessionId, slotId, memberId);
    }

    /** The entries of a slot, cancelled ones included, by member. */
    public static List<Entry> entries(GameTransaction transaction, long sessionId, long slotId) throws SQLException {
        return transaction.list(ENTRIES + " WHERE e.gamesessionid = ? AND e.slotid = ? ORDER BY e.id",
                GameTimetableRepository::entry, sessionId, slotId);
    }

    /** Every present entry (not cancelled, slot not removed), by slot time. */
    public static List<Entry> present(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.list(ENTRIES + " WHERE e.gamesessionid = ? AND e.status <> ? AND s.state <> ?"
                + " ORDER BY s.scheduledat, e.id", GameTimetableRepository::entry, sessionId, CANCELLED, REMOVED);
    }

    /** The number of present entries, for the limit. */
    public static int presentCount(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.first("SELECT COUNT(*) FROM tc_game_timetable_entries e JOIN tc_game_timetable_slots s"
                + " ON s.gamesessionid = e.gamesessionid AND s.id = e.slotid WHERE e.gamesessionid = ?"
                + " AND e.status <> ? AND s.state <> ?", rows -> rows.getInt(1), sessionId, CANCELLED, REMOVED)
                .orElse(0);
    }

    /** A member's present entries. */
    public static List<Entry> ofMember(GameTransaction transaction, long sessionId, long memberId)
            throws SQLException {
        return transaction.list(ENTRIES + " WHERE e.gamesessionid = ? AND e.memberid = ? AND e.status <> ?"
                + " AND s.state <> ? ORDER BY s.scheduledat", GameTimetableRepository::entry, sessionId, memberId,
                CANCELLED, REMOVED);
    }

    public static long insertEntry(GameTransaction transaction, long sessionId, long slotId, long memberId,
                                   String content) throws SQLException {
        return transaction.insert("INSERT INTO tc_game_timetable_entries (gamesessionid, slotid, memberid, content,"
                + " status, statusat) VALUES (?, ?, ?, ?, ?, ?)", sessionId, slotId, memberId, content, SCHEDULED,
                transaction.now());
    }

    /** Changes a cell's content; a cancelled cell becomes present again. */
    public static void setContent(GameTransaction transaction, long sessionId, long entryId, String content)
            throws SQLException {
        transaction.update("UPDATE tc_game_timetable_entries SET content = ?, status = ?, statusat = ?, reason = NULL,"
                + " detail = NULL WHERE gamesessionid = ? AND id = ?", content, SCHEDULED, transaction.now(),
                sessionId, entryId);
    }

    /** Records a cell's status: an outcome, or {@code cancelled}. */
    public static void setStatus(GameTransaction transaction, long sessionId, long entryId, String status,
                                 String reason, String detail) throws SQLException {
        transaction.update("UPDATE tc_game_timetable_entries SET status = ?, statusat = ?, reason = ?, detail = ?"
                + " WHERE gamesessionid = ? AND id = ?", status, transaction.now(), reason,
                GameJokerRepository.clip(detail, 512), sessionId, entryId);
    }

    /**
     * Present entries around {@code now} whose members are active: the past ones from {@code since} (newest first)
     * or the upcoming ones (oldest first), optionally of one member.
     */
    public static List<Entry> window(GameTransaction transaction, long sessionId, Long memberId, Instant since,
                                     Instant now, boolean past) throws SQLException {
        String sql = ENTRIES + " JOIN tc_game_session_members m ON m.gamesessionid = e.gamesessionid"
                + " AND m.id = e.memberid WHERE e.gamesessionid = ? AND e.status <> ? AND s.state <> ? AND m.state = ?"
                + (memberId == null ? "" : " AND e.memberid = ?")
                + (past ? " AND s.scheduledat >= ? AND s.scheduledat < ? ORDER BY s.scheduledat DESC, e.id DESC"
                        : " AND s.scheduledat >= ? ORDER BY s.scheduledat, e.id");
        List<Object> params = new ArrayList<>(List.of(sessionId, CANCELLED, REMOVED, GameMember.ACTIVE));
        if (memberId != null) {
            params.add(memberId);
        }
        if (past) {
            params.add(since);
        }
        params.add(now);
        return transaction.list(sql, GameTimetableRepository::entry, params.toArray());
    }

    /** A member's open entries in scheduled slots after {@code after}, oldest first. */
    public static List<Entry> upcoming(GameTransaction transaction, long sessionId, long memberId, Instant after)
            throws SQLException {
        return transaction.list(ENTRIES + " WHERE e.gamesessionid = ? AND e.memberid = ? AND e.status = ?"
                + " AND s.state = ? AND s.scheduledat > ? ORDER BY s.scheduledat", GameTimetableRepository::entry,
                sessionId, memberId, SCHEDULED, SCHEDULED, after);
    }

    // Changesets --------------------------------------------------------------------------------------------

    /** A recorded changeset: its request hash and result. */
    public record Change(String hash, String result) {
    }

    public static Optional<Change> change(GameTransaction transaction, long sessionId, String changeId)
            throws SQLException {
        return transaction.first("SELECT requesthash, result FROM tc_game_timetable_changes WHERE gamesessionid = ?"
                + " AND changeid = ?", rows -> new Change(rows.getString(1), rows.getString(2)), sessionId, changeId);
    }

    public static void recordChange(GameTransaction transaction, long sessionId, String changeId, String hash,
                                    String result) throws SQLException {
        transaction.update("INSERT INTO tc_game_timetable_changes (gamesessionid, changeid, requesthash, result,"
                + " createdat) VALUES (?, ?, ?, ?, ?)", sessionId, changeId, hash, result, transaction.now());
    }

    // Events ------------------------------------------------------------------------------------------------

    /** The ID of the session's latest execution event (any category), or 0. */
    public static long lastEventId(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.first("SELECT MAX(id) FROM tc_game_session_logs WHERE gamesessionid = ?"
                + " AND data IS NOT NULL", rows -> rows.getLong(1), sessionId).orElse(0L);
    }

}
