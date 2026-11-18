/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pushes committed execution events in the order their transactions committed.
 *
 * <p>A transaction that wrote events reserves a place just before its commit, while it still holds its session lock,
 * and fills the place after the commit (or cancels it on rollback). Pushes leave in reservation order, so the pushes
 * of one session follow its event IDs, whichever thread finishes its commit first. Whoever fills the first open place
 * pushes every ready batch; the others return at once, so no thread waits on another's push and no lock is held while
 * pushing.
 */
final class GameEventSequencer {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameEventSequencer.class);

    /** A place in the push order. */
    static final class Ticket {
        private GamePublisher publisher;
        private List<GameEvent> events;
        private boolean ready;
    }

    private final Deque<Ticket> queue = new ArrayDeque<>();
    private final AtomicBoolean draining = new AtomicBoolean();

    /** Takes the next place; call it while the session lock is held, before the commit. */
    Ticket reserve() {
        Ticket ticket = new Ticket();
        synchronized (queue) {
            queue.addLast(ticket);
        }
        return ticket;
    }

    /** The transaction committed: push its events once every earlier place is done. Never throws. */
    void publish(Ticket ticket, GamePublisher publisher, List<GameEvent> events) {
        synchronized (queue) {
            ticket.publisher = publisher;
            ticket.events = List.copyOf(events);
            ticket.ready = true;
        }
        drain();
    }

    /** The transaction rolled back: its place pushes nothing. */
    void cancel(Ticket ticket) {
        publish(ticket, null, List.of());
    }

    private Ticket next() {
        synchronized (queue) {
            Ticket head = queue.peekFirst();
            if (head == null || !head.ready) {
                return null;
            }
            return queue.pollFirst();
        }
    }

    private boolean headReady() {
        synchronized (queue) {
            Ticket head = queue.peekFirst();
            return head != null && head.ready;
        }
    }

    private void drain() {
        do {
            if (!draining.compareAndSet(false, true)) {
                return;
            }
            try {
                for (Ticket ticket = next(); ticket != null; ticket = next()) {
                    if (!ticket.events.isEmpty()) {
                        try {
                            ticket.publisher.gameEvents(ticket.events);
                        } catch (RuntimeException error) {
                            LOGGER.warn("Game event push failed", error);
                        }
                    }
                }
            } finally {
                draining.set(false);
            }
        } while (headReady());
    }

}
