/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * A self-service game request (tc_game_requests). {@code baseRevision} is the target's member state revision that a
 * hunter's request was based on. Labels are snapshots taken when the request was made.
 */
public record GameRequest(long id, long gameSessionId, String type, Long requesterMemberId, String requesterLabel,
                          String requesterRole, Long targetMemberId, String targetLabel, Long baseRevision,
                          JsonNode parameters, String status, Boolean approvalRequired, String message,
                          String detail, Long epoch, Instant createdAt, Instant expiresAt, Instant decidedAt,
                          Instant completedAt, Instant updatedAt) {

    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String EXECUTING = "executing";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";
    public static final String REJECTED = "rejected";
    public static final String EXPIRED = "expired";
    public static final String CANCELLED = "cancelled";

    public static final Set<String> OPEN = Set.of(PENDING, APPROVED, EXECUTING);

    public static final String STATIC_PING = "speedhuntStaticPing";
    public static final String LIVE_PING = "speedhuntLivePing";
    public static final String STOP_LIVE_PING = "speedhuntStopLivePing";
    public static final String TERMINATE = "speedhuntTerminate";
    public static final String HUNTER_POSITIONS = "jokerHunterPositions";
    public static final String BREAK = "jokerBreak";
    /** A fake ping on an own timetable ping ({@code entryId}) or a speedhunt ping ({@code pingNumber}). */
    public static final String FAKE_PING = "jokerFakePing";

    /** The hunters' shared speedhunt control. */
    public static final Set<String> HUNTER_TYPES = Set.of(STATIC_PING, LIVE_PING, STOP_LIVE_PING, TERMINATE);
    public static final Set<String> SPEEDHUNT_PINGS = Set.of(STATIC_PING, LIVE_PING);
    /** Joker requests that players and supporters make for themselves, with their joker type. */
    public static final Map<String, String> JOKER_TYPES = Map.of(
            HUNTER_POSITIONS, GameJokerRepository.HUNTER_POSITIONS,
            BREAK, GameJokerRepository.BREAK,
            FAKE_PING, GameJokerRepository.FAKE_PING);
    /** The session setting ({@code request_approvals}) of each request type. */
    public static final Map<String, String> APPROVAL_SETTINGS = Map.of(
            STATIC_PING, "speedhunt_static_ping",
            LIVE_PING, "speedhunt_live_ping",
            STOP_LIVE_PING, "speedhunt_stop_live_ping",
            TERMINATE, "speedhunt_terminate",
            HUNTER_POSITIONS, "joker_hunter_positions",
            BREAK, "joker_break",
            FAKE_PING, "joker_fake_ping");

    public boolean open() {
        return OPEN.contains(status);
    }

    /** A break request with a later start: a planned break. */
    public boolean plannedBreak() {
        return BREAK.equals(type) && parameters != null && parameters.hasNonNull("startsAt");
    }

}
