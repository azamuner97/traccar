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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fenced, idempotent effects deliveries of data model 2 (plan §3.1–3.2). One delivery is one game operation of the
 * controlling Tool: the typed state operations, ping intents (decided by {@link GamePingEngine}, one outcome each),
 * request outcomes, operator joker uses, archived notifications, queued pushes, logs and checkpoints, committed in
 * one transaction under a durable delivery ID.
 *
 * <p>A delivery carries no positions (game execution plan, phase 4): every mirror position is written by the
 * server's ping engine from an intent. The one-off pings are intents of a delivery: a manual ping (path
 * {@code manual}), a speedhunt static ping (path {@code speedhunt} with {@code pingDelivered} in
 * {@code onDelivered} and {@code pingFailed} in {@code onNotDelivered}), an operator fake (the same with a
 * {@code fake} for the run's audiences and source {@code operatorFake}), a request's ping (its completion in
 * {@code onDelivered} and {@code onNotDelivered}, so it commits with the ping) and a one-off hunter relay (path
 * {@code relay}, its joker use in {@code onDelivered}), which leaves a {@code tc_game_relays} row when it delivers.
 *
 * <p>A delivery with ping intents, {@code pingDelivered} or {@code liveUploadStarted} answers 409
 * ({@value GameExecutionService#NOT_RUNNING}) unless the session's game runs; a recorded delivery still replays.
 *
 * <p>The order is the effects transaction's: validate, lock the session and then the touched member states in
 * ascending order, apply, advance the member and session revisions, commit, and only then publish positions,
 * invalidations and pushes through the {@link GamePublisher}. The transaction makes no network calls. Repeating a
 * delivery ID with the same content returns the recorded outcome, with the current push states, and applies
 * nothing; the same ID with other content answers 409.
 *
 * <p>Privacy, as in model 1: fake and real positions use one payload, the target's own speedhunt fake is applied
 * by the engine so hunter-visible data never marks it, joker invalidations reach only the owners concerned (and the
 * hunters for their pool), and nothing names a speedhunt target to players and supporters.
 */
@Singleton
public class GameEffectsService {

    public static final int MAX_OPERATIONS = 200;
    public static final int MAX_ALERTS = 100;
    public static final int MAX_PUSHES = 500;

    public record NotificationInput(String generation, List<GameNotificationService.Alert> alerts) {
    }

    /** A push to a member; one with {@code alertId} is queued only when that alert is new with this delivery. */
    public record PushInput(Long memberId, String subject, String message, String notificator, String alertId) {
    }

    /**
     * One delivery. {@code pings} are ping intents ({@link GamePingEngine}): each is decided by the server's rules
     * in this transaction, after the operations, and answers one outcome; it applies its {@code onDelivered} or
     * {@code onNotDelivered} effects. Intents of a delivery never wait for a fresh fix.
     */
    public record Delivery(String deliveryId, List<GameOperation> operations,
                           List<GamePingEngine.Intent> pings, NotificationInput notifications,
                           List<PushInput> pushes, List<GameLogService.LogInput> logs,
                           List<GameRequestService.Completion> requests, List<GameJokerService.Use> jokers) {
    }

    public record AlertOutcome(String alertId, boolean created) {
    }

    public record PushOutcome(Long id, Long memberId, String status, String detail) {
    }

    public record RequestOutcome(long id, String status) {
    }

    /** The recorded outcome of a delivery; operator data. The mirror positions written are in {@code pings}. */
    public record Outcome(String deliveryId, boolean replayed, long epoch,
                          List<GameOperations.Result> operations, List<GamePingEngine.Outcome> pings,
                          String generation, List<AlertOutcome> alerts,
                          List<PushOutcome> pushes, List<RequestOutcome> requests,
                          List<GameJokerService.UseOutcome> jokers, List<GameOperations.FakeOutcome> fakes,
                          long sessionRevision) {
    }

    private final GameTransactions transactions;
    private final ObjectMapper objectMapper;
    private final GamePublisher publisher;
    private final GamePingEngine engine;

    @Inject
    public GameEffectsService(GameTransactions transactions, ObjectMapper objectMapper, GamePublisher publisher,
                              GamePingEngine engine) {
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.publisher = publisher;
        this.engine = engine;
    }

    private static <T> List<T> list(List<T> value) {
        return value == null ? List.of() : value;
    }

    private static boolean hasNull(List<?> value) {
        return value != null && value.stream().anyMatch(java.util.Objects::isNull);
    }

    /** Checks the shape of a delivery before any transaction. */
    static void validate(Delivery delivery) {
        if (delivery == null || delivery.deliveryId() == null
                || !delivery.deliveryId().matches("[A-Za-z0-9_-]{8,64}")) {
            throw GameErrors.invalid("Invalid delivery ID");
        }
        if (list(delivery.operations()).size() > MAX_OPERATIONS
                || list(delivery.pushes()).size() > MAX_PUSHES || list(delivery.requests()).size() > MAX_OPERATIONS
                || list(delivery.jokers()).size() > MAX_OPERATIONS) {
            throw GameErrors.invalid("Delivery is too large");
        }
        if (hasNull(delivery.operations()) || hasNull(delivery.pushes())
                || hasNull(delivery.jokers()) || hasNull(delivery.requests()) || hasNull(delivery.pings())) {
            throw GameErrors.invalid("Invalid delivery item");
        }
        GameOperations.checkSent(list(delivery.operations()));
        GamePingEngine.validateAll(delivery.pings());
        for (GamePingEngine.Intent intent : list(delivery.pings())) {
            for (GamePingEngine.Effects effects : new GamePingEngine.Effects[] {intent.onDelivered(),
                    intent.onNotDelivered()}) {
                if (effects != null) {
                    GameOperations.checkSent(effects.operationList());
                }
            }
        }
        if (delivery.notifications() != null) {
            var input = delivery.notifications();
            GameNotificationService.generation(input.generation());
            if (input.alerts() == null || input.alerts().isEmpty() || input.alerts().size() > MAX_ALERTS) {
                throw GameErrors.invalid("Invalid delivery notifications");
            }
        }
        for (PushInput push : list(delivery.pushes())) {
            if (push.memberId() == null || push.message() == null || push.message().isBlank()
                    || push.message().length() > 8192 || push.subject() != null && push.subject().length() > 512
                    || push.notificator() != null && !push.notificator().matches("[A-Za-z0-9_]{1,64}")
                    || push.alertId() != null && !push.alertId().matches("[A-Za-z0-9_-]{1,64}")) {
                throw GameErrors.invalid("Invalid delivery push");
            }
        }
    }

    private String hash(Delivery delivery) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                objectMapper.writeValueAsString(delivery).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    /** Applies a delivery that the controller fence admitted under {@code epoch} (recorded with the delivery). */
    public Outcome apply(long sessionId, long epoch, Delivery delivery) throws Exception {
        validate(delivery);
        String hash = hash(delivery);
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            var stored = transaction.first("SELECT id, gamesessionid, requesthash, outcome FROM tc_game_deliveries"
                    + " WHERE deliveryid = ?", rows -> new Object[] {rows.getLong(1), rows.getLong(2),
                            rows.getString(3), rows.getString(4)}, delivery.deliveryId());
            if (stored.isPresent()) {
                Object[] row = stored.get();
                if ((long) row[1] != sessionId || !hash.equals(row[2])) {
                    throw GameErrors.conflict("Delivery ID was already used for different effects");
                }
                return replay(transaction, (long) row[0], (String) row[3]);
            }
            if (!list(delivery.pings()).isEmpty()) {
                // A ping intent writes mirror positions: never on a stopped game (its operations check the same).
                GameExecutionService.requireRunning(transaction, sessionId);
            }
            long rowId = transaction.insert("INSERT INTO tc_game_deliveries (gamesessionid, deliveryid, epoch,"
                    + " requesthash, outcome, createdat) VALUES (?, ?, ?, ?, ?, ?)", sessionId, delivery.deliveryId(),
                    epoch, hash, "{}", transaction.now());
            Outcome outcome = apply(transaction, sessionId, epoch, delivery, rowId);
            transaction.update("UPDATE tc_game_deliveries SET outcome = ? WHERE id = ?",
                    objectMapper.writeValueAsString(outcome), rowId);
            return outcome;
        });
    }

    /**
     * The recorded outcome of a delivery, as a replay with the current push states, whatever epoch recorded it;
     * 404 when the session has no delivery with this ID. Read-only.
     */
    public Outcome lookup(long sessionId, String deliveryId) throws Exception {
        if (deliveryId == null || !deliveryId.matches("[A-Za-z0-9_-]{8,64}")) {
            throw GameErrors.invalid("Invalid delivery ID");
        }
        return transactions.read(transaction -> {
            GameDocumentService.sessionExists(transaction, sessionId);
            var stored = transaction.first("SELECT id, outcome FROM tc_game_deliveries WHERE gamesessionid = ?"
                    + " AND deliveryid = ?", rows -> new Object[] {rows.getLong(1), rows.getString(2)},
                    sessionId, deliveryId);
            if (stored.isEmpty()) {
                throw GameErrors.notFound("Delivery not recorded");
            }
            return replay(transaction, (long) stored.get()[0], (String) stored.get()[1]);
        });
    }

    private Outcome replay(GameTransaction transaction, long rowId, String json) throws Exception {
        Outcome outcome = objectMapper.readValue(json, Outcome.class);
        Map<Long, PushOutcome> current = new LinkedHashMap<>();
        transaction.list("SELECT id, recipientmemberid, status, detail FROM tc_game_push_outbox"
                + " WHERE deliveryrowid = ?", rows -> current.put(rows.getLong(1), new PushOutcome(rows.getLong(1),
                        GameTransaction.longOrNull(rows, "recipientmemberid"), rows.getString(3),
                        rows.getString(4))), rowId);
        List<PushOutcome> pushes = new ArrayList<>();
        for (PushOutcome push : outcome.pushes()) {
            pushes.add(push.id() != null && current.containsKey(push.id()) ? current.get(push.id()) : push);
        }
        return new Outcome(outcome.deliveryId(), true, outcome.epoch(), outcome.operations(), outcome.pings(),
                outcome.generation(), outcome.alerts(), pushes, outcome.requests(), outcome.jokers(), outcome.fakes(),
                outcome.sessionRevision());
    }

    private Outcome apply(GameTransaction transaction, long sessionId, long epoch, Delivery delivery, long rowId)
            throws Exception {
        List<GameOperation> operations = list(delivery.operations());
        List<GamePingEngine.Intent> intents = list(delivery.pings());
        Set<Long> locked = new java.util.TreeSet<>(GameOperations.members(transaction, sessionId, operations));
        locked.addAll(GamePingEngine.members(transaction, sessionId, intents));
        transaction.lockMemberStates(locked);
        GameOperations applied = new GameOperations(transaction, sessionId, "Tool", objectMapper, publisher)
                .fencedBy(epoch);

        List<RequestOutcome> requestOutcomes = new ArrayList<>();
        for (GameRequest request : GameRequestService.complete(transaction, sessionId, list(delivery.requests()),
                applied)) {
            requestOutcomes.add(new RequestOutcome(request.id(), request.status()));
        }
        for (int i = 0; i < operations.size(); i++) {
            applied.apply(i, operations.get(i));
        }
        for (long request : applied.succeededBreaks()) {
            if (!applied.immediateBreaks().contains(request)) {
                throw GameErrors.invalid("Break request " + request + " starts its break with its outcome");
            }
        }
        List<GameJokerService.UseOutcome> uses = new ArrayList<>();
        for (GameJokerService.Use use : list(delivery.jokers())) {
            GameJokerService.UseOutcome used = GameJokerService.use(transaction, sessionId, use, "Tool");
            uses.add(used);
            applied.jokerMembers().add(used.memberId());
        }

        GamePositions.Collector collector = new GamePositions.Collector();
        List<GamePingEngine.Outcome> pings = new ArrayList<>();
        for (GamePingEngine.Intent intent : intents) {
            GamePingEngine.Outcome ping = engine.apply(transaction, sessionId, intent, GamePingEngine.Context.NONE,
                    null, applied, collector);
            if (GamePingEngine.RELAY.equals(intent.path()) && ping.deliveredAny()) {
                recordRelay(transaction, sessionId, ping);
            }
            pings.add(ping);
        }

        String generation = null;
        List<AlertOutcome> alerts = new ArrayList<>();
        Set<String> created = new LinkedHashSet<>();
        boolean sessionAlert = false;
        Set<Long> recipients = new LinkedHashSet<>();
        if (delivery.notifications() != null) {
            generation = delivery.notifications().generation();
            for (GameNotificationService.Alert alert : delivery.notifications().alerts()) {
                var creation = GameNotificationService.create(transaction, sessionId, generation, alert);
                alerts.add(new AlertOutcome(alert.id(), creation.created()));
                if (creation.created()) {
                    created.add(alert.id());
                    if (alert.recipientMemberId() == null) {
                        sessionAlert = true;
                    } else {
                        recipients.add(alert.recipientMemberId());
                    }
                }
            }
        }

        List<PushOutcome> pushes = new ArrayList<>();
        List<Long> queued = new ArrayList<>();
        for (PushInput push : list(delivery.pushes())) {
            if (push.alertId() != null && !created.contains(push.alertId())) {
                pushes.add(new PushOutcome(null, push.memberId(), GamePushOutbox.SKIPPED,
                        "The alert was archived before; its push is not sent again"));
                continue;
            }
            Optional<GameMember> member = GameMemberRepository.find(transaction, sessionId, push.memberId());
            if (member.isEmpty() || !member.get().active() || member.get().userId() == null) {
                pushes.add(new PushOutcome(null, push.memberId(), GamePushOutbox.REJECTED,
                        "The recipient is not a member of this session with an account"));
                continue;
            }
            long id = transaction.insert("INSERT INTO tc_game_push_outbox (deliveryrowid, gamesessionid,"
                    + " recipientmemberid, userid, recipientlabel, notificator, subject, message, status, createdat)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rowId, sessionId, member.get().id(),
                    member.get().userId(), member.get().displayName(),
                    push.notificator() == null ? "traccar" : push.notificator(),
                    push.subject() == null ? "" : push.subject(), push.message(), GamePushOutbox.QUEUED,
                    transaction.now());
            queued.add(id);
            pushes.add(new PushOutcome(id, member.get().id(), GamePushOutbox.QUEUED, null));
        }

        if (delivery.logs() != null && !delivery.logs().isEmpty()) {
            GameLogService.append(transaction, sessionId, delivery.logs());
        }

        long revision = transaction.bumpSession();
        GameStateService.publish(transaction, publisher, sessionId, applied);
        collector.publish(transaction, publisher);
        if (sessionAlert || !recipients.isEmpty()) {
            Set<Long> members = sessionAlert ? null : Set.copyOf(recipients);
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(sessionId, GamePublisher.NOTIFICATIONS, members)));
        }
        if (!queued.isEmpty()) {
            transaction.afterCommit(() -> publisher.pushes(queued));
        }
        return new Outcome(delivery.deliveryId(), false, epoch, applied.results(), pings, generation, alerts,
                pushes, requestOutcomes, uses, applied.fakes(), revision);
    }

    /**
     * A relay intent of a delivery is a one-off hunter relay: one that delivered leaves a {@code tc_game_relays} row
     * with the request it fulfilled or the joker reservation it used, so the recipient's last relay delivery is on
     * the server whether a live relay or a one-off sent it.
     */
    private static void recordRelay(GameTransaction transaction, long sessionId, GamePingEngine.Outcome ping)
            throws SQLException {
        Long request = list(ping.requests()).stream().map(RequestOutcome::id).findFirst().orElse(null);
        Long reservation = list(ping.jokers()).stream().map(GameJokerService.UseOutcome::reservationId).findFirst()
                .orElse(null);
        if (reservation == null && request != null) {
            reservation = GameJokerRepository.ofRequest(transaction, sessionId, request).stream()
                    .map(GameJokerRepository.Reservation::id).findFirst().orElse(null);
        }
        GameStateRepository.insertRelay(transaction, sessionId, ping.recipientMemberId(), ping.hunters().size(),
                request, reservation);
    }

}
