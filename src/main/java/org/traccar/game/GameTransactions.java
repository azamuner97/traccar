/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;

/**
 * Opens the transactions of game data model 2.
 *
 * <p>A write follows the order of the effects transaction (plan §3.1): validate, lock (see
 * {@link GameTransaction}), apply, advance revisions, commit, and only then run the after-commit actions that
 * publish the change. A transaction makes no network calls. A database constraint violation rolls back and answers
 * 409, so rules that the services check first still hold under concurrent writers.
 */
@Singleton
public class GameTransactions {

    /** Work inside one transaction. */
    @FunctionalInterface
    public interface Work<T> {
        T run(GameTransaction transaction) throws Exception;
    }

    private final DataSource dataSource;
    private final Clock clock;

    @Inject
    public GameTransactions(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    /** Transactions whose {@link GameTransaction#now()} comes from {@code clock}, so tests can move time. */
    public GameTransactions(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /** The current time of the clock that transactions use. */
    public Instant now() {
        return GameTime.truncate(clock.instant());
    }

    /** A transaction that may lock and change game data. */
    public <T> T write(Work<T> work) throws Exception {
        return run(work, false, false);
    }

    /** A transaction that only reads, so its reads see one consistent state. */
    public <T> T read(Work<T> work) throws Exception {
        return run(work, true, false);
    }

    /**
     * A read transaction whose reads all see one snapshot of the database, whatever commits meanwhile: the operator
     * snapshot (plan §3.3). MySQL takes the snapshot at the first read under {@code REPEATABLE READ}, which also
     * covers tables read later. H2 documents phantom reads for its {@code REPEATABLE READ}, so H2 reads under
     * {@code SERIALIZABLE}, which reads one snapshot without blocking writers. The level is set explicitly, so a
     * server configured for {@code READ COMMITTED} still gives one snapshot.
     */
    public <T> T snapshot(Work<T> work) throws Exception {
        return run(work, true, true);
    }

    /** The isolation level that gives a read transaction one snapshot on this database. */
    static int snapshotIsolation(Connection connection) throws SQLException {
        return "H2".equals(connection.getMetaData().getDatabaseProductName())
                ? Connection.TRANSACTION_SERIALIZABLE : Connection.TRANSACTION_REPEATABLE_READ;
    }

    private <T> T run(Work<T> work, boolean readOnly, boolean snapshot) throws Exception {
        T result;
        GameTransaction transaction;
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            int isolation = connection.getTransactionIsolation();
            if (snapshot) {
                connection.setTransactionIsolation(snapshotIsolation(connection));
            }
            connection.setAutoCommit(false);
            try {
                transaction = new GameTransaction(connection, clock.instant(), readOnly);
                result = work.run(transaction);
                connection.commit();
            } catch (SQLException error) {
                rollback(connection, error);
                if (GameErrors.constraintViolation(error)) {
                    throw GameErrors.conflict("Game data constraint violated: " + error.getMessage());
                }
                throw error;
            } catch (Exception | Error error) {
                rollback(connection, error);
                throw error;
            } finally {
                try {
                    connection.setAutoCommit(autoCommit);
                    if (snapshot) {
                        connection.setTransactionIsolation(isolation);
                    }
                } catch (SQLException ignored) {
                }
            }
        }
        for (Runnable action : transaction.afterCommitActions()) {
            action.run();
        }
        return result;
    }

    private static void rollback(Connection connection, Throwable error) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            error.addSuppressed(rollbackError);
        }
    }

}
