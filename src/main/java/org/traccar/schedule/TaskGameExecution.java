/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.schedule;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.game.GameExecutionRunner;
import org.traccar.game.GameExecutionService;
import org.traccar.game.GameLiveUploadRunner;
import org.traccar.game.GameScheduler;
import org.traccar.game.GameTimetableRunner;
import org.traccar.game.GameTransactions;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The server's game execution (plan B2): every second, on the execution thread of {@link GameScheduler} rather
 * than the shared schedule pool, it asks each runner what is due for the running session and runs it. Only the
 * running session executes, whatever the controller lease does; a stopped game executes nothing. Waits are
 * scheduled continuations on the same thread, never sleeps. The runners: the timetable, then live uploads and
 * relays. Only one server instance uses the database, so this in-process scheduler plus the session lock is
 * enough; a secondary instance does not run it.
 */
public class TaskGameExecution implements ScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskGameExecution.class);
    public static final long TICK_MILLIS = 1000;

    private final GameTransactions transactions;
    private final GameScheduler scheduler;
    private final List<GameExecutionRunner<?>> runners;

    @Inject
    public TaskGameExecution(GameTransactions transactions, GameScheduler scheduler, GameTimetableRunner timetable,
                             GameLiveUploadRunner liveUploads) {
        this(transactions, scheduler, List.of(timetable, liveUploads));
    }

    public TaskGameExecution(GameTransactions transactions, GameScheduler scheduler,
                             List<GameExecutionRunner<?>> runners) {
        this.transactions = transactions;
        this.scheduler = scheduler;
        this.runners = List.copyOf(runners);
    }

    @Override
    public boolean multipleInstances() {
        return false;
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        scheduler.repeat(this, TICK_MILLIS, TICK_MILLIS);
    }

    @Override
    public void run() {
        Long session;
        try {
            session = transactions.read(GameExecutionService::runningSession).orElse(null);
        } catch (Exception error) {
            LOGGER.warn("Game execution gate failed", error);
            return;
        }
        if (session == null) {
            return;
        }
        for (GameExecutionRunner<?> runner : runners) {
            tick(runner, session);
        }
    }

    private <T> void tick(GameExecutionRunner<T> runner, long session) {
        List<T> due;
        try {
            Instant now = transactions.now();
            due = runner.due(session, now);
        } catch (Exception error) {
            LOGGER.warn("Game execution runner failed", error);
            return;
        }
        for (T item : due) {
            try {
                runner.run(session, item);
            } catch (Exception error) {
                LOGGER.warn("Game execution step {} failed", item, error);
            }
        }
    }

}
