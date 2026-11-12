/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The Traccar resources a session owns (plan §1): mirrors, groups and managed geofences. Ownership comes from these
 * rows and from member bindings ({@link GameOwnershipService}).
 *
 * <p>Lifecycle: a row is added {@code active}, {@linkplain #retire retired} when the game stops using it, and
 * {@linkplain #remove removed} once cleanup has dealt with the Traccar resource. A retiring row keeps the resource
 * owned and keeps its place, so the same route, purpose or plan key can only {@linkplain #reactivate come back}
 * until the row is removed. {@link #rebind} replaces a Traccar resource that was deleted or recreated.
 */
@Singleton
public class GameResourceService {

    /** Real role groups and role-to-role mirror groups: one per session. Keys follow the Tool's GroupsData. */
    public static final Set<String> SESSION_GROUP_PURPOSES = Set.of(
            "player", "supporter", "hunter",
            "playerToHunter", "supporterToHunter", "playerToPlayer", "supporterToPlayer", "playerToSupporter",
            "supporterToSupporter");

    /**
     * Hunter mirror groups for one destination member: the hunters' mirrors that one player or supporter sees.
     * The value is the role of that member.
     */
    public static final Map<String, String> MEMBER_GROUP_PURPOSES = Map.of(
            "hunterToPlayer", GameMember.PLAYER,
            "hunterToSupporter", GameMember.SUPPORTER);

    /** Geofences from the session settings, and geofences that a geofence plan entry uploads. */
    public static final Set<String> GEOFENCE_PURPOSES = Set.of("configured", "planned");

    /** Roles that mirrors of players and supporters show to. */
    public static final Set<String> MIRROR_DESTINATION_ROLES = Set.of(
            GameMember.PLAYER, GameMember.SUPPORTER, GameMember.HUNTER);

    private final GameTransactions transactions;

    @Inject
    public GameResourceService(GameTransactions transactions) {
        this.transactions = transactions;
    }

    private static GameMember referenced(GameTransaction transaction, long sessionId, long memberId, String label)
            throws Exception {
        GameMember member = GameMemberRepository.find(transaction, sessionId, memberId)
                .orElseThrow(() -> GameErrors.invalid(label + " not found in this game session"));
        if (!member.active()) {
            throw GameErrors.conflict("The " + label.toLowerCase() + " (" + member.label() + ") is retired");
        }
        return member;
    }

    /** A Traccar resource that exists and that no session row owns yet. */
    private static void unowned(GameTransaction transaction, GameResource.Kind kind, long resourceId)
            throws Exception {
        String label = kind.name().toLowerCase();
        if (!GameResourceRepository.traccarExists(transaction, kind, resourceId)) {
            throw GameErrors.invalid("Traccar " + label + " not found");
        }
        Optional<Long> owner = GameResourceRepository.owner(transaction, kind, resourceId);
        if (owner.isPresent()) {
            throw GameErrors.conflict("The Traccar " + label + " already belongs to game session " + owner.get());
        }
    }

    private static void mirrorDevice(GameTransaction transaction, long deviceId) throws Exception {
        unowned(transaction, GameResource.Kind.MIRROR, deviceId);
        if (GameOwnershipService.trackerSession(transaction, deviceId).isPresent()) {
            throw GameErrors.conflict("The device is a member's tracker and cannot be a mirror");
        }
    }

    private static GameResource find(GameTransaction transaction, GameResource.Kind kind, long sessionId, long id)
            throws Exception {
        return GameResourceRepository.find(transaction, kind, sessionId, id)
                .orElseThrow(() -> GameErrors.notFound("Game resource not found"));
    }

    // Reads -------------------------------------------------------------------------------------------------

    public List<GameResource.Mirror> mirrors(long sessionId) throws Exception {
        return transactions.read(transaction -> GameResourceRepository.mirrors(transaction, sessionId));
    }

    public List<GameResource.Group> groups(long sessionId) throws Exception {
        return transactions.read(transaction -> GameResourceRepository.groups(transaction, sessionId));
    }

    public List<GameResource.Geofence> geofences(long sessionId) throws Exception {
        return transactions.read(transaction -> GameResourceRepository.geofences(transaction, sessionId));
    }

    // Adding ------------------------------------------------------------------------------------------------

    /**
     * Adds a mirror following the Tool's mirroring topology: a player or supporter is mirrored to a role, and a
     * hunter to one player or supporter.
     */
    public GameResource.Mirror addMirror(long sessionId, long deviceId, long sourceMemberId, String destinationRole,
                                         Long destinationMemberId) throws Exception {
        if ((destinationRole == null) == (destinationMemberId == null)) {
            throw GameErrors.invalid("A mirror has either a destination role or a destination member");
        }
        if (destinationRole != null && !MIRROR_DESTINATION_ROLES.contains(destinationRole)) {
            throw GameErrors.invalid("Invalid mirror destination role");
        }
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            mirrorRoute(transaction, sessionId, sourceMemberId, destinationRole, destinationMemberId);
            if (GameResourceRepository.mirrorByRoute(transaction, sessionId, sourceMemberId, destinationRole,
                    destinationMemberId).isPresent()) {
                throw GameErrors.conflict("This mirror route already has a mirror in this game session");
            }
            mirrorDevice(transaction, deviceId);
            long id = GameResourceRepository.insertMirror(transaction, sessionId, deviceId, sourceMemberId,
                    destinationRole, destinationMemberId);
            transaction.bumpSession();
            return (GameResource.Mirror) find(transaction, GameResource.Kind.MIRROR, sessionId, id);
        });
    }

    private static void mirrorRoute(GameTransaction transaction, long sessionId, long sourceMemberId,
                                    String destinationRole, Long destinationMemberId) throws Exception {
        GameMember source = referenced(transaction, sessionId, sourceMemberId, "Source member");
        if (GameMember.HUNTER.equals(source.role())) {
            if (destinationMemberId == null) {
                throw GameErrors.invalid("A hunter is mirrored to one player or supporter");
            }
            GameMember destination = referenced(transaction, sessionId, destinationMemberId, "Destination member");
            if (!GameMember.hasState(destination.role())) {
                throw GameErrors.invalid("A hunter is mirrored to a player or a supporter");
            }
        } else if (GameMember.hasState(source.role())) {
            if (destinationRole == null) {
                throw GameErrors.invalid("A player or supporter is mirrored to a role");
            }
        } else {
            throw GameErrors.invalid("The spectator has no mirrors");
        }
    }

    /** Adds a group: session-level purposes have no member, per-member purposes name an active member. */
    public GameResource.Group addGroup(long sessionId, long groupId, String purpose, Long memberId) throws Exception {
        boolean sessionLevel = SESSION_GROUP_PURPOSES.contains(purpose);
        if (!sessionLevel && !MEMBER_GROUP_PURPOSES.containsKey(purpose)) {
            throw GameErrors.invalid("Invalid group purpose");
        }
        if (sessionLevel != (memberId == null)) {
            throw GameErrors.invalid(sessionLevel
                    ? "A " + purpose + " group belongs to the session, not to a member"
                    : "A " + purpose + " group belongs to one member");
        }
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            if (memberId != null) {
                GameMember member = referenced(transaction, sessionId, memberId, "Member");
                if (!member.role().equals(MEMBER_GROUP_PURPOSES.get(purpose))) {
                    throw GameErrors.invalid("A " + purpose + " group belongs to a "
                            + MEMBER_GROUP_PURPOSES.get(purpose));
                }
            }
            if (GameResourceRepository.groupByPurpose(transaction, sessionId, purpose, memberId).isPresent()) {
                throw GameErrors.conflict(memberId == null
                        ? "This game session already has a " + purpose + " group"
                        : "This member already has a " + purpose + " group");
            }
            unowned(transaction, GameResource.Kind.GROUP, groupId);
            long id = GameResourceRepository.insertGroup(transaction, sessionId, groupId, purpose, memberId);
            transaction.bumpSession();
            return (GameResource.Group) find(transaction, GameResource.Kind.GROUP, sessionId, id);
        });
    }

    /** Adds a managed geofence for a geofence plan key, which is unique in the session. */
    public GameResource.Geofence addGeofence(long sessionId, long geofenceId, String planKey, String purpose)
            throws Exception {
        if (!GEOFENCE_PURPOSES.contains(purpose)) {
            throw GameErrors.invalid("Invalid geofence purpose");
        }
        String validKey = GameErrors.text(planKey, 128, "geofence plan key");
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            if (GameResourceRepository.geofenceByPlanKey(transaction, sessionId, validKey).isPresent()) {
                throw GameErrors.conflict("This geofence plan key already has a geofence in this game session");
            }
            unowned(transaction, GameResource.Kind.GEOFENCE, geofenceId);
            long id = GameResourceRepository.insertGeofence(transaction, sessionId, geofenceId, validKey, purpose);
            transaction.bumpSession();
            return (GameResource.Geofence) find(transaction, GameResource.Kind.GEOFENCE, sessionId, id);
        });
    }

    // Lifecycle ---------------------------------------------------------------------------------------------

    /** Marks a row retiring; its Traccar resource stays owned until the row is removed. Idempotent. */
    public GameResource retire(GameResource.Kind kind, long sessionId, long id) throws Exception {
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            GameResource resource = find(transaction, kind, sessionId, id);
            if (!resource.active()) {
                return resource;
            }
            GameResourceRepository.setState(transaction, kind, sessionId, id, GameResource.RETIRING);
            transaction.bumpSession();
            return find(transaction, kind, sessionId, id);
        });
    }

    /** Takes a retiring row back into use, while its Traccar resource and its members still exist. Idempotent. */
    public GameResource reactivate(GameResource.Kind kind, long sessionId, long id) throws Exception {
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            GameResource resource = find(transaction, kind, sessionId, id);
            if (resource.active()) {
                return resource;
            }
            if (resource.resourceId() == null) {
                throw GameErrors.conflict("The Traccar resource of this row was deleted; remove the row instead");
            }
            if (resource instanceof GameResource.Mirror mirror) {
                referenced(transaction, sessionId, mirror.sourceMemberId(), "Source member");
                if (mirror.destinationMemberId() != null) {
                    referenced(transaction, sessionId, mirror.destinationMemberId(), "Destination member");
                }
            } else if (resource instanceof GameResource.Group group && group.memberId() != null) {
                referenced(transaction, sessionId, group.memberId(), "Member");
            }
            GameResourceRepository.setState(transaction, kind, sessionId, id, GameResource.ACTIVE);
            transaction.bumpSession();
            return find(transaction, kind, sessionId, id);
        });
    }

    /** Replaces the Traccar resource of an active row, after it was deleted or recreated in Traccar. */
    public GameResource rebind(GameResource.Kind kind, long sessionId, long id, long resourceId) throws Exception {
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            GameResource resource = find(transaction, kind, sessionId, id);
            if (!resource.active()) {
                throw GameErrors.conflict("A retiring resource row keeps its Traccar resource");
            }
            if (Objects.equals(resource.resourceId(), resourceId)) {
                return resource;
            }
            if (kind == GameResource.Kind.MIRROR) {
                mirrorDevice(transaction, resourceId);
            } else {
                unowned(transaction, kind, resourceId);
            }
            GameResourceRepository.setResource(transaction, kind, sessionId, id, resourceId);
            transaction.bumpSession();
            return find(transaction, kind, sessionId, id);
        });
    }

    /**
     * Removes a retiring row after cleanup: from then on its Traccar resource, if it still exists, no longer
     * belongs to the game. Active rows must be retired first.
     */
    public void remove(GameResource.Kind kind, long sessionId, long id) throws Exception {
        transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            GameResource resource = find(transaction, kind, sessionId, id);
            if (resource.active()) {
                throw GameErrors.conflict("Retire the resource row and clean up its Traccar resource first");
            }
            GameResourceRepository.delete(transaction, kind, sessionId, id);
            transaction.bumpSession();
            return null;
        });
    }

}
