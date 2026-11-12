/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import java.time.Instant;

/**
 * A stable place in a game: session, role and number. Account and tracker are current bindings; a retired member
 * has neither. {@code stateRevision} is the member state revision of a player or supporter and null for hunters and
 * the spectator; it is operator and hunter data (plan §2.4).
 */
public record GameMember(long id, long gameSessionId, String role, int number, String displayName, Long userId,
                         Long trackerId, String state, Instant createdAt, Instant retiredAt, Long stateRevision) {

    public static final String PLAYER = "player";
    public static final String SUPPORTER = "supporter";
    public static final String HUNTER = "hunter";
    public static final String SPECTATOR = "spectator";
    public static final String ACTIVE = "active";
    public static final String RETIRED = "retired";

    public boolean active() {
        return ACTIVE.equals(state);
    }

    /** Players and supporters have a member state row: the lock target and revision of their game state. */
    public static boolean hasState(String role) {
        return PLAYER.equals(role) || SUPPORTER.equals(role);
    }

    /** "player 3", for messages. */
    public String label() {
        return role + " " + number;
    }

}
