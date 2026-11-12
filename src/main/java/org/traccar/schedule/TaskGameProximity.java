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
import org.traccar.game.GameProximityService;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Deletes expired proximity grants and circles every second ({@link GameProximityService#purgeExpired}). Readers and
 * the permission union never see expired rows; the purge refreshes the device access of the expired grants'
 * recipients, so their WebSocket stops carrying the target's positions.
 */
public class TaskGameProximity implements ScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskGameProximity.class);

    private final GameProximityService service;

    @Inject
    public TaskGameProximity(GameProximityService service) {
        this.service = service;
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        executor.scheduleWithFixedDelay(this, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public void run() {
        try {
            service.purgeExpired();
        } catch (Exception error) {
            LOGGER.warn("Proximity expiry purge failed", error);
        }
    }
}
