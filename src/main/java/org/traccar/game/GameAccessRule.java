/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Mirror-only hunter access (model 1, 4c), on members and resource rows. Break and fake jokers protect a participant
 * only while hunters see them through mirrors, so while a session has break or fake jokers allocated, held or active
 * (granted or used above zero, or a reservation still held, such as a break planned beyond the allocation), no active
 * hunter of the session may access the tracker of an active player or supporter: neither directly nor through a
 * group, at any nesting depth. Spectators keep their real access by design.
 *
 * <p>The rule is checked where access or jokers are granted: joker grants and planned breaks (409), and binding
 * changes, permission grants and device and group moves (422, because 409 means a revision conflict there). Only
 * access a change adds is refused, so a change never fails for access that existed before it. A break does not start
 * while a hunter could see the participant's tracker (409), whatever gave that access.
 */
@Singleton
public class GameAccessRule implements GameBindingHook {

    public static final Set<String> MIRROR_ONLY_TYPES = Set.of(GameJokerRepository.BREAK,
            GameJokerRepository.FAKE_PING);
    /** Status of refused binding, permission, device and group changes. */
    public static final int REFUSED = 422;

    /** A change checked before it is made: new user permissions and new parents of devices and groups. */
    public record Change(List<long[]> userDevices, List<long[]> userGroups, Map<Long, Long> deviceGroups,
                         Map<Long, Long> groupParents) {
        public static final Change NONE = new Change(List.of(), List.of(), Map.of(), Map.of());

        public boolean isEmpty() {
            return userDevices.isEmpty() && userGroups.isEmpty() && deviceGroups.isEmpty() && groupParents.isEmpty();
        }
    }

    /** A hunter member whose account could see a participant member's tracker. */
    public record Violation(long gameSessionId, long hunterMemberId, String hunter, long participantMemberId,
                            long deviceId) {
    }

    private final GameTransactions transactions;

    @Inject
    public GameAccessRule(GameTransactions transactions) {
        this.transactions = transactions;
    }

    /** The sessions with break or fake jokers allocated, held or active, as a query of four parameters. */
    private static final String PROTECTED_SESSIONS = "SELECT gamesessionid FROM tc_game_jokers WHERE type IN (?, ?)"
            + " AND (granted > 0 OR used > 0) UNION SELECT gamesessionid FROM tc_game_joker_reservations"
            + " WHERE type IN (?, ?) AND status = '" + GameJokerRepository.RESERVED + "'";

    private static Object[] protectedParameters(Object... more) {
        List<Object> result = new ArrayList<>(List.of(GameJokerRepository.BREAK, GameJokerRepository.FAKE_PING,
                GameJokerRepository.BREAK, GameJokerRepository.FAKE_PING));
        result.addAll(List.of(more));
        return result.toArray();
    }

