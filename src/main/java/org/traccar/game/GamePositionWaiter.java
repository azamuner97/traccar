/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.traccar.model.Position;
import org.traccar.session.ConnectionManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lets the ping engine react to arriving tracker positions instead of polling (plan B3, freshness wait). It is
 * registered as an observer of {@link ConnectionManager#updatePosition}, which {@code PostProcessHandler} calls
 * for every processed tracker position. A wait completes with the {@link Position} itself, because with position
 * batching it may not be in the database yet. Observing costs one map lookup per position.
 */
@Singleton
public class GamePositionWaiter {

    private record Wait(Instant minimumFixTime, CompletableFuture<GamePositions.Fix> future) {
    }

    private final Map<Long, List<Wait>> waits = new ConcurrentHashMap<>();

    /** A waiter that nothing feeds; tests call {@link #arrived} themselves. */
    public GamePositionWaiter() {
    }

    @Inject
    public GamePositionWaiter(ConnectionManager connectionManager) {
        connectionManager.addPositionObserver(this::arrived);
    }

    /**
     * A future completed by the first position of {@code deviceId} whose fix time is at or after
     * {@code minimumFixTime}. Cancel it (or complete it) to stop waiting.
     */
    public CompletableFuture<GamePositions.Fix> await(long deviceId, Instant minimumFixTime) {
        CompletableFuture<GamePositions.Fix> future = new CompletableFuture<>();
        Wait wait = new Wait(minimumFixTime, future);
        // Registration and removal both run inside the map's atomic compute, so a wait is never added to a list that
        // a concurrent removal has just detached (SE-2).
        waits.compute(deviceId, (key, list) -> {
            List<Wait> result = list == null ? new CopyOnWriteArrayList<>() : list;
            result.add(wait);
            return result;
        });
        future.whenComplete((value, error) -> waits.computeIfPresent(deviceId, (key, list) -> {
            list.remove(wait);
            return list.isEmpty() ? null : list;
        }));
        return future;
    }

    /** A processed position: completes the waits of its device that it satisfies. */
    public void arrived(Position position) {
        List<Wait> pending = waits.get(position.getDeviceId());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        GamePositions.Fix fix = GamePositions.Fix.of(position);
        if (fix.fixTime() == null || !position.getValid() && position.getLatitude() == 0
                && position.getLongitude() == 0) {
            return;
        }
        for (Wait wait : pending) {
            if (!fix.fixTime().isBefore(wait.minimumFixTime())) {
                wait.future().complete(fix);
            }
        }
    }

    /** The number of open waits, for tests. */
    int pending() {
        return waits.values().stream().mapToInt(List::size).sum();
    }

}
