/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The single thread of the server's game execution (plan B2): the execution task's tick, the ping engine's
 * transactions and every continuation of a wait run on it, one at a time. Waits are scheduled continuations, never
 * sleeps, so a slow tracker never blocks anything else. Tests replace it with a scheduler on virtual time.
 */
@Singleton
public class GameScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameScheduler.class);

    /** A scheduled continuation that can be cancelled. */
    public interface Handle {
        void cancel();
    }

    private ScheduledExecutorService executor;

    private synchronized ScheduledExecutorService executor() {
        if (executor == null) {
            executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "game-execution");
                thread.setDaemon(true);
                return thread;
            });
        }
        return executor;
    }

    private static Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException | Error error) {
                LOGGER.warn("Game execution task failed", error);
            }
        };
    }

    /** Runs a task on the execution thread as soon as possible. */
    public void execute(Runnable task) {
        executor().execute(guarded(task));
    }

    /** Runs a task on the execution thread after {@code delayMillis}. */
    public Handle schedule(Runnable task, long delayMillis) {
        ScheduledFuture<?> future = executor().schedule(guarded(task), Math.max(0, delayMillis),
                TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    /** Runs a task every {@code periodMillis}, the first time after {@code delayMillis}. */
    public Handle repeat(Runnable task, long delayMillis, long periodMillis) {
        ScheduledFuture<?> future = executor().scheduleWithFixedDelay(guarded(task), delayMillis, periodMillis,
                TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    /** Stops the execution thread; pending continuations are dropped. */
    public synchronized void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

}
