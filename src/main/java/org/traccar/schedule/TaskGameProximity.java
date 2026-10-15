package org.traccar.schedule;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.database.GameProximityService;
import org.traccar.session.ConnectionManager;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TaskGameProximity implements ScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskGameProximity.class);
    private final GameProximityService service;
    private final ConnectionManager connectionManager;

    @Inject
    public TaskGameProximity(GameProximityService service, ConnectionManager connectionManager) {
        this.service = service;
        this.connectionManager = connectionManager;
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        executor.scheduleAtFixedRate(this, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public void run() {
        try {
            for (var change : service.reconcile()) {
                connectionManager.updateGameProximity(true, change.sessionDeviceId(), change.userIds());
            }
        } catch (Exception error) {
            LOGGER.warn("Proximity expiry reconciliation failed", error);
        }
    }
}