    /** Whether a session has break or fake jokers allocated, held or active. */
    static boolean required(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.exists("SELECT 1 FROM (" + PROTECTED_SESSIONS + ") protected_sessions"
                + " WHERE gamesessionid = ?", protectedParameters(sessionId));
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static Map<Long, Set<Long>> links(GameTransaction transaction, String table, String column,
                                              Collection<Long> users) throws SQLException {
        Map<Long, Set<Long>> result = new HashMap<>();
        transaction.list("SELECT userid, " + column + " FROM " + table + " WHERE userid IN ("
                + placeholders(users.size()) + ")", rows -> result.computeIfAbsent(rows.getLong(1),
                        key -> new HashSet<>()).add(rows.getLong(2)), users.toArray());
        return result;
    }

    /** The hunters of a session that would access its participants' trackers after the change. */
    static List<Violation> violations(GameTransaction transaction, long sessionId, Change change)
            throws SQLException {
        record Hunter(long memberId, String label) {
        }
        Map<Long, Hunter> hunters = new LinkedHashMap<>();
        Map<Long, Long> trackers = new java.util.TreeMap<>();
        for (GameMember member : GameMemberRepository.list(transaction, sessionId)) {
            if (!member.active()) {
                continue;
            }
            if (GameMember.HUNTER.equals(member.role()) && member.userId() != null) {
                hunters.put(member.userId(), new Hunter(member.id(), member.label()));
            } else if (GameMember.hasState(member.role()) && member.trackerId() != null) {
                trackers.put(member.trackerId(), member.id());
            }
        }
        if (hunters.isEmpty() || trackers.isEmpty()) {
            return List.of();
        }
        Map<Long, Set<Long>> devices = links(transaction, "tc_user_device", "deviceid", hunters.keySet());
        Map<Long, Set<Long>> groups = links(transaction, "tc_user_group", "groupid", hunters.keySet());
        for (long[] grant : change.userDevices()) {
            devices.computeIfAbsent(grant[0], key -> new HashSet<>()).add(grant[1]);
        }
        for (long[] grant : change.userGroups()) {
            groups.computeIfAbsent(grant[0], key -> new HashSet<>()).add(grant[1]);
        }
        Map<Long, Long> parents = new HashMap<>();
        transaction.list("SELECT id, groupid FROM tc_groups", rows -> {
            long parent = rows.getLong(2);
            return parents.put(rows.getLong(1), rows.wasNull() || parent <= 0 ? null : parent);
        });
        parents.putAll(change.groupParents());
        Map<Long, Long> trackerGroups = new HashMap<>();
        transaction.list("SELECT id, groupid FROM tc_devices WHERE id IN (" + placeholders(trackers.size()) + ")",
                rows -> {
                    long group = rows.getLong(2);
                    return trackerGroups.put(rows.getLong(1), rows.wasNull() || group <= 0 ? null : group);
                }, trackers.keySet().toArray());
        for (var entry : change.deviceGroups().entrySet()) {
            if (trackers.containsKey(entry.getKey())) {
                trackerGroups.put(entry.getKey(), entry.getValue());
            }
        }
        List<Violation> result = new ArrayList<>();
        for (var tracker : trackers.entrySet()) {
            // The tracker's group and every ancestor; a cycle ends the walk.
            Set<Long> ancestors = new HashSet<>();
            Long group = trackerGroups.get(tracker.getKey());
            while (group != null && ancestors.add(group)) {
                group = parents.get(group);
            }
            for (var hunter : hunters.entrySet()) {
                boolean direct = devices.getOrDefault(hunter.getKey(), Set.of()).contains(tracker.getKey());
                boolean inherited = groups.getOrDefault(hunter.getKey(), Set.of()).stream()
                        .anyMatch(ancestors::contains);
                if (direct || inherited) {
                    result.add(new Violation(sessionId, hunter.getValue().memberId(), hunter.getValue().label(),
                            tracker.getValue(), tracker.getKey()));
                }
            }
        }
        return result;
    }

    static String message(List<Violation> violations, String action) {
        Violation first = violations.getFirst();
        String more = violations.size() > 1 ? " (and " + (violations.size() - 1) + " more)" : "";
        return action + ": hunters must see players and supporters only through mirrors while break or fake jokers"
                + " are allocated or active, but the " + first.hunter() + " could see the real tracker of device "
                + first.deviceId() + more + ". Remove that access first.";
    }

    /** Refuses a break or fake joker grant while hunters of the session see real participant trackers (409). */
    static void checkGrant(GameTransaction transaction, long sessionId, String type) throws SQLException {
        if (!MIRROR_ONLY_TYPES.contains(type)) {
            return;
        }
        List<Violation> found = violations(transaction, sessionId, Change.NONE);
        if (!found.isEmpty()) {
            throw GameErrors.conflict(message(found, "Break and fake jokers cannot be granted"));
        }
    }

    /** Refuses the start of a member's break while a hunter of the session could see the member's tracker (409). */
    static void checkBreakStart(GameTransaction transaction, long sessionId, long memberId) throws SQLException {
        List<Violation> found = violations(transaction, sessionId, Change.NONE).stream()
                .filter(violation -> violation.participantMemberId() == memberId).toList();
        if (!found.isEmpty()) {
            throw GameErrors.conflict(message(found, "The break cannot start"));
        }
    }

    private static jakarta.ws.rs.WebApplicationException refused(String message) {
        return new jakarta.ws.rs.WebApplicationException(message, REFUSED);
    }

    /**
     * A binding change of a protected session must not add hunter access to a real participant tracker: a hunter's
     * new account that sees one, or a participant's new tracker that a hunter sees (422).
     */
    @Override
    public void bindingChanged(GameTransaction transaction, GameMember before, GameMember after) throws Exception {
        if (!after.active() || Objects.equals(before.userId(), after.userId())
                && Objects.equals(before.trackerId(), after.trackerId())) {
            return;
        }
        long sessionId = after.gameSessionId();
        if (!required(transaction, sessionId)) {
            return;
        }
        List<Violation> added = new ArrayList<>();
        for (Violation violation : violations(transaction, sessionId, Change.NONE)) {
            if (violation.hunterMemberId() == after.id() && !Objects.equals(before.userId(), after.userId())
                    || violation.participantMemberId() == after.id()
                            && !Objects.equals(before.trackerId(), after.trackerId())) {
                added.add(violation);
            }
        }
        if (!added.isEmpty()) {
            throw refused(message(added, "The binding cannot be changed"));
        }
    }

    /** Refuses a permission, device or group change that would break the rule in any protected session (422). */
    public void check(Change change) throws Exception {
        if (change == null || change.isEmpty()) {
            return;
        }
        transactions.read(transaction -> {
            List<Long> sessions = transaction.list("SELECT DISTINCT gamesessionid FROM (" + PROTECTED_SESSIONS
                    + ") protected_sessions ORDER BY gamesessionid", rows -> rows.getLong(1),
                    protectedParameters());
            for (long sessionId : sessions) {
                Set<List<Long>> known = new TreeSet<>((a, b) -> a.get(0).equals(b.get(0))
                        ? Long.compare(a.get(1), b.get(1)) : Long.compare(a.get(0), b.get(0)));
                for (Violation item : violations(transaction, sessionId, Change.NONE)) {
                    known.add(List.of(item.hunterMemberId(), item.deviceId()));
                }
                List<Violation> added = new ArrayList<>();
                for (Violation item : violations(transaction, sessionId, change)) {
                    if (!known.contains(List.of(item.hunterMemberId(), item.deviceId()))) {
                        added.add(item);
                    }
                }
                if (!added.isEmpty()) {
                    throw refused(message(added, "This access cannot be granted"));
                }
            }
            return null;
        });
    }

}
