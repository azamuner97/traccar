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
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The Traccar resources that game sessions own, derived from member bindings and resource rows: the model-2
 * counterpart of the model-1 {@code GameSessionService.owned()} that the controller fence reads.
 *
 * <p>Owned are the bound accounts and trackers of members, mirror devices, groups and managed geofences of every
 * session, including rows that are retiring, because they stay owned until cleanup removes them.
 */
@Singleton
public class GameOwnershipService {

    /** Traccar resources that belong to any session. */
    public record Owned(Set<Long> devices, Set<Long> groups, Set<Long> users, Set<Long> geofences) {
        public boolean any() {
            return !devices.isEmpty() || !groups.isEmpty() || !users.isEmpty() || !geofences.isEmpty();
        }
    }

    private static final int DEVICE = 1;
    private static final int USER = 2;
    private static final int GROUP = 3;
    private static final int GEOFENCE = 4;

    private static final String OWNED = "SELECT 1 AS kind, trackerid AS resourceid FROM tc_game_session_members"
            + " WHERE trackerid IS NOT NULL"
            + " UNION ALL SELECT 1, deviceid FROM tc_game_mirrors WHERE deviceid IS NOT NULL"
            + " UNION ALL SELECT 2, userid FROM tc_game_session_members WHERE userid IS NOT NULL"
            + " UNION ALL SELECT 3, groupid FROM tc_game_session_groups WHERE groupid IS NOT NULL"
            + " UNION ALL SELECT 4, geofenceid FROM tc_game_session_geofences WHERE geofenceid IS NOT NULL";

    private final GameTransactions transactions;

    @Inject
    public GameOwnershipService(GameTransactions transactions) {
        this.transactions = transactions;
    }

    /** Collects the game-owned resources of every session in one consistent read. */
    public Owned owned() throws Exception {
        return transactions.read(GameOwnershipService::owned);
    }

    public static Owned owned(GameTransaction transaction) throws SQLException {
        Set<Long> devices = new HashSet<>();
        Set<Long> users = new HashSet<>();
        Set<Long> groups = new HashSet<>();
        Set<Long> geofences = new HashSet<>();
        transaction.list(OWNED, rows -> {
            long id = rows.getLong("resourceid");
            switch (rows.getInt("kind")) {
                case DEVICE -> devices.add(id);
                case USER -> users.add(id);
                case GROUP -> groups.add(id);
                case GEOFENCE -> geofences.add(id);
                default -> throw new SQLException("Unknown owned resource kind");
            }
            return null;
        });
        return new Owned(devices, groups, users, geofences);
    }

    /** A session in which a device is a member's tracker, if any. */
    public static Optional<Long> trackerSession(GameTransaction transaction, long deviceId) throws SQLException {
        return transaction.first("SELECT gamesessionid FROM tc_game_session_members WHERE trackerid = ?",
                rows -> rows.getLong(1), deviceId);
    }

}
