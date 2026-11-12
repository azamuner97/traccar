/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.api.game;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.traccar.game.GameJokerService;
import org.traccar.game.GameMember;

/**
 * A player's or supporter's own jokers, or for hunters the shared pool of speedhunt jokers. Unused inventories,
 * fake plans and planned breaks stay private to their owner; spectators see no jokers.
 */
@Path("game-jokers")
@Produces(MediaType.APPLICATION_JSON)
public class GameJokerResource extends GameApiResource {

    @Inject
    private GameJokerService jokers;

    private GameMember owner() throws Exception {
        GameMember member = caller();
        if (!GameMember.hasState(member.role())) {
            throw new ForbiddenException("Jokers belong to players and supporters");
        }
        return member;
    }

    @GET
    public JsonNode own() throws Exception {
        GameMember member = caller();
        if (GameMember.HUNTER.equals(member.role())) {
            return json(jokers.pool(member.gameSessionId()));
        }
        return json(jokers.own(owner()));
    }

    /**
     * The owner cancels an armed fake or planned break that has not taken effect. Another owner's reservation
     * answers exactly like a missing one (404).
     */
    @POST
    @Path("reservations/{id}/cancel")
    public JsonNode cancel(@PathParam("id") long id) throws Exception {
        return json(jokers.cancel(owner(), id));
    }

}
