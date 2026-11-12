/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends the pushes that effects deliveries queue (tc_game_push_outbox), after their commit, one attempt per
 * recipient (model 1). A row is marked {@code attempting} before the attempt and {@code sent} or {@code failed}
 * after it. After a restart, rows never attempted are sent; rows whose attempt outcome is unknown become
 * {@code uncertain} and are never retried, because a repeated push could reach the recipient twice. A repeated
 * delivery ID never queues its pushes again ({@link GameEffectsService}).
 */
@Singleton
public class GamePushOutbox {

    public static final String QUEUED = "queued";
    public static final String ATTEMPTING = "attempting";
    public static final String SENT = "sent";
    public static final String FAILED = "failed";
    public static final String UNCERTAIN = "uncertain";
    /** Outcomes of pushes that were never queued; they have no outbox row. */
    public static final String SKIPPED = "skipped";
    public static final String REJECTED = "rejected";

    private static final Logger LOGGER = LoggerFactory.getLogger(GamePushOutbox.class);

    /** Delivers one push; throws when the notificator reports a failure. */
    @FunctionalInterface
    public interface Sender {
        void send(long userId, String notificator, String subject, String message) throws Exception;
    }

    private record Row(long id, Long userId, String notificator, String subject, String message) {
    }

    private final GameTransactions transactions;
    private final Sender sender;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "game-push-outbox-2");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public GamePushOutbox(GameTransactions transactions, Sender sender) {
        this.transactions = transactions;
        this.sender = sender;
    }

    /** The sender of the server: the recipient account's notificator, refusing deleted or disabled accounts. */
    public static Sender traccarSender(Storage storage, NotificatorManager notificators) {
        return (userId, notificator, subject, message) -> {
            User user = storage.getObject(User.class, new Request(
                    new Columns.All(), new Condition.Equals("id", userId)));
            if (user == null || user.getTemporary() || user.getDisabled()) {
                throw new IllegalStateException("The recipient account no longer exists or is disabled");
            }
            notificators.getNotificator(notificator).sendAsync(
                    user, new NotificationMessage(subject, message, message, true), null, null).get();
        };
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
        List<Long> ids;
        try {
            ids = transactions.read(transaction -> transaction.list(
                    "SELECT id FROM tc_game_push_outbox WHERE status = ? ORDER BY id", rows -> rows.getLong(1),
                    QUEUED));
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
        return transactions.write(transaction -> transaction.update("UPDATE tc_game_push_outbox SET status = ?,"
                + " detail = ?, completedat = ? WHERE status = ?", UNCERTAIN,
                "The server stopped during the attempt; the push may or may not have arrived", transaction.now(),
                ATTEMPTING));
    }

    /** Claims a queued row for one attempt; null when another attempt claimed it or it is no longer queued. */
    private Row claim(long id) throws Exception {
        return transactions.write(transaction -> {
            Row row = transaction.first("SELECT id, userid, notificator, subject, message FROM tc_game_push_outbox"
                    + " WHERE id = ? AND status = ? FOR UPDATE", rows -> new Row(rows.getLong("id"),
                            GameTransaction.longOrNull(rows, "userid"), rows.getString("notificator"),
                            rows.getString("subject"), rows.getString("message")), id, QUEUED).orElse(null);
            if (row != null) {
                transaction.update("UPDATE tc_game_push_outbox SET status = ?, attemptedat = ? WHERE id = ?",
                        ATTEMPTING, transaction.now(), id);
            }
            return row;
        });
    }

    private void finish(long id, String status, String detail) throws Exception {
        transactions.write(transaction -> transaction.update("UPDATE tc_game_push_outbox SET status = ?,"
                + " detail = ?, completedat = ? WHERE id = ? AND status = ?", status,
                GameJokerRepository.clip(detail, 512), transaction.now(), id, ATTEMPTING));
    }

    /** One attempt of one row; returns whether it was attempted. */
    boolean attempt(long id) {
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
