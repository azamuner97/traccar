/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The game notification journal of data model 2: stream metadata per session (tc_game_notification_state) and
 * retained history (tc_game_notifications), with recipients and sources as members.
 *
 * <p>The generation identifies the current history: clients discard what they cached when it changes. Every write
 * names the generation it expects, so a reset and an archive never interleave. Alert IDs are opaque, case-sensitive
 * tokens; archiving the same alert again creates nothing, and a different alert under a used ID answers 409.
 *
 * <p>Audiences follow model 1: {@code session} alerts reach every member, {@code hunters}/{@code nonHunters} a role
 * group (spectators count as hunters), {@code user} alerts one player, supporter or hunter about their own tracker,
 * and live-recipient alerts one recipient about another participant whose positions they receive.
 */
@Singleton
public class GameNotificationService {

    private static final Set<String> SESSION_TYPES = Set.of("speedhunt", "playerCaught", "notificationTest");
    private static final Set<String> TARGETED_TYPES = Set.of(
            "geofenceExit", "liveTrackingStarted", "liveTrackingStopped", "liveTrackingTargetsChanged",
            "participantProximityEnter", "participantProximityExit", "participantProximityConsequence");
    private static final Set<String> LIVE_RECIPIENT_TYPES = Set.of(
            "livePingReceived", "livePingEnded", "livePingTargetsChanged");
    private static final Map<String, String> LIVE_AUDIENCES = Map.of(
            GameMember.HUNTER, "liveHunter", GameMember.PLAYER, "livePlayer",
            GameMember.SUPPORTER, "liveSupporter", GameMember.SPECTATOR, "liveSpectator");

    public record State(long gameSessionId, String generation, Instant resetAt, long revision) {
    }

    /** An alert as the Tool archives it; {@code occurredAt} is an ISO-8601 instant. */
    public record Alert(String id, String type, String audience, String occurredAt, String timeZone, String message,
                        Long recipientMemberId, Long sourceMemberId) {
    }

    public record Entry(String id, String type, String audience, Instant occurredAt, String timeZone,
                        String message, Long recipientMemberId, String recipientLabel, Long sourceMemberId,
                        String sourceLabel, String generation, Instant createdAt) {
    }

    public record Creation(String generation, Entry item, boolean created) {
    }

    public record Page(String generation, Instant resetAt, List<Entry> items, String nextCursor, boolean hasMore) {
    }

    public record HistoryPage(long gameSessionId, List<Entry> items, String nextCursor, boolean hasMore) {
    }

    private final GameTransactions transactions;
    private final GamePublisher publisher;

    @Inject
    public GameNotificationService(GameTransactions transactions, GamePublisher publisher) {
        this.transactions = transactions;
        this.publisher = publisher;
    }

    public static String liveRecipientAudience(String role) {
        return LIVE_AUDIENCES.getOrDefault(role, "");
    }

