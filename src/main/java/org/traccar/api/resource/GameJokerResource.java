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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.traccar.api.BaseResource;
import org.traccar.database.GameJokerService;
import org.traccar.database.GameRequestService;

/**
 * The signed-in player's or supporter's own jokers and open reservations. Unused inventories stay private to
 * their owner and the operators.
 */
@Path("game-jokers")
@Produces(MediaType.APPLICATION_JSON)
public class GameJokerResource extends BaseResource {

    public static final String INVALIDATION = "game-jokers";

    @Inject
    private GameRequestService requests;

    @Inject
    private GameJokerService jokers;

    @GET
    public GameJokerService.Overview own() throws Exception {
        var member = requests.membership(getUserId());
        if (member.assignedDeviceId() == null
                || !"player".equals(member.role()) && !"supporter".equals(member.role())) {
            throw new ForbiddenException("Jokers belong to players and supporters");
        }
        return jokers.own(member.gameSessionId(), member.assignedDeviceId());
    }
}
