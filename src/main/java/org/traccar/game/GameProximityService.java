/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Participant proximity of data model 2 (docs/participant-proximity.md): temporary reciprocal access grants
 * between players and supporters, and hunter circles.
 *
 * <p>The controlling Tool publishes the whole proximity content based on the revision it read. There is no
 * generation (decision 4): a reset publishes empty content with the next revision. The expected revision is checked
 * before the content hash: a write based on the current revision applies (or changes nothing when the content is
 * identical), a retry of the write that already succeeded (expected revision + 1, identical content hash) answers
 * the current state, and anything else answers 409. A binding change revokes grants server-side and marks the hash
 * {@link GameProximityRepository#REVOKED}, so such a retry conflicts.
 */
@Singleton
public class GameProximityService {

    public static final int MAX_GRANTS = 10000;
    public static final int MAX_CIRCLES = 1000;

    /** A grant as published: the recipient member sees the target member's tracker until it expires. */
    public record GrantInput(String episodeId, long recipientMemberId, long targetMemberId, String expiresAt) {
    }

    public record CircleInput(String id, double latitude, double longitude, double diameterMeters, String expiresAt,
                              List<Long> memberIds) {
    }

    public record Grant(String episodeId, long recipientMemberId, long targetMemberId, long userId, long deviceId,
                        Instant expiresAt) {
    }

    /** A circle with its members; the counts are derived from the members' roles. */
    public record Circle(String id, double latitude, double longitude, double diameterMeters, int playerCount,
                         int supporterCount, Instant expiresAt, List<Long> memberIds) {
    }

    /** What members see of a circle: no member identities. */
    public record PublicCircle(String id, double latitude, double longitude, double diameterMeters, int playerCount,
                               int supporterCount, Instant expiresAt) {
    }

    public record Snapshot(long revision, List<Grant> grants, List<Circle> circles) {
    }

    private final GameTransactions transactions;
    private final GamePublisher publisher;

    @Inject
    public GameProximityService(GameTransactions transactions, GamePublisher publisher) {
        this.transactions = transactions;
        this.publisher = publisher;
    }

    private static boolean token(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}");
    }

    /** Checks the shape of published content: reciprocal grant episodes and well-formed circles. */
    static void validate(List<GrantInput> grants, List<CircleInput> circles) {
        if (grants == null || circles == null || grants.size() > MAX_GRANTS || circles.size() > MAX_CIRCLES) {
            throw GameErrors.invalid("Invalid proximity content");
        }
        Set<String> pairs = new HashSet<>();
        Map<String, List<GrantInput>> episodes = new HashMap<>();
        for (GrantInput grant : grants) {
            if (grant == null || !token(grant.episodeId()) || grant.recipientMemberId() <= 0
                    || grant.targetMemberId() <= 0 || grant.recipientMemberId() == grant.targetMemberId()
                    || !pairs.add(grant.recipientMemberId() + ":" + grant.targetMemberId())) {
                throw GameErrors.invalid("Invalid proximity grant");
            }
            GameTime.parse(grant.expiresAt(), "proximity expiry");
            episodes.computeIfAbsent(grant.episodeId(), key -> new ArrayList<>()).add(grant);
        }
        for (List<GrantInput> episode : episodes.values()) {
            if (episode.size() != 2 || episode.get(0).recipientMemberId() != episode.get(1).targetMemberId()
                    || episode.get(1).recipientMemberId() != episode.get(0).targetMemberId()
                    || !GameTime.parse(episode.get(0).expiresAt(), "proximity expiry")
                            .equals(GameTime.parse(episode.get(1).expiresAt(), "proximity expiry"))) {
                throw GameErrors.invalid("Proximity grants must be reciprocal");
            }
        }
        Set<String> ids = new HashSet<>();
        for (CircleInput circle : circles) {
            if (circle == null || !token(circle.id()) || !ids.add(circle.id())
                    || !Double.isFinite(circle.latitude()) || Math.abs(circle.latitude()) > 90
                    || !Double.isFinite(circle.longitude()) || Math.abs(circle.longitude()) > 180
                    || !Double.isFinite(circle.diameterMeters()) || circle.diameterMeters() <= 0
                    || circle.memberIds() == null || circle.memberIds().size() < 2
                    || circle.memberIds().stream().anyMatch(id -> id == null || id <= 0)
                    || new HashSet<>(circle.memberIds()).size() != circle.memberIds().size()) {
                throw GameErrors.invalid("Invalid proximity circle");
            }
            GameTime.parse(circle.expiresAt(), "proximity expiry");
        }
    }

    /** A hash of the content that does not depend on the order of grants, circles and circle members. */
    static String contentHash(List<GrantInput> grants, List<CircleInput> circles) throws Exception {
        List<String> lines = new ArrayList<>();
        for (GrantInput grant : grants) {
            lines.add("g|" + grant.episodeId() + "|" + grant.recipientMemberId() + "|" + grant.targetMemberId() + "|"
                    + GameTime.parse(grant.expiresAt(), "proximity expiry").toEpochMilli());
        }
        for (CircleInput circle : circles) {
            lines.add("c|" + circle.id() + "|" + Double.doubleToLongBits(circle.latitude()) + "|"
                    + Double.doubleToLongBits(circle.longitude()) + "|"
                    + Double.doubleToLongBits(circle.diameterMeters()) + "|"
                    + GameTime.parse(circle.expiresAt(), "proximity expiry").toEpochMilli() + "|"
                    + new TreeSet<>(circle.memberIds()));
        }
        lines.sort(Comparator.naturalOrder());
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    /** Active players and supporters of a session, by member ID. */
    private static Map<Long, GameMember> participants(GameTransaction transaction, long sessionId) throws Exception {
        Map<Long, GameMember> result = new HashMap<>();
        for (GameMember member : GameMemberRepository.list(transaction, sessionId)) {
            if (member.active() && GameMember.hasState(member.role())) {
                result.put(member.id(), member);
            }
        }
        return result;
    }

    static Snapshot read(GameTransaction transaction, long sessionId, Instant now) throws Exception {
        long revision = GameProximityRepository.state(transaction, sessionId)
                .map(GameProximityRepository.State::revision).orElse(0L);
        Map<Long, GameMember> participants = participants(transaction, sessionId);
        List<Grant> grants = new ArrayList<>();
        for (var grant : GameProximityRepository.grants(transaction, sessionId, now)) {
            grants.add(new Grant(grant.episodeId(), grant.recipientMemberId(), grant.targetMemberId(),
                    grant.userId(), grant.deviceId(), grant.expiresAt()));
        }
        List<Circle> circles = new ArrayList<>();
        for (var circle : GameProximityRepository.circles(transaction, sessionId, now)) {
            List<Long> members = GameProximityRepository.circleMembers(transaction, sessionId, circle.circleId());
            int players = (int) members.stream().filter(id -> participants.containsKey(id)
                    && GameMember.PLAYER.equals(participants.get(id).role())).count();
            circles.add(new Circle(circle.circleId(), circle.latitude(), circle.longitude(),
                    circle.diameterMeters(), players, members.size() - players, circle.expiresAt(), members));
        }
        return new Snapshot(revision, grants, circles);
    }

    /** The operators' view: revision, unexpired grants and circles with their members. */
    public Snapshot snapshot(long sessionId) throws Exception {
        return transactions.read(transaction -> {
            GameDocumentService.sessionExists(transaction, sessionId);
            return read(transaction, sessionId, transaction.now());
        });
    }

    /** The members' view of the circles. */
    public List<PublicCircle> circles(long sessionId) throws Exception {
        return snapshot(sessionId).circles().stream().map(circle -> new PublicCircle(circle.id(), circle.latitude(),
                circle.longitude(), circle.diameterMeters(), circle.playerCount(), circle.supporterCount(),
                circle.expiresAt())).toList();
    }

    /**
     * Replaces the published content based on {@code expectedRevision}. Grant recipients and targets must be active
     * players or supporters bound to an account and a tracker; circles name active players and supporters.
     */
    public Snapshot replace(long sessionId, long expectedRevision, List<GrantInput> grants, List<CircleInput> circles)
            throws Exception {
        validate(grants, circles);
        String hash = contentHash(grants, circles);
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            var state = GameProximityRepository.state(transaction, sessionId);
            long current = state.map(GameProximityRepository.State::revision).orElse(0L);
            boolean sameContent = state.isPresent() && hash.equals(state.get().contentHash());
            if (expectedRevision != current) {
                if (expectedRevision + 1 == current && sameContent) {
                    return read(transaction, sessionId, transaction.now());
                }
                throw GameErrors.revisionConflict("Proximity", current);
            }
            if (sameContent) {
                return read(transaction, sessionId, transaction.now());
            }
            Map<Long, GameMember> participants = participants(transaction, sessionId);
            List<GameProximityRepository.Grant> resolved = new ArrayList<>();
            for (GrantInput grant : grants) {
                GameMember recipient = participants.get(grant.recipientMemberId());
                GameMember target = participants.get(grant.targetMemberId());
                if (recipient == null || target == null) {
                    throw GameErrors.invalid("Proximity grants are between active players and supporters");
                }
                if (recipient.userId() == null || target.trackerId() == null) {
                    throw GameErrors.conflict("A proximity grant names a member without account or tracker");
                }
                resolved.add(new GameProximityRepository.Grant(grant.episodeId(), recipient.id(), target.id(),
                        recipient.userId(), target.trackerId(),
                        GameTime.parse(grant.expiresAt(), "proximity expiry")));
            }
            for (CircleInput circle : circles) {
                if (!participants.keySet().containsAll(circle.memberIds())) {
                    throw GameErrors.invalid("Proximity circles contain active players and supporters");
                }
            }
            Set<Long> users = new LinkedHashSet<>(GameProximityRepository.recipients(transaction, sessionId));
            GameProximityRepository.clear(transaction, sessionId);
            for (var grant : resolved) {
                GameProximityRepository.insertGrant(transaction, sessionId, grant);
                users.add(grant.userId());
            }
            for (CircleInput circle : circles) {
                GameProximityRepository.insertCircle(transaction, sessionId, new GameProximityRepository.Circle(
                        circle.id(), circle.latitude(), circle.longitude(), circle.diameterMeters(),
                        GameTime.parse(circle.expiresAt(), "proximity expiry")), circle.memberIds());
            }
            GameProximityRepository.putState(transaction, sessionId, current + 1, hash);
            transaction.bumpSession();
            transaction.afterCommit(() -> publisher.proximityAccess(sessionId, users));
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(sessionId, GamePublisher.PROXIMITY, null)));
            return read(transaction, sessionId, transaction.now());
        });
    }

    /** Clears the proximity content of a session with the next revision (a reset; no generation). */
    public Snapshot reset(long sessionId) throws Exception {
        long revision = transactions.read(transaction -> {
            GameDocumentService.sessionExists(transaction, sessionId);
            return GameProximityRepository.state(transaction, sessionId)
                    .map(GameProximityRepository.State::revision).orElse(0L);
        });
        return replace(sessionId, revision, List.of(), List.of());
    }

    /**
     * Deletes expired grants and circles of every session. Readers never see expired rows, so this changes no
     * revision; it keeps the access tables small, and after commit the recipients of expired grants have their
     * device access recomputed, so their WebSocket stops carrying the target's positions (model 1's expiry).
     *
     * @return the number of deleted grants and circles
     */
    public int purgeExpired() throws Exception {
        List<Long> sessions = transactions.read(transaction -> transaction.list(
                "SELECT DISTINCT gamesessionid FROM tc_game_proximity_grants WHERE expiresat <= ?"
                        + " UNION SELECT DISTINCT gamesessionid FROM tc_game_proximity_circles WHERE expiresat <= ?",
                rows -> rows.getLong(1), transaction.now(), transaction.now()));
        int deleted = 0;
        for (long sessionId : sessions) {
            try {
                deleted += purgeExpired(sessionId);
            } catch (jakarta.ws.rs.NotFoundException deletedMeanwhile) {
                // The session was deleted after the scan, together with its grants and circles.
            }
        }
        return deleted;
    }

    private int purgeExpired(long sessionId) throws Exception {
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            Instant now = transaction.now();
            Set<Long> recipients = Set.copyOf(transaction.list("SELECT DISTINCT userid FROM tc_game_proximity_grants"
                    + " WHERE gamesessionid = ? AND expiresat <= ?", rows -> rows.getLong(1), sessionId, now));
            int grants = transaction.update("DELETE FROM tc_game_proximity_grants WHERE gamesessionid = ?"
                    + " AND expiresat <= ?", sessionId, now);
            int count = grants;
            if (grants > 0) {
                transaction.afterCommit(() -> publisher.proximityAccess(sessionId, recipients));
            }
            transaction.update("DELETE FROM tc_game_proximity_circle_members WHERE gamesessionid = ?"
                    + " AND circleid IN (SELECT circleid FROM tc_game_proximity_circles WHERE gamesessionid = ?"
                    + " AND expiresat <= ?)", sessionId, sessionId, now);
            count += transaction.update("DELETE FROM tc_game_proximity_circles WHERE gamesessionid = ?"
                    + " AND expiresat <= ?", sessionId, now);
            return count;
        });
    }

}