    public static String generation(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw GameErrors.invalid("Invalid notification generation");
        }
        return value;
    }

    /** Checks an alert's shape and audience; answers 400. Members are checked against the session later. */
    static Instant validate(Alert alert, Instant now) {
        if (alert == null || alert.id() == null || !alert.id().matches("[A-Za-z0-9_-]{1,64}")
                || alert.type() == null || alert.message() == null || alert.message().isBlank()
                || alert.message().length() > 8192 || alert.timeZone() == null || alert.timeZone().length() > 64
                || alert.timeZone().startsWith("SystemV/")
                || !ZoneId.getAvailableZoneIds().contains(alert.timeZone())) {
            throw GameErrors.invalid("Invalid game notification");
        }
        Instant occurred = GameTime.parse(alert.occurredAt(), "notification time");
        if (!alert.occurredAt().endsWith("Z") || occurred.isAfter(now.plusSeconds(60))) {
            throw GameErrors.invalid("Invalid notification time");
        }
        boolean session = "session".equals(alert.audience()) && SESSION_TYPES.contains(alert.type())
                && alert.recipientMemberId() == null && alert.sourceMemberId() == null;
        boolean targeted = "user".equals(alert.audience()) && TARGETED_TYPES.contains(alert.type())
                && alert.recipientMemberId() != null && alert.recipientMemberId().equals(alert.sourceMemberId());
        boolean role = ("speedhunt".equals(alert.type()) || "participantProximityConsequence".equals(alert.type()))
                && ("hunters".equals(alert.audience()) || "nonHunters".equals(alert.audience()))
                && (!"participantProximityConsequence".equals(alert.type()) || "hunters".equals(alert.audience()))
                && alert.recipientMemberId() == null && alert.sourceMemberId() == null;
        boolean live = LIVE_RECIPIENT_TYPES.contains(alert.type()) && LIVE_AUDIENCES.containsValue(alert.audience())
                && alert.recipientMemberId() != null && alert.sourceMemberId() != null
                && !alert.recipientMemberId().equals(alert.sourceMemberId())
                && (!"livePingTargetsChanged".equals(alert.type()) || "liveSpectator".equals(alert.audience()));
        if (!session && !targeted && !role && !live) {
            throw GameErrors.invalid("Invalid notification audience or targets");
        }
        return occurred;
    }

    private static GameMember member(GameTransaction transaction, long sessionId, long memberId) throws SQLException {
        GameMember member = GameMemberRepository.find(transaction, sessionId, memberId)
                .orElseThrow(() -> GameErrors.invalid("Notification member not found in this game session"));
        if (!member.active()) {
            throw GameErrors.conflict("The " + member.label() + " is retired");
        }
        return member;
    }

    // State -------------------------------------------------------------------------------------------------

    static Optional<State> state(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.first("SELECT generation, resetat, revision FROM tc_game_notification_state"
                + " WHERE gamesessionid = ?", rows -> new State(sessionId, rows.getString(1),
                        GameTime.get(rows, "resetat"), rows.getLong(3)), sessionId);
    }

    /** The stream state; a session without one gets its first generation (inside a write transaction). */
    static State initialize(GameTransaction transaction, long sessionId) throws SQLException {
        Optional<State> state = state(transaction, sessionId);
        if (state.isPresent()) {
            return state.get();
        }
        String generation = UUID.randomUUID().toString();
        transaction.update("INSERT INTO tc_game_notification_state (gamesessionid, generation, resetat, revision)"
                + " VALUES (?, ?, ?, 1)", sessionId, generation, transaction.now());
        return new State(sessionId, generation, transaction.now(), 1);
    }

    public State initialize(long sessionId) throws Exception {
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            boolean created = state(transaction, sessionId).isEmpty();
            State state = initialize(transaction, sessionId);
            if (created) {
                transaction.bumpSession();
            }
            return state;
        });
    }

    /**
     * Starts a new generation and deletes the session's history. A repeated reset to the same replacement answers
     * the current state; any other generation mismatch answers 409.
     */
    public State reset(long sessionId, String expected, String replacement) throws Exception {
        generation(expected);
        generation(replacement);
        if (expected.equals(replacement)) {
            throw GameErrors.invalid("Reset requires a new generation");
        }
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            State state = initialize(transaction, sessionId);
            if (replacement.equals(state.generation())) {
                return state;
            }
            if (!expected.equals(state.generation())) {
                throw GameErrors.conflict("Notification generation changed");
            }
            transaction.update("DELETE FROM tc_game_notifications WHERE gamesessionid = ?", sessionId);
            transaction.update("UPDATE tc_game_notification_state SET generation = ?, resetat = ?, revision = ?"
                    + " WHERE gamesessionid = ?", replacement, transaction.now(), state.revision() + 1, sessionId);
            transaction.bumpSession();
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(sessionId, GamePublisher.NOTIFICATIONS, null)));
            return new State(sessionId, replacement, transaction.now(), state.revision() + 1);
        });
    }

    // Archive -----------------------------------------------------------------------------------------------

    private static Entry entry(ResultSet rows) throws SQLException {
        return new Entry(rows.getString("alertid"), rows.getString("type"), rows.getString("audience"),
                GameTime.get(rows, "occurredat"), rows.getString("timezone"), rows.getString("message"),
                GameTransaction.longOrNull(rows, "recipientmemberid"), rows.getString("recipientlabel"),
                GameTransaction.longOrNull(rows, "sourcememberid"), rows.getString("sourcelabel"),
                rows.getString("generation"), GameTime.get(rows, "createdat"));
    }

    private static final String ENTRIES = "SELECT alertid, type, audience, occurredat, timezone, message,"
            + " recipientmemberid, recipientlabel, sourcememberid, sourcelabel, generation, createdat, id"
            + " FROM tc_game_notifications";

    public Creation create(long sessionId, String expected, Alert alert) throws Exception {
        generation(expected);
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            Creation creation = create(transaction, sessionId, expected, alert);
            if (creation.created()) {
                notify(transaction, sessionId, List.of(creation.item()));
            }
            return creation;
        });
    }

    /** Registers the after-commit invalidations of newly archived alerts. */
    void notify(GameTransaction transaction, long sessionId, List<Entry> created) {
        boolean session = false;
        Set<Long> recipients = new java.util.LinkedHashSet<>();
        for (Entry item : created) {
            if (item.recipientMemberId() == null) {
                session = true;
            } else {
                recipients.add(item.recipientMemberId());
            }
        }
        if (session) {
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(sessionId, GamePublisher.NOTIFICATIONS, null)));
        } else if (!recipients.isEmpty()) {
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(sessionId, GamePublisher.NOTIFICATIONS, recipients)));
        }
    }

    /**
     * Archives an alert inside a transaction that holds the session lock (also from effects deliveries). An
     * identical repeat is not created again.
     */
    static Creation create(GameTransaction transaction, long sessionId, String expected, Alert alert)
            throws SQLException {
        Instant occurred = validate(alert, transaction.now());
        State state = initialize(transaction, sessionId);
        if (!state.generation().equals(expected)) {
            throw GameErrors.conflict("Notification generation changed");
        }
        String recipientLabel = null;
        String sourceLabel = null;
        if (alert.recipientMemberId() != null) {
            GameMember recipient = member(transaction, sessionId, alert.recipientMemberId());
            GameMember source = member(transaction, sessionId, alert.sourceMemberId());
            if ("user".equals(alert.audience())) {
                if (GameMember.SPECTATOR.equals(recipient.role())) {
                    throw GameErrors.invalid("Targeted notifications reach players, supporters and hunters");
                }
            } else if (!liveRecipientAudience(recipient.role()).equals(alert.audience())
                    || !GameMember.hasState(source.role())) {
                throw GameErrors.invalid("Live notification recipient or source is not eligible");
            }
            recipientLabel = recipient.displayName();
            sourceLabel = source.displayName();
        }
        Optional<Entry> existing = transaction.first(ENTRIES + " WHERE gamesessionid = ? AND generation = ?"
                + " AND alertid = ?", GameNotificationService::entry, sessionId, expected, alert.id());
        if (existing.isPresent()) {
            Entry stored = existing.get();
            boolean same = stored.type().equals(alert.type()) && stored.audience().equals(alert.audience())
                    && stored.occurredAt().equals(occurred) && stored.timeZone().equals(alert.timeZone())
                    && stored.message().equals(alert.message())
                    && java.util.Objects.equals(stored.recipientMemberId(), alert.recipientMemberId())
                    && java.util.Objects.equals(stored.sourceMemberId(), alert.sourceMemberId());
            if (!same) {
                throw GameErrors.conflict("Conflicting notification ID");
            }
            return new Creation(expected, stored, false);
        }
        transaction.insert("INSERT INTO tc_game_notifications (gamesessionid, generation, alertid, type, audience,"
                + " recipientmemberid, recipientlabel, sourcememberid, sourcelabel, occurredat, timezone, message,"
                + " createdat) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", sessionId, expected, alert.id(),
                alert.type(), alert.audience(), alert.recipientMemberId(), recipientLabel, alert.sourceMemberId(),
                sourceLabel, occurred, alert.timeZone(), alert.message(), transaction.now());
        return new Creation(expected, new Entry(alert.id(), alert.type(), alert.audience(), occurred,
                alert.timeZone(), alert.message(), alert.recipientMemberId(), recipientLabel,
                alert.sourceMemberId(), sourceLabel, expected, transaction.now()), true);
    }

    // Reading -----------------------------------------------------------------------------------------------

    private static long cursor(String cursor, int limit) {
        if (limit < 1 || limit > 500 || cursor != null && !cursor.matches("[0-9]{1,18}")) {
            throw GameErrors.invalid("Invalid notification pagination");
        }
        return cursor == null ? 0 : Long.parseLong(cursor);
    }

    /**
     * One page of the current generation. Members ({@code viewer}) see session alerts, their role group's alerts,
     * targeted alerts about themselves, and live-recipient alerts for their current role; operators (null) see
     * everything.
     */
    public Page list(long sessionId, String expected, String cursor, int requestedLimit, GameMember viewer)
            throws Exception {
        if (expected != null) {
            generation(expected);
        }
        int limit = requestedLimit == 0 ? 100 : requestedLimit;
        long after = cursor(cursor, limit);
        return transactions.read(transaction -> {
            GameDocumentService.sessionExists(transaction, sessionId);
            State state = state(transaction, sessionId).orElse(null);
            if (state == null) {
                return new Page(null, null, List.of(), Long.toString(after), false);
            }
            if (expected != null && !expected.equals(state.generation())) {
                throw GameErrors.conflict("Notification generation changed");
            }
            List<Object> params = new ArrayList<>(List.of(sessionId, state.generation(), after));
            String filter = "";
            if (viewer != null) {
                filter = " AND (audience = 'session'"
                        + " OR (type IN ('speedhunt', 'participantProximityConsequence') AND audience = ?)"
                        + " OR (audience = 'user' AND recipientmemberid = ? AND sourcememberid = ?)"
                        + " OR (recipientmemberid = ? AND audience = ?))";
                boolean hunters = GameMember.HUNTER.equals(viewer.role())
                        || GameMember.SPECTATOR.equals(viewer.role());
                params.addAll(List.of(hunters ? "hunters" : "nonHunters", viewer.id(), viewer.id(), viewer.id(),
                        liveRecipientAudience(viewer.role())));
            }
            List<Identified> rows = transaction.list(ENTRIES + " WHERE gamesessionid = ? AND generation = ?"
                    + " AND id > ?" + filter + " ORDER BY id", limit + 1, GameNotificationService::withId,
                    params.toArray());
            boolean more = rows.size() > limit;
            List<Identified> page = more ? rows.subList(0, limit) : rows;
            String next = page.isEmpty() ? Long.toString(after) : Long.toString(page.getLast().id());
            return new Page(state.generation(), state.resetAt(), page.stream().map(Identified::entry).toList(),
                    next, more);
        });
    }

    private record Identified(long id, Entry entry) {
    }

    private static Identified withId(ResultSet rows) throws SQLException {
        return new Identified(rows.getLong("id"), entry(rows));
    }

    /** Operator history of a session across generations. */
    public HistoryPage history(long sessionId, String cursor, int requestedLimit) throws Exception {
        int limit = requestedLimit == 0 ? 100 : requestedLimit;
        long after = cursor(cursor, limit);
        return transactions.read(transaction -> {
            GameDocumentService.sessionExists(transaction, sessionId);
            List<Identified> rows = transaction.list(ENTRIES + " WHERE gamesessionid = ? AND id > ? ORDER BY id",
                    limit + 1, GameNotificationService::withId, sessionId, after);
            boolean more = rows.size() > limit;
            List<Identified> page = more ? rows.subList(0, limit) : rows;
            String next = page.isEmpty() ? Long.toString(after) : Long.toString(page.getLast().id());
            return new HistoryPage(sessionId, page.stream().map(Identified::entry).toList(), next, more);
        });
    }

    public State state(long sessionId) throws Exception {
        return transactions.read(transaction -> {
            GameDocumentService.sessionExists(transaction, sessionId);
            return state(transaction, sessionId).orElse(null);
        });
    }

}
