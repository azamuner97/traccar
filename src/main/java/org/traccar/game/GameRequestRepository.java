/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Requests (tc_game_requests) and the joker settlement that ends them. */
public final class GameRequestRepository {

    private static final ObjectMapper READER = new ObjectMapper();

    private static final String SELECT = "SELECT id, gamesessionid, type, requestermemberid, requesterlabel,"
            + " requesterrole, targetmemberid, targetlabel, baserevision, parameters, status, approvalrequired,"
            + " message, detail, epoch, createdat, expiresat, decidedat, completedat, updatedat FROM tc_game_requests";

    /** What a settlement changed: the members whose jokers changed, and whether the hunters' pool did. */
    public record Settled(Set<Long> members, boolean pool) {
        public static Settled none() {
            return new Settled(new LinkedHashSet<>(), false);
        }

        void add(Settled other) {
            members.addAll(other.members());
        }
    }

    private GameRequestRepository() {
    }

    private static GameRequest request(ResultSet rows) throws SQLException {
        JsonNode parameters;
        try {
            parameters = READER.readTree(rows.getString("parameters"));
        } catch (Exception error) {
            parameters = JsonNodeFactory.instance.objectNode();
        }
        boolean approval = rows.getBoolean("approvalrequired");
        Boolean approvalRequired = rows.wasNull() ? null : approval;
        return new GameRequest(rows.getLong("id"), rows.getLong("gamesessionid"), rows.getString("type"),
                GameTransaction.longOrNull(rows, "requestermemberid"), rows.getString("requesterlabel"),
                rows.getString("requesterrole"), GameTransaction.longOrNull(rows, "targetmemberid"),
                rows.getString("targetlabel"), GameTransaction.longOrNull(rows, "baserevision"), parameters,
                rows.getString("status"), approvalRequired, rows.getString("message"), rows.getString("detail"),
                GameTransaction.longOrNull(rows, "epoch"), GameTime.get(rows, "createdat"),
                GameTime.get(rows, "expiresat"), GameTime.get(rows, "decidedat"), GameTime.get(rows, "completedat"),
                GameTime.get(rows, "updatedat"));
    }

    public static Optional<GameRequest> find(GameTransaction transaction, long sessionId, long id)
            throws SQLException {
        return transaction.first(SELECT + " WHERE gamesessionid = ? AND id = ?", GameRequestRepository::request,
                sessionId, id);
    }

    public static long insert(GameTransaction transaction, long sessionId, String type, GameMember requester,
                              GameMember target, Long baseRevision, String parameters, boolean approvalRequired,
                              Instant expiresAt) throws SQLException {
        Instant now = transaction.now();
        return transaction.insert("INSERT INTO tc_game_requests (gamesessionid, type, requestermemberid,"
                + " requesterlabel, requesterrole, targetmemberid, targetlabel, baserevision, parameters, status,"
                + " approvalrequired, createdat, expiresat, updatedat) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?)", sessionId, type, requester.id(), requester.displayName(), requester.role(), target.id(),
                target.displayName(), baseRevision, parameters, GameRequest.PENDING, approvalRequired, now,
                expiresAt, now);
    }

    /** Requests of a session, newest first, filtered by an SQL condition. */
    public static List<GameRequest> list(GameTransaction transaction, long sessionId, String condition, int limit,
                                         Object... params) throws SQLException {
        Object[] all = new Object[params.length + 1];
        all[0] = sessionId;
        System.arraycopy(params, 0, all, 1, params.length);
        return transaction.list(SELECT + " WHERE gamesessionid = ?" + (condition == null ? "" : " AND " + condition)
                + " ORDER BY id DESC", limit, GameRequestRepository::request, all);
    }

    public static List<GameRequest> open(GameTransaction transaction, long sessionId) throws SQLException {
        return transaction.list(SELECT + " WHERE gamesessionid = ? AND status IN (?, ?, ?) ORDER BY id",
                GameRequestRepository::request, sessionId, GameRequest.PENDING, GameRequest.APPROVED,
                GameRequest.EXECUTING);
    }

    /** Sets a status and its texts; a terminal status completes the request and settles its joker. */
    public static Settled finish(GameTransaction transaction, GameRequest request, String status, String message,
                                 String detail, boolean decided) throws SQLException {
        boolean terminal = !GameRequest.OPEN.contains(status);
        Instant now = transaction.now();
        List<Object> params = new ArrayList<>(List.of(status));
        params.add(GameJokerRepository.clip(message, 512));
        params.add(GameJokerRepository.clip(detail, 512));
        params.add(now);
        if (decided) {
            params.add(now);
        }
        if (terminal) {
            params.add(now);
        }
        params.add(request.gameSessionId());
        params.add(request.id());
        transaction.update("UPDATE tc_game_requests SET status = ?, message = ?, detail = ?, updatedat = ?"
                + (decided ? ", decidedat = ?" : "") + (terminal ? ", completedat = ?" : "")
                + " WHERE gamesessionid = ? AND id = ?", params.toArray());
        return terminal ? settle(transaction, request, status) : Settled.none();
    }

    /**
     * Settles the reservation of a request that reached an end. A succeeded request uses its joker, except that it
     * arms a fake ping, a planned break (whose break row is planned here) and a joker of the hunters' pool, which
     * take effect later. Any other end returns the joker.
     */
    static Settled settle(GameTransaction transaction, GameRequest request, String status) throws SQLException {
        Settled result = Settled.none();
        boolean pool = false;
        for (var reservation : GameJokerRepository.ofRequest(transaction, request.gameSessionId(), request.id())) {
            if (!reservation.reserved()) {
                continue;
            }
            if (reservation.memberId() == null) {
                pool = true;
            } else {
                result.members().add(reservation.memberId());
            }
            if (!GameRequest.SUCCEEDED.equals(status)) {
                GameJokerRepository.release(transaction, reservation, null, "Request " + status);
            } else if (GameRequest.FAKE_PING.equals(request.type()) || GameJokerRepository.poolType(reservation.type())
                    || request.plannedBreak()) {
                GameJokerRepository.arm(transaction, reservation, null);
                if (request.plannedBreak()) {
                    GameStateRepository.insertPlannedBreak(transaction, request.gameSessionId(),
                            request.targetMemberId(), GameTime.parse(request.parameters().get("startsAt").asText(),
                                    "break start"), GameState.BY_PARTICIPANT, request.id(), reservation.id());
                }
            } else {
                GameJokerRepository.consume(transaction, reservation, null, null);
            }
        }
        return new Settled(result.members(), pool);
    }

    /** Cancels the open requests a member made, with their jokers, when the member is out of the game. */
    public static Settled cancelOpen(GameTransaction transaction, long sessionId, long memberId, String status,
                                     String message) throws SQLException {
        Settled result = Settled.none();
        boolean pool = false;
        for (GameRequest request : open(transaction, sessionId)) {
            if (request.requesterMemberId() != null && request.requesterMemberId() == memberId) {
                Settled settled = finish(transaction, request, status, message, null, false);
                result.add(settled);
                pool |= settled.pool();
            }
        }
        return new Settled(result.members(), pool);
    }

}
