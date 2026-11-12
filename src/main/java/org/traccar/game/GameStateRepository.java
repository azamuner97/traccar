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

/**
 * Rows of the typed game state: tc_game_catches, tc_game_speedhunts, tc_game_speedhunt_pings, tc_game_breaks and
 * tc_game_live_uploads. "At most one active" rules are enforced by the marker columns ({@code activememberid}).
 */
public final class GameStateRepository {

    private static final String CATCHES = "SELECT id, memberid, caughtat, releasedat, actor FROM tc_game_catches";
    private static final String RUNS = "SELECT id, targetmemberid, tohunters, toplayers, tosupporters, startedat,"
            + " endedat, outcome, reason, startedby, requestid, reservationid FROM tc_game_speedhunts";
    private static final String PINGS = "SELECT id, runid, number, mode, source, sentat, requestid, fakeplanid"
            + " FROM tc_game_speedhunt_pings";
    private static final String BREAKS = "SELECT id, memberid, state, plannedstart, startedat, deadline, endedat,"
            + " plannedby, requestid, reservationid FROM tc_game_breaks";
    private static final String UPLOADS = "SELECT id, purpose, sourcememberid, recipientmemberid, pingid, tohunters,"
            + " toplayers, tosupporters, intervalseconds, startedat, deadline, lastsampleat, endedat, state,"
            + " endreason, reason, controllerepoch, requestid, reservationid FROM tc_game_live_uploads";

    private GameStateRepository() {
    }

    private static GameState.Catch catchRow(ResultSet rows) throws SQLException {
        return new GameState.Catch(rows.getLong("id"), rows.getLong("memberid"), GameTime.get(rows, "caughtat"),
                GameTime.get(rows, "releasedat"), rows.getString("actor"));
    }

    private static GameState.Run run(ResultSet rows) throws SQLException {
        return new GameState.Run(rows.getLong("id"), rows.getLong("targetmemberid"), rows.getBoolean("tohunters"),
                rows.getBoolean("toplayers"), rows.getBoolean("tosupporters"), GameTime.get(rows, "startedat"),
                GameTime.get(rows, "endedat"), rows.getString("outcome"), rows.getString("reason"),
                rows.getString("startedby"), GameTransaction.longOrNull(rows, "requestid"),
                GameTransaction.longOrNull(rows, "reservationid"));
    }

    private static GameState.Ping ping(ResultSet rows) throws SQLException {
        return new GameState.Ping(rows.getLong("id"), rows.getLong("runid"), rows.getInt("number"),
                rows.getString("mode"), rows.getString("source"), GameTime.get(rows, "sentat"),
                GameTransaction.longOrNull(rows, "requestid"), GameTransaction.longOrNull(rows, "fakeplanid"));
    }

    private static GameState.Break pause(ResultSet rows) throws SQLException {
        return new GameState.Break(rows.getLong("id"), rows.getLong("memberid"), rows.getString("state"),
                GameTime.get(rows, "plannedstart"), GameTime.get(rows, "startedat"), GameTime.get(rows, "deadline"),
                GameTime.get(rows, "endedat"), rows.getString("plannedby"),
                GameTransaction.longOrNull(rows, "requestid"), GameTransaction.longOrNull(rows, "reservationid"));
    }

    private static GameState.LiveUpload upload(ResultSet rows) throws SQLException {
        return new GameState.LiveUpload(rows.getLong("id"), rows.getString("purpose"),
                GameTransaction.longOrNull(rows, "sourcememberid"),
                GameTransaction.longOrNull(rows, "recipientmemberid"), GameTransaction.longOrNull(rows, "pingid"),
                rows.getBoolean("tohunters"), rows.getBoolean("toplayers"), rows.getBoolean("tosupporters"),
                rows.getInt("intervalseconds"), GameTime.get(rows, "startedat"), GameTime.get(rows, "deadline"),
                GameTime.get(rows, "lastsampleat"), GameTime.get(rows, "endedat"), rows.getString("state"),
                rows.getString("endreason"), rows.getString("reason"), rows.getLong("controllerepoch"),
                GameTransaction.longOrNull(rows, "requestid"), GameTransaction.longOrNull(rows, "reservationid"));
    }

    // Catches -----------------------------------------------------------------------------------------------

    public static Optional<GameState.Catch> activeCatch(GameTransaction transaction, long sessionId, long memberId)
            throws SQLException {
        return transaction.first(CATCHES + " WHERE gamesessionid = ? AND activememberid = ?",
                GameStateRepository::catchRow, sessionId, memberId);
    }

    public static List<GameState.Catch> catches(GameTransaction transaction, long sessionId, boolean activeOnly)
            throws SQLException {
        return transaction.list(CATCHES + " WHERE gamesessionid = ?"
                + (activeOnly ? " AND activememberid IS NOT NULL" : "") + " ORDER BY id",
                GameStateRepository::catchRow, sessionId);
    }

