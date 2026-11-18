/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Execution events reach administrators once per commit and, per session, in ID order (review SE-1, SC-1, SC-8):
 * a transaction's events are one push, and pushes leave in the order their transactions committed under the session
 * lock, whichever thread finishes its commit first.
 */
public class GameEventPushTest {

    private static final class Batches implements GamePublisher {
        final List<List<GameEvent>> batches = new CopyOnWriteArrayList<>();
        boolean failNext;

        @Override
        public void positions(java.util.Collection<Long> received, List<org.traccar.model.Position> latest) {
        }

        @Override
        public void invalidate(Invalidation invalidation) {
        }

        @Override
        public void pushes(List<Long> outboxIds) {
        }

        @Override
        public void proximityAccess(long gameSessionId, Set<Long> userIds) {
        }

        @Override
        public void accounts(Set<Long> userIds, String topic) {
        }

        @Override
        public void operators(String topic) {
        }

        @Override
        public void gameEvents(List<GameEvent> events) {
            batches.add(events);
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("push failed");
            }
        }

        List<Long> ids() {
            List<Long> ids = new ArrayList<>();
            batches.forEach(batch -> batch.forEach(event -> ids.add(event.id())));
            return ids;
        }
    }

    private static GameEvent event(long id) {
        return new GameEvent(id, 1, "ping", "2026-01-01T00:00:00Z", "Ping " + id,
                JsonNodeFactory.instance.objectNode());
    }

    private final Batches publisher = new Batches();
    private GameTransactions transactions;
    private long session;

    @BeforeEach
    void setup() throws Exception {
        var source = GameSchemaFixture.model2();
        transactions = new GameTransactions(source);
        session = new GameSessionService(transactions, new ObjectMapper())
                .create("Pushes", 5, 5, JsonNodeFactory.instance.objectNode()).id();
    }

    private void events(int count) throws Exception {
        transactions.write(transaction -> {
            transaction.lockSession(session);
            for (int i = 0; i < count; i++) {
                GameLogService.event(transaction, publisher, session, "timetable", "Ping " + i,
                        JsonNodeFactory.instance.objectNode().put("index", i));
            }
            return null;
        });
    }

    @Test
    public void aTransactionPushesItsEventsOnceInIdOrder() throws Exception {
        events(3);
        assertEquals(1, publisher.batches.size());
        List<Long> ids = publisher.ids();
        assertEquals(3, ids.size());
        assertEquals(ids.stream().sorted().toList(), ids);

        // A rolled-back transaction pushes nothing and does not hold back the next one.
        assertThrows(IllegalStateException.class, () -> transactions.write(transaction -> {
            transaction.lockSession(session);
            GameLogService.event(transaction, publisher, session, "timetable", "Lost",
                    JsonNodeFactory.instance.objectNode());
            throw new IllegalStateException("rolled back");
        }));
        events(1);
        assertEquals(2, publisher.batches.size());
        assertEquals(4, publisher.ids().size());
    }

    @Test
    public void aLongEventMessageIsCutBetweenCharacters() {
        String emoji = "\uD83D\uDE00";
        String split = "a".repeat(GameLogService.MAX_MESSAGE - 1) + emoji;
        assertEquals("a".repeat(GameLogService.MAX_MESSAGE - 1), GameLogService.truncate(split));
        String whole = "a".repeat(GameLogService.MAX_MESSAGE - 2) + emoji + "b";
        assertEquals("a".repeat(GameLogService.MAX_MESSAGE - 2) + emoji, GameLogService.truncate(whole));
        assertEquals("short", GameLogService.truncate("short"));
    }

    @Test
    public void anEventNeedsTheLockOfItsSession() {
        assertThrows(IllegalStateException.class, () -> transactions.write(transaction ->
                GameLogService.event(transaction, publisher, session, "timetable", "Unlocked",
                        JsonNodeFactory.instance.objectNode())));
        assertTrue(publisher.batches.isEmpty());
    }

    @Test
    public void pushesLeaveInReservationOrderWhoeverFinishesFirst() {
        GameEventSequencer sequencer = new GameEventSequencer();
        var first = sequencer.reserve();
        var second = sequencer.reserve();
        var third = sequencer.reserve();
        sequencer.publish(second, publisher, List.of(event(2)));
        sequencer.publish(third, publisher, List.of(event(3), event(4)));
        assertTrue(publisher.batches.isEmpty());
        sequencer.publish(first, publisher, List.of(event(1)));
        assertEquals(List.of(1L, 2L, 3L, 4L), publisher.ids());

        // A cancelled reservation (rollback) releases the ones behind it; a failing push does not stop the queue.
        var cancelled = sequencer.reserve();
        var failing = sequencer.reserve();
        var last = sequencer.reserve();
        sequencer.publish(last, publisher, List.of(event(7)));
        sequencer.publish(failing, publisher, List.of(event(6)));
        assertEquals(3, publisher.batches.size());
        publisher.failNext = true;
        sequencer.cancel(cancelled);
        assertEquals(List.of(1L, 2L, 3L, 4L, 6L, 7L), publisher.ids());
    }

}
