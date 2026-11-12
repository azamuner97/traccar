/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.api.game;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.traccar.game.GameMember;
import org.traccar.game.GameNotificationService;

/**
 * The game notification journal of data model 2, keyed by game session instead of model 1's session device.
 * Members read the current generation of their own session (resolved from their membership), filtered by their
 * role and member; operators read and archive any session's journal and reset it. {@code GameControllerFilter}
 * fences every write.
 */
@Path("game-notifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GameNotificationResource extends GameApiResource {

    public record CreateRequest(Long gameSessionId, String generation, GameNotificationService.Alert alert) {
    }

    public record ResetRequest(String expectedGeneration, String replacementGeneration) {
    }

    @Inject
    private GameNotificationService notifications;

    /**
     * One page of the current generation. An administrator naming {@code gameSessionId} reads everything of that
     * session; anyone else reads their own session's alerts as its member.
     */
    @GET
    public JsonNode list(@QueryParam("gameSessionId") Long gameSessionId, @QueryParam("generation") String generation,
                         @QueryParam("after") String after, @QueryParam("limit") int limit) throws Exception {
        if (gameSessionId != null && administrator()) {
            return json(notifications.list(gameSessionId, generation, after, limit, null));
        }
        GameMember member = caller();
        if (gameSessionId != null && gameSessionId != member.gameSessionId()) {
            throw new ForbiddenException("Notification session access denied");
        }
        return json(notifications.list(member.gameSessionId(), generation, after, limit, member));
    }

    /** Operator history of a session across generations. */
    @GET
    @Path("history")
    public JsonNode history(@QueryParam("gameSessionId") long gameSessionId, @QueryParam("after") String after,
                            @QueryParam("limit") int limit) throws Exception {
        checkOperator();
        return json(notifications.history(gameSessionId, after, limit));
    }

    @POST
    public JsonNode create(CreateRequest request) throws Exception {
        checkOperator();
        if (request == null || request.gameSessionId() == null) {
            throw new BadRequestException("gameSessionId and alert are required");
        }
        return json(notifications.create(request.gameSessionId(), request.generation(), request.alert()));
    }

    /** The stream state of a session (null before its first generation). */
    @GET
    @Path("session/{id}")
    public JsonNode state(@PathParam("id") long id) throws Exception {
        checkOperator();
        return json(notifications.state(id));
    }

    /** Starts the session's first generation; an initialized session keeps its own. */
    @POST
    @Path("session/{id}")
    public JsonNode initialize(@PathParam("id") long id) throws Exception {
        checkOperator();
        return json(notifications.initialize(id));
    }

    /** Starts a new generation and deletes the session's notification history. */
    @POST
    @Path("session/{id}/reset")
    public JsonNode reset(@PathParam("id") long id, ResetRequest request) throws Exception {
        checkOperator();
        required(request, "Reset required");
        return json(notifications.reset(id, request.expectedGeneration(), request.replacementGeneration()));
    }

}
