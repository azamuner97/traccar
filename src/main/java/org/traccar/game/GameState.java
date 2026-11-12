/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import java.time.Instant;
import java.util.Set;

/**
 * The typed game state of data model 2 (plan §1): catch intervals, speedhunt runs and their delivered pings,
 * breaks including planned ones, and live uploads. A member is caught exactly while a catch has no
 * {@code releasedAt}; counts are queries over runs and pings.
 */
public final class GameState {

    public static final String STATIC = "static";
    public static final String LIVE = "live";
    public static final String REAL = "real";
    public static final String OPERATOR_FAKE = "operatorFake";
    /** A joker fake; operator-only, never shown to members. */
    public static final String JOKER_FAKE = "jokerFake";
    public static final String COMPLETED = "completed";
    public static final String TERMINATED = "terminated";

    public static final String PLANNED = "planned";
    public static final String WAITING = "waiting";
    public static final String ACTIVE = "active";
    public static final String ENDED = "ended";
    public static final String CANCELLED = "cancelled";
    public static final String MISSED = "missed";
    public static final String BY_PARTICIPANT = "participant";
    public static final String BY_OPERATOR = "operator";

    public static final String SPEEDHUNT_PING = "speedhuntPing";
    public static final String MANUAL = "manual";
    public static final String GEOFENCE_AUTO = "geofenceAuto";
    public static final String HUNTER_RELAY = "hunterRelay";
    public static final Set<String> PURPOSES = Set.of(SPEEDHUNT_PING, MANUAL, GEOFENCE_AUTO, HUNTER_RELAY);

    private GameState() {
    }

    public record Catch(long id, long memberId, Instant caughtAt, Instant releasedAt, String actor) {
        public boolean active() {
            return releasedAt == null;
        }
    }

    /** One speedhunt; it exists from its first delivered ping on. */
    public record Run(long id, long targetMemberId, boolean toHunters, boolean toPlayers, boolean toSupporters,
                      Instant startedAt, Instant endedAt, String outcome, String reason, String startedBy,
                      Long requestId, Long reservationId) {
        public boolean active() {
            return endedAt == null;
        }
    }

    /** A delivered logical ping; a live ping is one ping however many samples it sends. */
    public record Ping(long id, long runId, int number, String mode, String source, Instant sentAt, Long requestId,
                       Long fakePlanId) {
    }

    /** A break: {@code planned} and {@code waiting} breaks are private to their owner and the operators. */
    public record Break(long id, long memberId, String state, Instant plannedStart, Instant startedAt,
                        Instant deadline, Instant endedAt, String plannedBy, Long requestId, Long reservationId) {
        public boolean pending() {
            return PLANNED.equals(state) || WAITING.equals(state);
        }

        public boolean active() {
            return ACTIVE.equals(state);
        }

        /** Protects the member at {@code now}: active and before its deadline, which ends it by itself. */
        public boolean protects(Instant now) {
            return active() && deadline.isAfter(now);
        }
    }

    /** Repeated uploads: a live speedhunt ping, manual or geofence live tracking, or a live hunter relay. */
    public record LiveUpload(long id, String purpose, Long sourceMemberId, Long recipientMemberId, Long pingId,
                             boolean toHunters, boolean toPlayers, boolean toSupporters, int intervalSeconds,
                             Instant startedAt, Instant deadline, Instant lastSampleAt, Instant endedAt, String state,
                             String endReason, String reason, long controllerEpoch, Long requestId,
                             Long reservationId) {
        public boolean active() {
            return ACTIVE.equals(state);
        }

        /** The member whose state the upload belongs to: the source, or the recipient of a hunter relay. */
        public long memberId() {
            return sourceMemberId != null ? sourceMemberId : recipientMemberId;
        }

        /**
         * Whether hunters can observe it: a speedhunt ping always (it is speedhunt state), manual and geofence
         * tracking when they reach hunters. A hunter relay never reaches hunters.
         */
        public boolean visibleToHunters() {
            return SPEEDHUNT_PING.equals(purpose) || toHunters && !HUNTER_RELAY.equals(purpose);
        }
    }

}
