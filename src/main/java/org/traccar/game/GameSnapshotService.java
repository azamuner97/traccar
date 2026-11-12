/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The operator snapshot (plan §3.3): everything the Tool needs to load and recover a session, read at one database
 * snapshot ({@link GameTransactions#snapshot}), so no part of it is newer than another and {@code revision} (the
 * session aggregate revision) describes all of it. Operator data: it names targets, ping sources, fake plans and
 * bound runs. Generated credentials are not part of it; they are read on their own, with the server's key.
 */
@Singleton
public class GameSnapshotService {

    public record Resources(List<GameResource.Mirror> mirrors, List<GameResource.Group> groups,
                            List<GameResource.Geofence> geofences) {
    }

    /**
     * The typed game state: every catch interval, every run with its pings (newest first), every break, every live
     * upload, the member state revisions and the per-member speedhunt statistics.
     */
    public record State(List<GameState.Catch> catches, List<GameStateService.RunHistory> runs,
                        List<GameState.Break> breaks, List<GameState.LiveUpload> liveUploads,
                        Map<Long, Long> memberRevisions, List<GameStateRepository.Statistics> statistics) {
    }

    /**
     * {@code executingEpoch} is the controller epoch while the lease executes this session, else null.
     * {@code requests} are the open requests; {@code fakePlans} the plans of reserved fake ping jokers (planned
     * breaks are break rows of {@code state}).
     */
    public record Snapshot(long gameSessionId, long revision, Instant readAt, Long executingEpoch,
                           GameSessionService.Session session, List<GameMember> members, Resources resources,
                           State state, List<GameRequest> requests, GameJokerService.Overview jokers,
                           List<GameJokerRepository.Plan> fakePlans, GameTimetableService.Timetable timetable,
                           GameDocumentService.Document geofencePlans,
                           List<GameDocumentService.Checkpoint> checkpoints,
                           GameNotificationService.State notifications, GameProximityService.Snapshot proximity) {
    }

    private final GameTransactions transactions;
    private final ObjectMapper objectMapper;

    @Inject
    public GameSnapshotService(GameTransactions transactions, ObjectMapper objectMapper) {
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    /** The snapshot of a session; 404 when it does not exist. */
    public Snapshot snapshot(long sessionId) throws Exception {
        return transactions.snapshot(transaction -> {
            GameSessionService.Session session = GameSessionService.read(transaction, objectMapper, sessionId);
            List<GameStateService.RunHistory> runs = new ArrayList<>();
            for (GameState.Run run : GameStateRepository.runs(transaction, sessionId, false)) {
                runs.add(new GameStateService.RunHistory(run, GameStateRepository.pings(transaction, sessionId,
                        run.id())));
            }
            State state = new State(GameStateRepository.catches(transaction, sessionId, false), runs,
                    GameStateRepository.breaks(transaction, sessionId),
                    GameStateRepository.uploads(transaction, sessionId),
                    GameStateService.revisions(transaction, sessionId),
                    GameStateRepository.statistics(transaction, sessionId));
            Resources resources = new Resources(GameResourceRepository.mirrors(transaction, sessionId),
                    GameResourceRepository.groups(transaction, sessionId),
                    GameResourceRepository.geofences(transaction, sessionId));
            return new Snapshot(sessionId, session.revision(), transaction.now(),
                    GameStateRepository.executingEpoch(transaction, sessionId), session,
                    GameMemberRepository.list(transaction, sessionId), resources, state,
                    GameRequestRepository.open(transaction, sessionId),
                    GameJokerService.overview(transaction, sessionId),
                    GameJokerRepository.plans(transaction, sessionId, "r.status = ?", GameJokerRepository.RESERVED),
                    GameTimetableService.read(transaction, objectMapper, sessionId),
                    GameDocumentService.geofencePlans(transaction, objectMapper, sessionId),
                    GameDocumentService.checkpoints(transaction, objectMapper, sessionId),
                    GameNotificationService.state(transaction, sessionId).orElse(null),
                    GameProximityService.read(transaction, sessionId, transaction.now()));
        });
    }

}
