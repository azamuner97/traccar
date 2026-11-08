/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.traccar.api.BaseResource;
import org.traccar.database.GameRequestService;
import org.traccar.session.ConnectionManager;

import java.util.List;

/**
 * Self-service game requests of the signed-in member. Membership authorizes them: hunters control speedhunts
 * on players and supporters of their own session. The controlling Tool decides and executes them.
 */
@Path("game-requests")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GameRequestResource extends BaseResource {

    public static final String INVALIDATION = "game-requests";

    @Inject
    private GameRequestService requests;

    @Inject
    private ConnectionManager connectionManager;

    @GET
    public List<GameRequestService.Request> list(@QueryParam("limit") int limit) throws Exception {
        return requests.list(requests.membership(getUserId()), limit);
    }

    @POST
    public GameRequestService.Request submit(GameRequestService.Submission submission) throws Exception {
        var member = requests.membership(getUserId());
        var result = requests.submit(member, submission);
        connectionManager.updateGameMembers(true, member.gameSessionId(), INVALIDATION);
        return result;
    }

    @POST
    @Path("{id}/cancel")
    public GameRequestService.Request cancel(@PathParam("id") long id) throws Exception {
        var member = requests.membership(getUserId());
        var result = requests.cancel(member, id);
        connectionManager.updateGameMembers(true, member.gameSessionId(), INVALIDATION);
        return result;
    }
}
