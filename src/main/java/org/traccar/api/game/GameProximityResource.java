/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.api.game;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.traccar.game.GameMember;
import org.traccar.game.GameProximityService;
import org.traccar.model.Device;
import org.traccar.model.User;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Participant proximity of data model 2, keyed by game session. Operators publish and reset a session's grants and
 * circles; members read what concerns them in their own session (resolved from their membership): hunters and
 * spectators the circles without member identities, players and supporters their own temporary access. Proximity
 * has no generation; a reset publishes empty content with the next revision. {@code GameControllerFilter} fences
 * every write.
 */
@Path("game-proximity")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GameProximityResource extends GameApiResource {

    public record ReplaceRequest(Long expectedRevision, List<GameProximityService.GrantInput> grants,
                                 List<GameProximityService.CircleInput> circles) {
    }

    /** Temporary access of the caller to a target member's tracker, until {@code expiresAt}. */
    public record Access(long memberId, long deviceId, String expiresAt) {
    }

    @Inject
    private GameProximityService proximity;

    @GET
    @Path("session/{id}")
    public JsonNode get(@PathParam("id") long id) throws Exception {
        checkOperator();
        return json(proximity.snapshot(id));
    }

    /** Replaces the published content based on the revision it was loaded with; the server assigns the next. */
    @PUT
    @Path("session/{id}")
    public JsonNode replace(@PathParam("id") long id, ReplaceRequest request) throws Exception {
        checkOperator();
        required(request, "Proximity content required");
        return json(proximity.replace(id, required(request.expectedRevision(), "expectedRevision is required"),
                request.grants() == null ? List.of() : request.grants(),
                request.circles() == null ? List.of() : request.circles()));
    }

    @POST
    @Path("session/{id}/reset")
    public JsonNode reset(@PathParam("id") long id) throws Exception {
        checkOperator();
        return json(proximity.reset(id));
    }

    /** The circles of the caller's session, for its hunters and spectator. */
    @GET
    @Path("circles")
    public JsonNode circles() throws Exception {
        GameMember member = caller();
        if (!GameMember.HUNTER.equals(member.role()) && !GameMember.SPECTATOR.equals(member.role())) {
            throw new ForbiddenException("Proximity circles are restricted to session hunters and spectators");
        }
        return json(proximity.circles(member.gameSessionId()));
    }

    /**
     * The caller's own temporary access to other participants' trackers, for players and supporters. Trackers the
     * account sees permanently anyway are left out, as in model 1.
     */
    @GET
    @Path("access")
    public List<Access> access() throws Exception {
        GameMember member = caller();
        if (!GameMember.hasState(member.role())) {
            throw new ForbiddenException("Proximity access belongs to session participants");
        }
        Set<Long> permanent = new HashSet<>();
        storage.getObjects(Device.class, new Request(new Columns.Include("id"),
                new Condition.Permission(User.class, getUserId(), Device.class).excludeTemporary()))
                .forEach(device -> permanent.add(device.getId()));
        List<Access> result = new ArrayList<>();
        for (GameProximityService.Grant grant : proximity.snapshot(member.gameSessionId()).grants()) {
            if (grant.recipientMemberId() == member.id() && !permanent.contains(grant.deviceId())) {
                result.add(new Access(grant.targetMemberId(), grant.deviceId(), grant.expiresAt().toString()));
            }
        }
        return result;
    }

}
