/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The append-only session log (tc_game_session_logs), read by operators. It also holds what model 1 kept as
 * {@code speedhunt_events} in the Tool runtime: those entries use the category {@link #SPEEDHUNT}.
 *
 * <p>Rows with {@code data} are execution events ({@link GameEvent}): the server writes them for its own execution
 * steps and pushes them to administrators after commit. Lines the Tool appends never carry data.
 */
@Singleton
public class GameLogService {

    public static final String SPEEDHUNT = "speedhunt";
    public static final int MAX_BATCH = 500;
    public static final int MAX_MESSAGE = 16384;

    /** A log entry as submitted; {@code occurredAt} is an ISO-8601 instant. */
    public record LogInput(String category, String occurredAt, String message) {
    }

    /** A log row; {@code data} is set on execution events only. */
    public record LogEntry(long id, String category, Instant occurredAt, String message, JsonNode data) {
    }

    public record LogPage(List<LogEntry> items, String nextCursor, boolean hasMore) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final GameTransactions transactions;

    @Inject
    public GameLogService(GameTransactions transactions) {
        this.transactions = transactions;
    }

    private static String category(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,32}")) {
            throw GameErrors.invalid("Invalid log category");
        }
        return value;
    }

    public void append(long sessionId, List<LogInput> entries) throws Exception {
        transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            append(transaction, sessionId, entries);
            return null;
        });
    }

    /** Appends entries inside a transaction that holds the session lock (also from effects deliveries). */
    static void append(GameTransaction transaction, long sessionId, List<LogInput> entries) throws SQLException {
        if (entries == null || entries.isEmpty() || entries.size() > MAX_BATCH) {
            throw GameErrors.invalid("Invalid log batch");
        }
        List<Instant> times = new ArrayList<>();
        for (LogInput entry : entries) {
            if (entry == null || entry.message() == null || entry.message().length() > MAX_MESSAGE) {
                throw GameErrors.invalid("Invalid log entry");
            }
            category(entry.category());
            times.add(GameTime.parse(entry.occurredAt(), "log time"));
        }
        for (int i = 0; i < entries.size(); i++) {
            transaction.update("INSERT INTO tc_game_session_logs (gamesessionid, category, occurredat, message)"
                    + " VALUES (?, ?, ?, ?)", sessionId, entries.get(i).category(), times.get(i),
                    entries.get(i).message());
        }
    }

    /**
     * Writes an execution event inside a transaction that holds the session lock. The transaction pushes its events
     * to administrators once after commit, in the session's event ID order. {@code data} is a JSON object of at most
     * {@value GameEvent#MAX_DATA} bytes.
     */
    public static GameEvent event(GameTransaction transaction, GamePublisher publisher, long sessionId,
                                  String category, String message, JsonNode data) throws SQLException {
        if (!GameEvent.CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("Unknown game event category " + category);
        }
        if (data == null || !data.isObject()) {
            throw new IllegalArgumentException("Game event data must be a JSON object");
        }
        String json;
        try {
            json = JSON.writeValueAsString(data);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException(error);
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > GameEvent.MAX_DATA) {
            throw new IllegalArgumentException("Game event data exceeds " + GameEvent.MAX_DATA + " bytes");
        }
        if (transaction.session() != sessionId) {
            throw new IllegalStateException("A game event belongs to the locked session");
        }
        String text = truncate(message);
        Instant now = transaction.now();
        long id = transaction.insert("INSERT INTO tc_game_session_logs (gamesessionid, category, occurredat, message,"
                + " data) VALUES (?, ?, ?, ?, ?)", sessionId, category, now, text, json);
        GameEvent event = new GameEvent(id, sessionId, category, now.toString(), text, data.deepCopy());
        transaction.event(publisher, event);
        return event;
    }

    /**
     * Cuts a message to {@value #MAX_MESSAGE} UTF-16 code units, the unit of the limit, without splitting a surrogate
     * pair (review WC-9).
     */
    static String truncate(String message) {
        if (message.length() <= MAX_MESSAGE) {
            return message;
        }
        int end = Character.isHighSurrogate(message.charAt(MAX_MESSAGE - 1))
                && Character.isLowSurrogate(message.charAt(MAX_MESSAGE)) ? MAX_MESSAGE - 1 : MAX_MESSAGE;
        return message.substring(0, end);
    }

    private static JsonNode data(String value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            return null;
        }
    }

    /**
     * One page of the log in insertion order, optionally of one category, after {@code cursor}. With {@code events}
     * only execution events (rows with data) are listed, so the Tool catches up by the last event ID it showed.
     */
    public LogPage list(long sessionId, String cursor, int requestedLimit, String category, boolean events)
            throws Exception {
        int limit = requestedLimit == 0 ? 500 : requestedLimit;
        if (limit < 1 || limit > 1000 || cursor != null && !cursor.matches("[0-9]{1,18}")) {
            throw GameErrors.invalid("Invalid log pagination");
        }
        String filter = category == null ? null : category(category);
        long after = cursor == null ? 0 : Long.parseLong(cursor);
        return transactions.read(transaction -> {
            GameDocumentService.sessionExists(transaction, sessionId);
            List<LogEntry> items = transaction.list("SELECT id, category, occurredat, message, data"
                    + " FROM tc_game_session_logs WHERE gamesessionid = ? AND id > ?"
                    + (events ? " AND data IS NOT NULL" : "")
                    + (filter == null ? "" : " AND category = ?") + " ORDER BY id", limit + 1,
                    rows -> new LogEntry(rows.getLong("id"), rows.getString("category"),
                            GameTime.get(rows, "occurredat"), rows.getString("message"), data(rows.getString("data"))),
                    filter == null ? new Object[] {sessionId, after} : new Object[] {sessionId, after, filter});
            boolean more = items.size() > limit;
            List<LogEntry> page = more ? items.subList(0, limit) : items;
            String next = page.isEmpty() ? Long.toString(after) : Long.toString(page.getLast().id());
            return new LogPage(List.copyOf(page), next, more);
        });
    }

}
