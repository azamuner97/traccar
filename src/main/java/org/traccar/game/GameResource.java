/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

/**
 * A Traccar resource that a game session owns: a mirror device, a group, or a managed geofence (plan §1).
 *
 * <p>A row is {@code active} while the game uses it and {@code retiring} after the game stopped using it and before
 * cleanup removed it. Both states keep the Traccar resource owned. The Traccar ID is null after the resource was
 * deleted in Traccar ({@code ON DELETE SET NULL}).
 */
public sealed interface GameResource permits GameResource.Mirror, GameResource.Group, GameResource.Geofence {

    String ACTIVE = "active";
    String RETIRING = "retiring";

    enum Kind { MIRROR, GROUP, GEOFENCE }

    long id();

    long gameSessionId();

    /** The Traccar device, group or geofence ID. */
    Long resourceId();

    String state();

    Kind kind();

    default boolean active() {
        return ACTIVE.equals(state());
    }

    /** A mirror device that shows a source member's position to a role or to one member. */
    record Mirror(long id, long gameSessionId, Long deviceId, long sourceMemberId, String destinationRole,
                  Long destinationMemberId, String state) implements GameResource {

        @Override
        public Long resourceId() {
            return deviceId;
        }

        @Override
        public Kind kind() {
            return Kind.MIRROR;
        }
    }

    /** A Traccar group with its purpose; per-member purposes name the member. */
    record Group(long id, long gameSessionId, Long groupId, String purpose, Long memberId, String state)
            implements GameResource {

        @Override
        public Long resourceId() {
            return groupId;
        }

        @Override
        public Kind kind() {
            return Kind.GROUP;
        }
    }

    /** A managed geofence and the geofence plan entry it belongs to. */
    record Geofence(long id, long gameSessionId, Long geofenceId, String planKey, String purpose, String state)
            implements GameResource {

        @Override
        public Long resourceId() {
            return geofenceId;
        }

        @Override
        public Kind kind() {
            return Kind.GEOFENCE;
        }
    }

}
