/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Invalidates what depended on a member's old binding when its account or tracker changes, or when it retires
 * (plan §2.2), in the binding's transaction: the member's open requests end (waiting ones expire, executing ones
 * fail) and return their jokers, and the member's armed plans (fakes that have not bound yet, planned and waiting
 * breaks) are released. Bound fakes stay used; active state (a catch, a running speedhunt, an active break) belongs
 * to the game and stays.
 */
@Singleton
public class GamePlanInvalidation implements GameBindingHook {

    public static final String MESSAGE = "The member's account or tracker changed";

    private final GamePublisher publisher;

    @Inject
    public GamePlanInvalidation(GamePublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void bindingChanged(GameTransaction transaction, GameMember before, GameMember after) throws Exception {
        if (Objects.equals(before.userId(), after.userId()) && Objects.equals(before.trackerId(), after.trackerId())
                && before.state().equals(after.state())) {
            return;
        }
        long sessionId = after.gameSessionId();
        boolean requests = false;
        boolean pool = false;
        Set<Long> owners = new HashSet<>();
        for (GameRequest request : GameRequestRepository.open(transaction, sessionId)) {
            if (request.requesterMemberId() == null || request.requesterMemberId() != after.id()) {
                continue;
            }
            String status = GameRequest.EXECUTING.equals(request.status()) ? GameRequest.FAILED : GameRequest.EXPIRED;
            var settled = GameRequestRepository.finish(transaction, request, status, MESSAGE, null, false);
            owners.addAll(settled.members());
            pool |= settled.pool();
            requests = true;
        }
        if (GameMember.hasState(after.role())
                && GameJokerService.releasePlans(transaction, sessionId, after.id(), MESSAGE)) {
            owners.add(after.id());
        }
        if (requests) {
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(sessionId, GamePublisher.REQUESTS, null)));
        }
        if (pool) {
            owners.addAll(GameStateService.hunters(transaction, sessionId));
        }
        if (!owners.isEmpty()) {
            Set<Long> recipients = Set.copyOf(owners);
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(sessionId, GamePublisher.JOKERS, recipients)));
        }
    }

}
