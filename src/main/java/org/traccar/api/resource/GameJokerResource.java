/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.traccar.api.BaseResource;
import org.traccar.database.GameJokerService;
import org.traccar.database.GameRequestService;
import org.traccar.session.ConnectionManager;

/**
 * The signed-in player's or supporter's own jokers, open reservations and armed fake plans, and the timetable pings
 * a fake can replace. Unused inventories and fake plans stay private to their owner and the operators.
 */
@Path("game-jokers")
@Produces(MediaType.APPLICATION_JSON)
public class GameJokerResource extends BaseResource {

    public static final String INVALIDATION = "game-jokers";

    @Inject
    private GameRequestService requests;

    @Inject
    private GameJokerService jokers;

    @Inject
    private ConnectionManager connectionManager;

    private GameRequestService.Membership owner() throws Exception {
        var member = requests.membership(getUserId());
        if (member.assignedDeviceId() == null
                || !"player".equals(member.role()) && !"supporter".equals(member.role())) {
            throw new ForbiddenException("Jokers belong to players and supporters");
        }
        return member;
    }

    /** A player's or supporter's own jokers, or for hunters the shared pool of speedhunt jokers. */
    @GET
    public GameJokerService.Own own() throws Exception {
        var hunter = requests.membership(getUserId());
        if ("hunter".equals(hunter.role())) {
            return jokers.pool(hunter.gameSessionId());
        }
        var member = owner();
        return jokers.own(member.gameSessionId(), member.assignedDeviceId());
    }

    /** The owner cancels an armed fake or planned break that has not been used. */
    @POST
    @Path("reservations/{id}/cancel")
    public GameJokerService.Reservation cancel(@PathParam("id") long id) throws Exception {
        var member = owner();
        var result = jokers.cancelFake(member.gameSessionId(), member.assignedDeviceId(), id, "Owner");
        connectionManager.updateGameUsers(true, java.util.Set.of(member.userId()), INVALIDATION);
        return result;
    }
}
