/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;

import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The server's {@link GamePublisher}: what a committed game change publishes, through Traccar's caches, connection
 * manager and the push outbox. It runs after commit and never throws, because the change it announces is already
 * committed; a failure is logged and the clients' periodic reads catch up.
 *
 * <ul>
 * <li>Positions go to the cache and WebSocket subscribers like tracker uploads, and their devices show online with
 * a new last-update time, as model 1's effects did.</li>
 * <li>Invalidations keep model 1's WebSocket names ({@code {"invalidate": ["game-status"]}}). They reach the
 * accounts of the named members, or of every active member of the session, and always the operators
 * (administrators). Proximity circles concern hunters and the spectator only: a session-wide {@code game-proximity}
 * reaches those roles, and players and supporters learn about their own access through {@link #proximityAccess}.</li>
 * </ul>
 */
@Singleton
public class GameServerPublisher implements GamePublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameServerPublisher.class);

    private final GameTransactions transactions;
    private final CacheManager cacheManager;
    private final ConnectionManager connectionManager;
    private final GamePushOutbox outbox;

    @Inject
    public GameServerPublisher(GameTransactions transactions, CacheManager cacheManager,
                               ConnectionManager connectionManager, GamePushOutbox outbox) {
        this.transactions = transactions;
        this.cacheManager = cacheManager;
        this.connectionManager = connectionManager;
        this.outbox = outbox;
    }

    @Override
    public void positions(Collection<Long> devices, List<Position> latest) {
        try {
            Date now = new Date();
            for (long deviceId : devices) {
                connectionManager.updateDevice(deviceId, Device.STATUS_ONLINE, now);
            }
            for (Position position : latest) {
                cacheManager.updatePosition(position);
                connectionManager.updatePosition(true, position);
            }
        } catch (RuntimeException error) {
            LOGGER.warn("Game positions could not be published", error);
        }
    }

    /** The accounts of the members an invalidation names, or of every member of its session who should see it. */
    Set<Long> accounts(Invalidation invalidation) throws Exception {
        return transactions.read(transaction -> {
            Set<Long> result = new HashSet<>();
            if (invalidation.members() == null) {
                String roles = PROXIMITY.equals(invalidation.topic())
                        ? " AND role IN ('" + GameMember.HUNTER + "', '" + GameMember.SPECTATOR + "')" : "";
                result.addAll(transaction.list("SELECT userid FROM tc_game_session_members WHERE gamesessionid = ?"
                        + " AND state = 'active' AND userid IS NOT NULL" + roles, rows -> rows.getLong(1),
                        invalidation.gameSessionId()));
            } else {
                for (long memberId : invalidation.members()) {
                    transaction.first("SELECT userid FROM tc_game_session_members WHERE gamesessionid = ? AND id = ?"
                            + " AND state = 'active' AND userid IS NOT NULL", rows -> rows.getLong(1),
                            invalidation.gameSessionId(), memberId).ifPresent(result::add);
                }
            }
            return result;
        });
    }

    @Override
    public void invalidate(Invalidation invalidation) {
        try {
            connectionManager.updateGameUsers(true, accounts(invalidation), invalidation.topic());
        } catch (Exception error) {
            LOGGER.warn("Game invalidation {} could not be published", invalidation.topic(), error);
        }
    }

    @Override
    public void pushes(List<Long> outboxIds) {
        try {
            outbox.dispatch(outboxIds);
        } catch (RuntimeException error) {
            LOGGER.warn("Game pushes could not be dispatched", error);
        }
    }

    @Override
    public void proximityAccess(long gameSessionId, Set<Long> userIds) {
        try {
            connectionManager.updateGameAccess(true, userIds);
        } catch (RuntimeException error) {
            LOGGER.warn("Proximity access could not be refreshed", error);
        }
    }

    @Override
    public void accounts(Set<Long> userIds, String topic) {
        try {
            connectionManager.updateGameUsers(true, userIds, topic);
        } catch (RuntimeException error) {
            LOGGER.warn("Game invalidation {} could not be published", topic, error);
        }
    }

    @Override
    public void operators(String topic) {
        try {
            connectionManager.updateGameAdmin(true, topic);
        } catch (RuntimeException error) {
            LOGGER.warn("Game invalidation {} could not be published", topic, error);
        }
    }

}
