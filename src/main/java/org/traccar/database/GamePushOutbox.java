/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.database;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.User;
import org.traccar.notification.NotificationMessage;
import org.traccar.notification.NotificatorManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends pushes queued by effects deliveries after their commit, one attempt per recipient. A row is marked
 * {@code attempting} before the attempt and {@code sent} or {@code failed} after it. After a restart, rows
 * that were never attempted are sent; rows whose attempt outcome is unknown become {@code uncertain} and are
 * not retried, because a repeated push could reach the recipient twice.
 */
@Singleton
public class GamePushOutbox {

    public static final String QUEUED = "queued";
    public static final String ATTEMPTING = "attempting";
    public static final String SENT = "sent";
    public static final String FAILED = "failed";
    public static final String UNCERTAIN = "uncertain";

    private static final Logger LOGGER = LoggerFactory.getLogger(GamePushOutbox.class);

    /** Delivers one push; throws when the notificator reports a failure. */
    @FunctionalInterface
    public interface Sender {
        void send(long userId, String notificator, String subject, String message) throws Exception;
    }

    private record Row(long id, Long userId, String notificator, String subject, String message) { }

    private final DataSource dataSource;
    private final Sender sender;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "game-push-outbox");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public GamePushOutbox(DataSource dataSource, Storage storage, NotificatorManager notificators) {
        this(dataSource, (userId, notificator, subject, message) -> {
            User user = storage.getObject(User.class, new Request(
                    new Columns.All(), new Condition.Equals("id", userId)));
            if (user == null || user.getTemporary() || user.getDisabled()) {
                throw new IllegalStateException("The recipient account no longer exists or is disabled");
            }
            notificators.getNotificator(notificator).sendAsync(
                    user, new NotificationMessage(subject, message, message, true), null, null).get();
        });
    }

    public GamePushOutbox(DataSource dataSource, Sender sender) {
        this.dataSource = dataSource;
        this.sender = sender;
    }

    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    /** Sends the given queued pushes in the background, in order. */
    public void dispatch(List<Long> ids) {
        if (!ids.isEmpty()) {
            executor.submit(() -> ids.forEach(this::attempt));
        }
    }

    /** Sends every queued push in the background. */
    public void dispatchQueued() {
        executor.submit(this::sendQueued);
    }

    /** Sends every queued push on the calling thread; returns how many were attempted. */
    public int sendQueued() {
        List<Long> ids = new ArrayList<>();
        try (var connection = dataSource.getConnection();
             var query = connection.prepareStatement(
                     "SELECT id FROM tc_game_push_outbox WHERE status = ? ORDER BY id")) {
            query.setString(1, QUEUED);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getLong(1));
                }
            }
        } catch (Exception error) {
            LOGGER.warn("Game push outbox scan failed", error);
            return 0;
        }
        int attempted = 0;
        for (long id : ids) {
            if (attempt(id)) {
                attempted += 1;
            }
        }
        return attempted;
    }

    /** Marks pushes interrupted by a restart as uncertain; they are never retried. */
    public int markInterrupted() throws Exception {
        try (var connection = dataSource.getConnection();
             var query = connection.prepareStatement("UPDATE tc_game_push_outbox SET status = ?, detail = ?,"
                     + " completedat = ? WHERE status = ?")) {
            query.setString(1, UNCERTAIN);
            query.setString(2, "The server stopped during the attempt; the push may or may not have arrived");
            query.setTimestamp(3, now(), utc());
            query.setString(4, ATTEMPTING);
            return query.executeUpdate();
        }
    }

    private Row claim(long id) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Row row = null;
                try (var query = connection.prepareStatement("SELECT id,userid,notificator,subject,message"
                        + " FROM tc_game_push_outbox WHERE id = ? AND status = ? FOR UPDATE")) {
                    query.setLong(1, id);
                    query.setString(2, QUEUED);
                    try (var rows = query.executeQuery()) {
                        if (rows.next()) {
                            long user = rows.getLong("userid");
                            row = new Row(id, rows.wasNull() ? null : user, rows.getString("notificator"),
                                    rows.getString("subject"), rows.getString("message"));
                        }
                    }
                }
                if (row != null) {
                    try (var query = connection.prepareStatement(
                            "UPDATE tc_game_push_outbox SET status = ?, attemptedat = ? WHERE id = ?")) {
                        query.setString(1, ATTEMPTING);
                        query.setTimestamp(2, now(), utc());
                        query.setLong(3, id);
                        query.executeUpdate();
                    }
                }
                connection.commit();
                return row;
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private void finish(long id, String status, String detail) throws Exception {
        try (var connection = dataSource.getConnection();
             var query = connection.prepareStatement("UPDATE tc_game_push_outbox SET status = ?, detail = ?,"
                     + " completedat = ? WHERE id = ? AND status = ?")) {
            query.setString(1, status);
            query.setString(2, detail == null ? null : detail.length() > 512 ? detail.substring(0, 512) : detail);
            query.setTimestamp(3, now(), utc());
            query.setLong(4, id);
            query.setString(5, ATTEMPTING);
            query.executeUpdate();
        }
    }

    private boolean attempt(long id) {
        Row row;
        try {
            row = claim(id);
        } catch (Exception error) {
            LOGGER.warn("Game push {} could not be claimed", id, error);
            return false;
        }
        if (row == null) {
            return false;
        }
        String status = SENT;
        String detail = null;
        try {
            if (row.userId() == null) {
                throw new IllegalStateException("The recipient account was deleted");
            }
            sender.send(row.userId(), row.notificator(), row.subject(), row.message());
        } catch (Exception error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            status = FAILED;
            detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        }
        try {
            finish(id, status, detail);
        } catch (Exception error) {
            LOGGER.warn("Game push {} outcome could not be recorded", id, error);
        }
        return true;
    }

    public void shutdown() {
        executor.shutdown();
    }
}