    public static long insertCatch(GameTransaction transaction, long sessionId, long memberId, String actor)
            throws SQLException {
        return transaction.insert("INSERT INTO tc_game_catches (gamesessionid, memberid, caughtat, activememberid,"
                + " actor) VALUES (?, ?, ?, ?, ?)", sessionId, memberId, transaction.now(), memberId,
                GameJokerRepository.clip(actor, 128));
    }

    public static void release(GameTransaction transaction, long sessionId, long catchId) throws SQLException {
        transaction.update("UPDATE tc_game_catches SET releasedat = ?, activememberid = NULL"
                + " WHERE gamesessionid = ? AND id = ?", transaction.now(), sessionId, catchId);
    }

    // Runs and pings ----------------------------------------------------------------------------------------

    public static Optional<GameState.Run> run(GameTransaction transaction, long sessionId, long runId)
            throws SQLException {
        return transaction.first(RUNS + " WHERE gamesessionid = ? AND id = ?", GameStateRepository::run,
                sessionId, runId);
    }

    public static Optional<GameState.Run> activeRun(GameTransaction transaction, long sessionId, long memberId)
            throws SQLException {
        return transaction.first(RUNS + " WHERE gamesessionid = ? AND activememberid = ?", GameStateRepository::run,
                sessionId, memberId);
    }

    /** Runs of a session, newest first, optionally only active ones. */
    public static List<GameState.Run> runs(GameTransaction transaction, long sessionId, boolean activeOnly)
            throws SQLException {
        return transaction.list(RUNS + " WHERE gamesessionid = ?"
                + (activeOnly ? " AND activememberid IS NOT NULL" : "") + " ORDER BY startedat DESC, id DESC",
                GameStateRepository::run, sessionId);
    }

    /** The most recently started run of a session. */
    public static Optional<GameState.Run> lastRun(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.first(RUNS + " WHERE gamesessionid = ? ORDER BY startedat DESC, id DESC",
                GameStateRepository::run, sessionId);
    }

    public static long insertRun(GameTransaction transaction, long sessionId, long targetMemberId, boolean toHunters,
                                 boolean toPlayers, boolean toSupporters, String startedBy, Long requestId,
                                 Long reservationId) throws SQLException {
        return transaction.insert("INSERT INTO tc_game_speedhunts (gamesessionid, targetmemberid, tohunters,"
                + " toplayers, tosupporters, startedat, startedby, requestid, reservationid, activememberid)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", sessionId, targetMemberId, toHunters, toPlayers,
                toSupporters, transaction.now(), GameJokerRepository.clip(startedBy, 128), requestId, reservationId,
                targetMemberId);
    }

    public static void endRun(GameTransaction transaction, long sessionId, long runId, String outcome, String reason)
            throws SQLException {
        transaction.update("UPDATE tc_game_speedhunts SET endedat = ?, outcome = ?, reason = ?, activememberid = NULL"
                + " WHERE gamesessionid = ? AND id = ?", transaction.now(), outcome,
                GameJokerRepository.clip(reason, 512), sessionId, runId);
    }

    public static List<GameState.Ping> pings(GameTransaction transaction, long sessionId, long runId)
            throws SQLException {
        return transaction.list(PINGS + " WHERE gamesessionid = ? AND runid = ? ORDER BY number",
                GameStateRepository::ping, sessionId, runId);
    }

    public static Optional<GameState.Ping> ping(GameTransaction transaction, long sessionId, long pingId)
            throws SQLException {
        return transaction.first(PINGS + " WHERE gamesessionid = ? AND id = ?", GameStateRepository::ping,
                sessionId, pingId);
    }

    /** The highest delivered ping number of a run, 0 before its first ping. */
    public static int lastPingNumber(GameTransaction transaction, long sessionId, long runId) throws SQLException {
        return transaction.first("SELECT MAX(number) FROM tc_game_speedhunt_pings WHERE gamesessionid = ?"
                + " AND runid = ?", rows -> rows.getInt(1), sessionId, runId).orElse(0);
    }

    public static long insertPing(GameTransaction transaction, long sessionId, long runId, int number, String mode,
                                  String source, Long requestId, Long fakePlanId) throws SQLException {
        return transaction.insert("INSERT INTO tc_game_speedhunt_pings (gamesessionid, runid, number, mode, source,"
                + " sentat, requestid, fakeplanid) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", sessionId, runId, number, mode,
                source, transaction.now(), requestId, fakePlanId);
    }

    /** Per member: runs, delivered pings, live pings, and the start of the latest run. */
    public record Statistics(long memberId, int speedhunts, int pings, int livePings, Instant lastStartedAt) {
    }

