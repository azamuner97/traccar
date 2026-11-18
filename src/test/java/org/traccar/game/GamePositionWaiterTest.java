/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import org.junit.jupiter.api.Test;
import org.traccar.model.Position;

import java.time.Instant;
import java.util.Date;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review SE-2: a wait registered while another wait of the same tracker completes is never lost in a list that the
 * completion detached from the map. A stress test: it races registration against removal many times.
 */
public class GamePositionWaiterTest {

    private static Position position(long deviceId, Instant fixTime) {
        Position position = new Position("osmand");
        position.setDeviceId(deviceId);
        position.setValid(true);
        position.setLatitude(46);
        position.setLongitude(7);
        position.setFixTime(Date.from(fixTime));
        position.setDeviceTime(Date.from(fixTime));
        return position;
    }

    @Test
    void aWaitRegisteredWhileAnotherCompletesIsNeverLost() throws Exception {
        GamePositionWaiter waiter = new GamePositionWaiter();
        Instant minimum = Instant.parse("2026-01-01T00:00:00Z");
        for (int round = 0; round < 2000; round++) {
            long device = round;
            var first = waiter.await(device, minimum);
            var second = new java.util.concurrent.atomic.AtomicReference<
                    java.util.concurrent.CompletableFuture<GamePositions.Fix>>();
            CyclicBarrier barrier = new CyclicBarrier(2);
            Thread completer = new Thread(() -> {
                try {
                    barrier.await();
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
                first.cancel(false);
            });
            completer.start();
            barrier.await();
            second.set(waiter.await(device, minimum));
            completer.join();
            waiter.arrived(position(device, minimum.plusSeconds(1)));
            assertTrue(second.get().isDone(), "round " + round);
        }
        assertEquals(0, waiter.pending());
    }

}
