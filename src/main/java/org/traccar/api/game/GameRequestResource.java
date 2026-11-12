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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.traccar.game.GameRequestService;

/**
 * Members' self-service game requests: players and supporters use their jokers, hunters control speedhunts with the
 * target's member state revision. Hunters and spectators see every speedhunt request (with roster labels for
 * hunters); everyone else sees only their own.
 */
@Path("game-requests")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GameRequestResource extends GameApiResource {

    @Inject
    private GameRequestService requests;

    @GET
    public JsonNode list(@QueryParam("limit") int limit) throws Exception {
        return json(requests.list(caller(), limit));
    }

    @POST
    public JsonNode submit(GameRequestService.Submission submission) throws Exception {
        return json(requests.submit(caller(), submission));
    }

    /** The requester cancels a request that waits for approval or execution; its joker returns. */
    @POST
    @Path("{id}/cancel")
    public JsonNode cancel(@PathParam("id") long id) throws Exception {
        return json(requests.cancel(caller(), id));
    }

}
