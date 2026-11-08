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
import jakarta.ws.rs.core.MediaType;
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

    @Inject
    private GameRequestService requests;

    @GET
    public GameRequestService.Status status() throws Exception {
        return requests.status(requests.membership(getUserId()));
    }
}