    public static List<Statistics> statistics(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.list("SELECT s.targetmemberid, COUNT(DISTINCT s.id) AS runs, COUNT(p.id) AS pings,"
                + " SUM(CASE WHEN p.mode = 'live' THEN 1 ELSE 0 END) AS livepings, MAX(s.startedat) AS laststarted"
                + " FROM tc_game_speedhunts s LEFT JOIN tc_game_speedhunt_pings p"
                + " ON p.gamesessionid = s.gamesessionid AND p.runid = s.id"
                + " WHERE s.gamesessionid = ? GROUP BY s.targetmemberid ORDER BY s.targetmemberid",
                rows -> new Statistics(rows.getLong(1), rows.getInt(2), rows.getInt(3), rows.getInt(4),
                        GameTime.get(rows, "laststarted")), sessionId);
    }

    // Breaks ------------------------------------------------------------------------------------------------

    public static Optional<GameState.Break> pause(GameTransaction transaction, long sessionId, long breakId)
            throws SQLException {
        return transaction.first(BREAKS + " WHERE gamesessionid = ? AND id = ?", GameStateRepository::pause,
                sessionId, breakId);
    }

    public static Optional<GameState.Break> activeBreak(GameTransaction transaction, long sessionId, long memberId)
            throws SQLException {
        return transaction.first(BREAKS + " WHERE gamesessionid = ? AND activememberid = ?",
                GameStateRepository::pause, sessionId, memberId);
    }

    /** The break that a reservation holds the joker of. */
    public static Optional<GameState.Break> breakOfReservation(GameTransaction transaction, long sessionId,
                                                               long reservationId) throws SQLException {
        return transaction.first(BREAKS + " WHERE gamesessionid = ? AND reservationid = ?",
                GameStateRepository::pause, sessionId, reservationId);
    }

    /** Planned, waiting and active breaks of a session, optionally of one member. */
    public static List<GameState.Break> currentBreaks(GameTransaction transaction, long sessionId, Long memberId)
            throws SQLException {
        return memberId == null
                ? transaction.list(BREAKS + " WHERE gamesessionid = ? AND state IN (?, ?, ?) ORDER BY id",
                        GameStateRepository::pause, sessionId, GameState.PLANNED, GameState.WAITING,
                        GameState.ACTIVE)
                : transaction.list(BREAKS + " WHERE gamesessionid = ? AND memberid = ? AND state IN (?, ?, ?)"
                        + " ORDER BY id", GameStateRepository::pause, sessionId, memberId, GameState.PLANNED,
                        GameState.WAITING, GameState.ACTIVE);
    }

    public static List<GameState.Break> breaks(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.list(BREAKS + " WHERE gamesessionid = ? ORDER BY id", GameStateRepository::pause,
                sessionId);
    }

    public static long insertPlannedBreak(GameTransaction transaction, long sessionId, long memberId,
                                          Instant plannedStart, String plannedBy, Long requestId, long reservationId)
            throws SQLException {
        return transaction.insert("INSERT INTO tc_game_breaks (gamesessionid, memberid, state, plannedstart,"
                + " plannedby, requestid, reservationid) VALUES (?, ?, ?, ?, ?, ?, ?)", sessionId, memberId,
                GameState.PLANNED, plannedStart, plannedBy, requestId, reservationId);
    }

    public static long insertActiveBreak(GameTransaction transaction, long sessionId, long memberId,
                                         Instant deadline, String plannedBy, Long requestId, Long reservationId)
            throws SQLException {
        return transaction.insert("INSERT INTO tc_game_breaks (gamesessionid, memberid, state, startedat, deadline,"
                + " plannedby, requestid, reservationid, activememberid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                sessionId, memberId, GameState.ACTIVE, transaction.now(), deadline, plannedBy, requestId,
                reservationId, memberId);
    }

    public static void waitBreak(GameTransaction transaction, long sessionId, long breakId) throws SQLException {
        transaction.update("UPDATE tc_game_breaks SET state = ? WHERE gamesessionid = ? AND id = ?",
                GameState.WAITING, sessionId, breakId);
    }

    public static void startBreak(GameTransaction transaction, long sessionId, GameState.Break pause,
                                  Instant deadline) throws SQLException {
        transaction.update("UPDATE tc_game_breaks SET state = ?, startedat = ?, deadline = ?, activememberid = ?"
                + " WHERE gamesessionid = ? AND id = ?", GameState.ACTIVE, transaction.now(), deadline,
                pause.memberId(), sessionId, pause.id());
    }

    /** Ends a break: {@code ended} after it was active, {@code cancelled} or {@code missed} before it started. */
    public static void finishBreak(GameTransaction transaction, long sessionId, long breakId, String state)
            throws SQLException {
        transaction.update("UPDATE tc_game_breaks SET state = ?, endedat = ?, activememberid = NULL"
                + " WHERE gamesessionid = ? AND id = ?", state, transaction.now(), sessionId, breakId);
    }

