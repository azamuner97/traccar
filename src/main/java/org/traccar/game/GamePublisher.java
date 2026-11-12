/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import org.traccar.model.Position;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * What a committed game change publishes: the "then publish" step of the effects transaction (plan §3.1). The
 * services call it only from after-commit actions ({@link GameTransaction#afterCommit}), so nothing uncommitted is
 * ever published. {@link GameServerPublisher} implements it with Traccar's caches, connection manager and push
 * outbox.
 *
 * <p>Invalidations name the members who may learn about a change. A private change (a joker grant, a fake binding,
 * another member's plan) reaches only its owners and the operators, so it never changes anything another member
 * can observe (plan §2.4).
 */
public interface GamePublisher {

    String STATUS = "game-status";
    String SPEEDHUNT_HISTORY = "game-speedhunt-history";
    String REQUESTS = "game-requests";
    String JOKERS = "game-jokers";
    String TIMETABLE = "game-timetable";
    String NOTIFICATIONS = "game-notifications";
    String PROXIMITY = "game-proximity";

    /**
     * A resource of a session changed. {@code members} are the member IDs to tell, or null for every member of the
     * session; operators are always told.
     */
    record Invalidation(long gameSessionId, String topic, Set<Long> members) {
    }

    /** Publishes nothing; for tests and tools that do not run inside the server. */
    GamePublisher NONE = new GamePublisher() {
        @Override
        public void positions(Collection<Long> devices, List<Position> latest) {
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
    };

    /**
     * Mirror positions were stored: {@code devices} received a position and are online now; {@code latest} became
     * their latest position and goes to caches and WebSocket subscribers, the way {@code PostProcessHandler} does.
     */
    void positions(Collection<Long> devices, List<Position> latest);

    void invalidate(Invalidation invalidation);

    /** Push outbox rows queued by a delivery, to send after commit. */
    void pushes(List<Long> outboxIds);

    /** Temporary proximity access of these accounts changed: their device permissions are recomputed. */
    void proximityAccess(long gameSessionId, Set<Long> userIds);

    /**
     * A resource changed for these accounts, whether or not they are members now; operators are told too. A binding
     * change names the account it removed, which no member resolves to after the commit (plan §5.10).
     */
    void accounts(Set<Long> userIds, String topic);

    /** A resource only operators read changed, such as the controller lease. */
    void operators(String topic);

}
