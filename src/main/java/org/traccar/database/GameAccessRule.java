/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.database;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mirror-only hunter access. Break and fake jokers protect a participant only while hunters see them through
 * mirrors, so while a session has break or fake jokers allocated or active (granted or used), no hunter of that
 * session may access a real tracker of the session's players and supporters: neither directly nor through a
 * group, at any nesting depth. Spectators keep their real access by design.
 * <p>
 * The rule is checked where access or jokers are granted: joker grants, permission grants, device and group
 * moves, and membership changes. A change that would break it is refused before it is made.
 */
@Singleton
public class GameAccessRule {

    public static final Set<String> MIRROR_ONLY_TYPES = Set.of(GameJokerService.BREAK, GameJokerService.FAKE_PING);
    /** Status of refused permission, device, group and membership changes (409 means a revision conflict). */
    public static final int REFUSED = 422;

    /** A change checked before it is made: new user permissions and new parents of devices and groups. */
    public record Change(List<long[]> userDevices, List<long[]> userGroups, Map<Long, Long> deviceGroups,
                         Map<Long, Long> groupParents) {
        public static final Change NONE = new Change(List.of(), List.of(), Map.of(), Map.of());

        public boolean isEmpty() {
            return userDevices.isEmpty() && userGroups.isEmpty() && deviceGroups.isEmpty()
                    && groupParents.isEmpty();
        }
    }

    public record Violation(long gameSessionId, long hunterUserId, String hunter, long deviceId) { }

    private final DataSource dataSource;

