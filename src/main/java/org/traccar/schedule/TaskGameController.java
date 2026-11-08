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
import org.traccar.api.resource.GameControllerResource;
import org.traccar.api.resource.GameRequestResource;
import org.traccar.api.resource.GameStatusResource;
import org.traccar.database.GameControllerService;
import org.traccar.database.GamePushOutbox;
import org.traccar.database.GameRequestService;
import org.traccar.session.ConnectionManager;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Ends a controller lease that survived a server restart, recovers the push outbox, and records lease expiry
 * from the stored deadline.
 */
public class TaskGameController implements ScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskGameController.class);

    private final GameControllerService controller;
    private final GamePushOutbox outbox;
    private final ConnectionManager connectionManager;
    private final GameRequestService requests;
    private boolean started;

    @Inject
    public TaskGameController(GameControllerService controller, GamePushOutbox outbox,
                              ConnectionManager connectionManager, GameRequestService requests) {
        this.controller = controller;
        this.outbox = outbox;
        this.connectionManager = connectionManager;
        this.requests = requests;
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
                    LOGGER.info("Game controller lease ended by the server restart; Tool must resume explicitly");
                }
                int uncertain = outbox.markInterrupted();
                if (uncertain > 0) {
                    LOGGER.warn("{} game push(es) interrupted by the restart are marked uncertain", uncertain);
                }
                outbox.dispatchQueued();
                started = true;
            }
            if (controller.recordExpiry()) {
                connectionManager.updateGameAdmin(true, GameControllerResource.INVALIDATION);
                connectionManager.updateGameMembers(true, null, GameRequestResource.INVALIDATION);
                connectionManager.updateGameMembers(true, null, GameStatusResource.INVALIDATION);
            }
            for (long gameSessionId : requests.expire()) {
                connectionManager.updateGameMembers(true, gameSessionId, GameRequestResource.INVALIDATION);
            }
        } catch (Exception error) {
            LOGGER.warn("Game controller maintenance failed", error);
        }
    }
}
