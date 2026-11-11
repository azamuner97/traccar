/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.database.GameRequestService;

/**
 * The signed-in member's view of the game: whether the controlling Tool runs it, server time, and the status
 * the Tool publishes, filtered by role. Players and supporters never learn whom a speedhunt targets.
 */
@Path("game-status")
@Produces(MediaType.APPLICATION_JSON)
public class GameStatusResource extends BaseResource {

    public static final String INVALIDATION = "game-status";
    public static final String SPEEDHUNT_HISTORY_INVALIDATION = "game-speedhunt-history";

    @Inject
    private GameRequestService requests;

    @GET
    public GameRequestService.Status status() throws Exception {
        return requests.status(requests.membership(getUserId()));
    }

    @GET
    @Path("speedhunt-history")
    public Response speedhuntHistory() throws Exception {
        return Response.ok(requests.speedhuntHistory(getUserId()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    @GET
    @Path("speedhunt")
    public Response speedhunt() throws Exception {
        long userId = getUserId();
        Long sessionId = permissionsService.notAdmin(userId) ? requests.membership(userId).gameSessionId() : null;
        return Response.ok(requests.speedhuntStatus(sessionId))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