    @Inject
    public GameAccessRule(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Whether the session has break or fake jokers allocated or active. */
    static boolean required(Connection connection, long gameSessionId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT 1 FROM tc_game_jokers WHERE gamesessionid = ?"
                + " AND type IN (?,?) AND (granted > 0 OR used > 0)")) {
            query.setLong(1, gameSessionId);
            query.setString(2, GameJokerService.BREAK);
            query.setString(3, GameJokerService.FAKE_PING);
            query.setMaxRows(1);
            try (var rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static List<Long> requiringSessions(Connection connection) throws SQLException {
        List<Long> result = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT DISTINCT gamesessionid FROM tc_game_jokers"
                + " WHERE type IN (?,?) AND (granted > 0 OR used > 0)")) {
            query.setString(1, GameJokerService.BREAK);
            query.setString(2, GameJokerService.FAKE_PING);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(rows.getLong(1));
                }
            }
        }
        return result;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static Map<Long, Set<Long>> links(Connection connection, String table, String column,
                                              Collection<Long> users) throws SQLException {
        Map<Long, Set<Long>> result = new HashMap<>();
        try (var query = connection.prepareStatement("SELECT userid," + column + " FROM " + table
                + " WHERE userid IN (" + placeholders(users.size()) + ")")) {
            int index = 1;
            for (long user : users) {
                query.setLong(index++, user);
            }
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    result.computeIfAbsent(rows.getLong(1), key -> new HashSet<>()).add(rows.getLong(2));
                }
            }
        }
        return result;
    }

    /** The hunters of a session that would access its real participant trackers after the change. */
    static List<Violation> violations(Connection connection, long gameSessionId, Change change) throws SQLException {
        Map<Long, String> hunters = new LinkedHashMap<>();
        Set<Long> trackers = new java.util.TreeSet<>();
        try (var query = connection.prepareStatement("SELECT userid,role,assigneddeviceid,login"
                + " FROM tc_game_session_members WHERE gamesessionid = ? ORDER BY userid")) {
            query.setLong(1, gameSessionId);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    String role = rows.getString("role");
                    if ("hunter".equals(role)) {
                        hunters.put(rows.getLong("userid"), rows.getString("login"));
                    } else if ("player".equals(role) || "supporter".equals(role)) {
                        long device = rows.getLong("assigneddeviceid");
                        if (!rows.wasNull()) {
                            trackers.add(device);
                        }
                    }
                }
            }
        }
        if (hunters.isEmpty() || trackers.isEmpty()) {
            return List.of();
        }
        Map<Long, Set<Long>> devices = links(connection, "tc_user_device", "deviceid", hunters.keySet());
        Map<Long, Set<Long>> groups = links(connection, "tc_user_group", "groupid", hunters.keySet());
        for (long[] grant : change.userDevices()) {
            devices.computeIfAbsent(grant[0], key -> new HashSet<>()).add(grant[1]);
        }
        for (long[] grant : change.userGroups()) {
            groups.computeIfAbsent(grant[0], key -> new HashSet<>()).add(grant[1]);
        }
        Map<Long, Long> parents = new HashMap<>();
        try (var query = connection.prepareStatement("SELECT id,groupid FROM tc_groups")) {
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    long parent = rows.getLong(2);
                    parents.put(rows.getLong(1), rows.wasNull() || parent <= 0 ? null : parent);
                }
            }
        }
        parents.putAll(change.groupParents());
        Map<Long, Long> trackerGroups = new HashMap<>();
        try (var query = connection.prepareStatement("SELECT id,groupid FROM tc_devices WHERE id IN ("
                + placeholders(trackers.size()) + ")")) {
            int index = 1;
            for (long tracker : trackers) {
                query.setLong(index++, tracker);
            }
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    long group = rows.getLong(2);
                    trackerGroups.put(rows.getLong(1), rows.wasNull() || group <= 0 ? null : group);
                }
            }
        }
        for (var entry : change.deviceGroups().entrySet()) {
            if (trackers.contains(entry.getKey())) {
                trackerGroups.put(entry.getKey(), entry.getValue());
            }
        }
        List<Violation> result = new ArrayList<>();
        for (long tracker : trackers) {
            // The tracker's group and every ancestor; a cycle ends the walk.
            Set<Long> ancestors = new HashSet<>();
            Long group = trackerGroups.get(tracker);
            while (group != null && ancestors.add(group)) {
                group = parents.get(group);
            }
            for (var hunter : hunters.entrySet()) {
                boolean direct = devices.getOrDefault(hunter.getKey(), Set.of()).contains(tracker);
                boolean inherited = groups.getOrDefault(hunter.getKey(), Set.of()).stream()
                        .anyMatch(ancestors::contains);
                if (direct || inherited) {
                    result.add(new Violation(gameSessionId, hunter.getKey(), hunter.getValue(), tracker));
                }
            }
        }
        return result;
    }

    static String message(List<Violation> violations, String action) {
        Violation first = violations.getFirst();
        String more = violations.size() > 1 ? " (and " + (violations.size() - 1) + " more)" : "";
        return action + ": hunters must see players and supporters only through mirrors while break or fake"
                + " jokers are allocated or active, but hunter " + first.hunter() + " could see the real tracker"
                + " of device " + first.deviceId() + more + ". Remove that access first.";
    }

    /** Refuses a joker grant when hunters of the session already see real participant trackers. */
    static void checkGrant(Connection connection, long gameSessionId, String type) throws SQLException {
        if (!MIRROR_ONLY_TYPES.contains(type)) {
            return;
        }
        List<Violation> found = violations(connection, gameSessionId, Change.NONE);
        if (!found.isEmpty()) {
            throw new WebApplicationException(message(found, "Break and fake jokers cannot be granted"), 409);
        }
    }

    /** Violations that are in {@code after} but not in {@code before} (by hunter and tracker). */
    private static List<Violation> added(List<Violation> after, List<Violation> before) {
        Set<List<Long>> known = new HashSet<>();
        for (Violation item : before) {
            known.add(List.of(item.hunterUserId(), item.deviceId()));
        }
        List<Violation> result = new ArrayList<>();
        for (Violation item : after) {
            if (!known.contains(List.of(item.hunterUserId(), item.deviceId()))) {
                result.add(item);
            }
        }
        return result;
    }

    /** The current violations of a protected session, read before its members change; empty otherwise. */
    static List<Violation> beforeMembers(Connection connection, long gameSessionId) throws SQLException {
        return required(connection, gameSessionId) ? violations(connection, gameSessionId, Change.NONE) : List.of();
    }

    /**
     * Refuses new members that would let a hunter see real participant trackers of a protected session: a user
     * with such access becoming a hunter, or such a device becoming a participant tracker. Access the new members
     * do not add never blocks the save.
     */
    static void checkMembers(Connection connection, long gameSessionId, List<Violation> before)
            throws SQLException {
        if (!required(connection, gameSessionId)) {
            return;
        }
        List<Violation> found = added(violations(connection, gameSessionId, Change.NONE), before);
        if (!found.isEmpty()) {
            throw new WebApplicationException(message(found, "The session members cannot be saved"), REFUSED);
        }
    }

    /** Refuses a permission, device or group change that would break the rule in any protected session. */
    public void check(Change change) throws Exception {
        if (change == null || change.isEmpty()) {
            return;
        }
        try (var connection = dataSource.getConnection()) {
            for (long session : requiringSessions(connection)) {
                // Only access this change adds is refused; it never blocks unrelated edits.
                List<Violation> found = added(violations(connection, session, change),
                        violations(connection, session, Change.NONE));
                if (!found.isEmpty()) {
                    throw new WebApplicationException(message(found, "This access cannot be granted"), REFUSED);
                }
            }
        }
    }
}
