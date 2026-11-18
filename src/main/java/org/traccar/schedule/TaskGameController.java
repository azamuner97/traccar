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
import org.traccar.game.GameControllerService;
import org.traccar.game.GamePushOutbox;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Game maintenance of data model 2, every second.
 * <ul>
 * <li>Once at startup: ends a controller lease that survived the restart (its Tool re-acquires it automatically),
 * marks pushes whose attempt the restart interrupted as uncertain (never retried), and sends the pushes still
 * queued. The game state of every session is kept.</li>
 * <li>Records lease expiry from the stored deadline and announces it. Requests never expire, and losing the lease
 * changes neither the game state nor any request.</li>
 * <li>Retries returning the requests of the running session still executing under an older epoch than the lease's
 * (one indexed read while there are none).</li>
 * </ul>
 */
public class TaskGameController implements ScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskGameController.class);

    private final GameControllerService controller;
    private final GamePushOutbox outbox;
    private boolean started;

    @Inject
    public TaskGameController(GameControllerService controller, GamePushOutbox outbox) {
        this.controller = controller;
        this.outbox = outbox;
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        executor.scheduleWithFixedDelay(this, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public void run() {
        try {
            if (!started) {
                if (controller.endAfterRestart()) {
                    LOGGER.info("Game controller lease ended by the server restart; its Tool re-acquires it");
                }
                int uncertain = outbox.markInterrupted();
                if (uncertain > 0) {
                    LOGGER.warn("{} game push(es) interrupted by the restart are marked uncertain", uncertain);
                }
                outbox.dispatchQueued();
                started = true;
            }
            controller.recordExpiry();
        } catch (Exception error) {
            LOGGER.warn("Game controller maintenance failed", error);
        }
        try {
            if (controller.returnStaleExecuting()) {
                LOGGER.info("Game requests left executing under an older controller epoch were returned");
            }
        } catch (Exception error) {
            LOGGER.warn("Executing game requests could not be returned", error);
        }
    }
}
