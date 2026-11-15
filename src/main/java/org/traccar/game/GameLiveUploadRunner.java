/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the live uploads and relays of the running session (plan phase 2), in {@link TaskGameExecution}: every
 * active row of tc_game_live_uploads ticks every {@code intervalSeconds} until its {@code deadline}, whatever the
 * controller lease does.
 * <ul>
 * <li><b>Participant uploads</b> (live speedhunt ping, manual and geofence live tracking) send one engine intent per
 * tick, on the paths {@code speedhunt}, {@code live} and {@code geofenceLive}, with the row's audiences and the latest
 * position, without a freshness wait. The first delivered sample of a live speedhunt ping applies
 * {@code pingDelivered} with the row's ping fields in the same transaction; every other delivered sample records
 * itself ({@code lastSampleAt}).</li>
 * <li><b>Hunter relays</b> send one relay intent per tick to the recipient's per-hunter mirrors; the first tick that
 * delivers uses the row's "Get hunter positions" joker. Without a joker left (and no use beyond the allocation) the
 * relay ends ({@code noJoker}).</li>
 * <li><b>Ticks are never replayed:</b> a tick that comes late sends the current position once and the next one
 * follows an interval later. A tick that delivers nothing changes nothing but its outcome.</li>
 * <li><b>Ending:</b> an upload past its deadline ends ({@code deadline}). Stops, catches, the end of a speedhunt,
 * the game stopping and retirements end uploads where they happen ({@link GameOperations#endUpload}).</li>
 * </ul>
 * Each tick's outcome is stored on the row ({@code lastStatus}, {@code lastReason}, {@code lastDelivered}); a
 * {@code sample} or {@code waitingForLocation} event is written only when it changes or when the tick recorded a
 * ping or a joker ({@link GameLiveEvents}).
 */
@Singleton
public class GameLiveUploadRunner implements GameExecutionRunner<GameLiveUploadRunner.Work> {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameLiveUploadRunner.class);

    public enum Kind { END, TICK }

    /** One step: end an upload past its deadline, or tick it. */
    public record Work(Kind kind, long uploadId) {
    }

    private final GameTransactions transactions;
    private final GamePublisher publisher;
    private final GamePingEngine engine;
    /** Uploads whose tick is waiting for or inside its transaction. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    /** When each upload ticks next; an upload not listed ticks at once (a new one, or after a restart). */
    private final Map<Long, Instant> nextTicks = new ConcurrentHashMap<>();

    @Inject
    public GameLiveUploadRunner(GameTransactions transactions, GamePublisher publisher, GamePingEngine engine) {
        this.transactions = transactions;
        this.publisher = publisher;
        this.engine = engine;
    }

    /** When an upload ticks next, for tests. */
    Instant nextTick(long uploadId) {
        return nextTicks.get(uploadId);
    }

    @Override
    public List<Work> due(long sessionId, Instant now) throws Exception {
        return transactions.read(transaction -> {
            if (!GameExecutionService.running(transaction, sessionId)) {
                return List.of();
            }
            List<Work> work = new ArrayList<>();
            Set<Long> active = new HashSet<>();
            for (GameState.LiveUpload upload : GameStateRepository.activeUploads(transaction, sessionId)) {
                active.add(upload.id());
                if (upload.deadline() != null && !upload.deadline().isAfter(now)) {
                    work.add(new Work(Kind.END, upload.id()));
                } else if (!inFlight.contains(upload.id())) {
                    Instant next = nextTicks.get(upload.id());
                    if (next == null || !next.isAfter(now)) {
                        work.add(new Work(Kind.TICK, upload.id()));
                    }
                }
            }
            nextTicks.keySet().retainAll(active);
            return work;
        });
    }

    @Override
    public void run(long sessionId, Work work) throws Exception {
        switch (work.kind()) {
            case END -> expire(sessionId, work.uploadId());
            case TICK -> tick(sessionId, work.uploadId());
            default -> throw new IllegalStateException();
        }
    }

    // Ending ------------------------------------------------------------------------------------------------

    private void expire(long sessionId, long uploadId) throws Exception {
        end(sessionId, uploadId, GameLiveEvents.DEADLINE, null, true);
    }

    /** Ends an upload in its own transaction; {@code deadlineOnly} ends it only once its deadline passed. */
    private void end(long sessionId, long uploadId, String reason, String detail, boolean deadlineOnly)
            throws Exception {
        transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            var upload = GameStateRepository.upload(transaction, sessionId, uploadId).orElse(null);
            if (upload == null || !upload.active() || deadlineOnly && (upload.deadline() == null
                    || upload.deadline().isAfter(transaction.now()))) {
                return null;
            }
            transaction.lockMemberStates(Set.of(upload.memberId()));
            GameOperations ended = new GameOperations(transaction, sessionId, "Server", null, publisher);
            ended.endUpload(upload, reason, detail);
            transaction.bumpSession();
            GameStateService.publish(transaction, publisher, sessionId, ended);
            return null;
        });
        nextTicks.remove(uploadId);
    }

    // Ticking -----------------------------------------------------------------------------------------------

    private static String path(GameState.LiveUpload upload) {
        return switch (upload.purpose()) {
            case GameState.SPEEDHUNT_PING -> GamePingEngine.SPEEDHUNT;
            case GameState.GEOFENCE_AUTO -> GamePingEngine.GEOFENCE_LIVE;
            case GameState.HUNTER_RELAY -> GamePingEngine.RELAY;
            default -> GamePingEngine.LIVE;
        };
    }

    /** The intent of one tick: its delivered sample records itself, or the live ping's ping. */
    static GamePingEngine.Intent intent(GameState.LiveUpload upload) {
        GameOperation delivered;
        if (GameState.SPEEDHUNT_PING.equals(upload.purpose()) && upload.pingId() == null) {
            delivered = new GameOperation.PingDelivered(upload.memberId(),
                    upload.pingNumber() == null ? 1 : upload.pingNumber(), GameState.LIVE, GameState.REAL,
                    upload.toHunters(), upload.toPlayers(), upload.toSupporters(), upload.requestId(), upload.id(),
                    upload.startedBy(), upload.overAllocate());
        } else {
            boolean joker = upload.relay() && upload.useJoker() && upload.reservationId() == null;
            delivered = new GameOperation.LiveUploadSample(upload.id(), joker ? Boolean.TRUE : null,
                    joker ? upload.overAllocate() : null);
        }
        GamePingEngine.Effects effects = new GamePingEngine.Effects(List.of(delivered), null, null);
        return upload.relay()
                ? new GamePingEngine.Intent(GamePingEngine.RELAY, null, upload.memberId(), null, null, null, null,
                        effects, null)
                : new GamePingEngine.Intent(path(upload), upload.memberId(), null, upload.audiences(), null, null,
                        null, effects, null);
    }

    private void tick(long sessionId, long uploadId) throws Exception {
        if (!inFlight.add(uploadId)) {
            return;
        }
        GameState.LiveUpload upload;
        try {
            upload = transactions.read(transaction -> GameStateRepository.upload(transaction, sessionId, uploadId)
                    .filter(GameState.LiveUpload::active).orElse(null));
        } catch (Exception error) {
            inFlight.remove(uploadId);
            throw error;
        }
        if (upload == null) {
            inFlight.remove(uploadId);
            return;
        }
        // The next tick follows an interval after this one was due; a late tick is never replayed.
        Instant now = transactions.now();
        Instant due = nextTicks.getOrDefault(uploadId, now);
        Instant next = due.plusSeconds(upload.intervalSeconds());
        nextTicks.put(uploadId, next.isAfter(now) ? next : now.plusSeconds(upload.intervalSeconds()));
        engine.execute(sessionId, intent(upload), GamePingEngine.Context.NONE, hooks(sessionId, uploadId))
                .whenComplete((outcome, error) -> {
                    inFlight.remove(uploadId);
                    if (error != null) {
                        failed(sessionId, upload, error);
                    }
                });
    }

    /** A tick's outcome as compared with the previous tick's: status, reason and the audiences it reached. */
    private static String key(String status, String reason, List<String> delivered) {
        return status == null ? null : status + ":" + reason + ":" + String.join(",", delivered);
    }

    private GamePingEngine.Hooks hooks(long sessionId, long uploadId) {
        AtomicReference<GameState.LiveUpload> before = new AtomicReference<>();
        return new GamePingEngine.Hooks() {
            @Override
            public boolean proceed(GameTransaction transaction) throws Exception {
                var upload = GameStateRepository.upload(transaction, sessionId, uploadId).orElse(null);
                if (upload == null || !upload.active() || !GameExecutionService.running(transaction, sessionId)) {
                    return false;
                }
                before.set(upload);
                return true;
            }

            @Override
            public void completed(GameTransaction transaction, GamePingEngine.Outcome outcome) throws Exception {
                GameState.LiveUpload previous = before.get();
                GameState.LiveUpload upload = GameStateRepository.upload(transaction, sessionId, uploadId)
                        .orElseThrow();
                List<String> delivered = outcome.delivered() == null ? List.of() : outcome.delivered();
                GameStateRepository.tick(transaction, sessionId, uploadId, outcome.status(), outcome.reason(),
                        delivered);
                GameOperations.Result ping = outcome.operations() == null ? null : outcome.operations().stream()
                        .filter(result -> "pingDelivered".equals(result.operation())).findFirst().orElse(null);
                GameJokerRepository.Reservation joker = upload.reservationId() != null
                        && previous.reservationId() == null
                        ? GameJokerRepository.reservation(transaction, sessionId, upload.reservationId()).orElse(null)
                        : null;
                Integer limit = ping == null ? null : GameSettings.read(transaction, sessionId).pingsPerSpeedhunt();
                GameLiveEvents.Recorded recorded = new GameLiveEvents.Recorded(ping, limit, joker);
                String was = key(previous.lastStatus(), previous.lastReason(), previous.lastDelivered());
                String now = key(outcome.status(), outcome.reason(), delivered);
                boolean waiting = GamePingEngine.FAILED.equals(outcome.status())
                        && GamePingEngine.NO_POSITION.equals(outcome.reason());
                if (now.equals(was) && !recorded.any()) {
                    return;
                }
                GameState.LiveUpload current = GameStateRepository.upload(transaction, sessionId, uploadId)
                        .orElseThrow();
                if (waiting && !recorded.any()) {
                    GameLiveEvents.waiting(transaction, publisher, sessionId, current, outcome);
                } else {
                    boolean resumed = GamePingEngine.FAILED.equals(previous.lastStatus())
                            && GamePingEngine.NO_POSITION.equals(previous.lastReason()) && outcome.deliveredAny();
                    GameLiveEvents.sample(transaction, publisher, sessionId, current, outcome, recorded, resumed);
                }
            }
        };
    }

    /**
     * A tick that the game rules refused (a 409 or 400 in its transaction) ends the upload: a relay whose joker is
     * gone ({@code noJoker}), anything else {@code rejected} with the refusal. Other failures are retried by the
     * next tick.
     */
    private void failed(long sessionId, GameState.LiveUpload upload, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (!(cause instanceof WebApplicationException refusal)) {
            LOGGER.warn("Live upload {} tick failed", upload.id(), cause);
            return;
        }
        String message = refusal.getMessage();
        boolean joker = upload.relay() && upload.useJoker() && upload.reservationId() == null
                && message != null && message.contains("joker");
        LOGGER.info("Live upload {} ends: its tick was refused: {}", upload.id(), message);
        try {
            end(sessionId, upload.id(), joker ? GameLiveEvents.NO_JOKER : GameLiveEvents.REJECTED, message, false);
        } catch (Exception failure) {
            LOGGER.warn("Live upload {} could not be ended", upload.id(), failure);
        }
    }

}
