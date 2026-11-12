/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.api.game;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.game.GameStatusService;

/**
 * The signed-in member's view of the game, computed from the typed tables: whether a Tool controls it, the server
 * time, a hash of the projection, and the role projection ({@link org.traccar.game.GameStatus}). Players and
 * supporters never learn whom a speedhunt targets.
 */
@Path("game-status")
@Produces(MediaType.APPLICATION_JSON)
public class GameStatusResource extends GameApiResource {

    @Inject
    private GameStatusService status;

    @GET
    public JsonNode status() throws Exception {
        return json(status.status(getUserId()));
    }

    /** The session's speedhunts with their pings: the same anonymous projection for every member. */
    @GET
    @Path("speedhunt-history")
    public Response speedhuntHistory() throws Exception {
        return noStore(status.speedhuntHistory(getUserId()));
    }

    /** Anonymous speedhunt progress: of the member's session, or for administrators of the executing session. */
    @GET
    @Path("speedhunt")
    public Response speedhunt() throws Exception {
        Long sessionId = administrator() ? null : caller().gameSessionId();
        return noStore(status.speedhunt(sessionId));
    }

}