    // Live uploads ------------------------------------------------------------------------------------------

    public static Optional<GameState.LiveUpload> upload(GameTransaction transaction, long sessionId, long id)
            throws SQLException {
        return transaction.first(UPLOADS + " WHERE gamesessionid = ? AND id = ?", GameStateRepository::upload,
                sessionId, id);
    }

    public static List<GameState.LiveUpload> activeUploads(GameTransaction transaction, long sessionId)
            throws SQLException {
        return transaction.list(UPLOADS + " WHERE gamesessionid = ? AND state = ? ORDER BY id",
                GameStateRepository::upload, sessionId, GameState.ACTIVE);
    }

    public static List<GameState.LiveUpload> uploads(GameTransaction transaction, long sessionId)
            throws SQLException {
        return transaction.list(UPLOADS + " WHERE gamesessionid = ? ORDER BY id", GameStateRepository::upload,
                sessionId);
    }

    public static long insertUpload(GameTransaction transaction, long sessionId, String purpose, Long sourceMemberId,
                                    Long recipientMemberId, boolean toHunters, boolean toPlayers,
                                    boolean toSupporters, int intervalSeconds, Instant deadline, String reason,
                                    long epoch, Long requestId) throws SQLException {
        return transaction.insert("INSERT INTO tc_game_live_uploads (gamesessionid, purpose, sourcememberid,"
                + " recipientmemberid, tohunters, toplayers, tosupporters, intervalseconds, startedat, deadline,"
                + " state, reason, controllerepoch, requestid) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                sessionId, purpose, sourceMemberId, recipientMemberId, toHunters, toPlayers, toSupporters,
                intervalSeconds, transaction.now(), deadline, GameState.ACTIVE, GameJokerRepository.clip(reason, 512),
                epoch, requestId);
    }

    public static void sample(GameTransaction transaction, long sessionId, long id, long epoch) throws SQLException {
        transaction.update("UPDATE tc_game_live_uploads SET lastsampleat = ?, controllerepoch = ?"
                + " WHERE gamesessionid = ? AND id = ?", transaction.now(), epoch, sessionId, id);
    }

    public static void linkPing(GameTransaction transaction, long sessionId, long id, long pingId)
            throws SQLException {
        transaction.update("UPDATE tc_game_live_uploads SET pingid = ? WHERE gamesessionid = ? AND id = ?",
                pingId, sessionId, id);
    }

    public static void linkReservation(GameTransaction transaction, long sessionId, long id, long reservationId)
            throws SQLException {
        transaction.update("UPDATE tc_game_live_uploads SET reservationid = ? WHERE gamesessionid = ? AND id = ?",
                reservationId, sessionId, id);
    }

    public static void endUpload(GameTransaction transaction, long sessionId, long id, String endReason)
            throws SQLException {
        transaction.update("UPDATE tc_game_live_uploads SET state = ?, endedat = ?, endreason = ?"
                + " WHERE gamesessionid = ? AND id = ?", GameState.ENDED, transaction.now(),
                GameJokerRepository.clip(endReason, 64), sessionId, id);
    }

    /**
     * Whether a speedhunt is running in the session: an active run, or an active live speedhunt ping (whose first
     * sample may not have reached anyone yet). The same for every member, so it reveals no target.
     */
    public static boolean speedhuntRunning(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.exists("SELECT 1 FROM tc_game_speedhunts WHERE gamesessionid = ?"
                + " AND activememberid IS NOT NULL", sessionId)
                || transaction.exists("SELECT 1 FROM tc_game_live_uploads WHERE gamesessionid = ? AND purpose = ?"
                        + " AND state = ?", sessionId, GameState.SPEEDHUNT_PING, GameState.ACTIVE);
    }

    /** The session the active controller lease executes, if any. */
    public static Optional<Long> executingSession(GameTransaction transaction) throws SQLException {
        return transaction.first("SELECT gamesessionid FROM tc_game_controller WHERE id = 1 AND state = 'active'"
                + " AND expiresat > ? AND gamesessionid IS NOT NULL", rows -> rows.getLong(1), transaction.now());
    }

    /**
     * The current controller epoch while the lease is active and executes this session, otherwise null. The lease
     * row keeps model 1's UTC {@code TIMESTAMP(3)} columns, which read and write like {@link GameTime} values.
     */
    public static Long executingEpoch(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.first("SELECT epoch FROM tc_game_controller WHERE id = 1 AND state = 'active'"
                + " AND expiresat > ? AND gamesessionid = ?", rows -> rows.getLong(1),
                transaction.now(), sessionId).orElse(null);
    }

}
