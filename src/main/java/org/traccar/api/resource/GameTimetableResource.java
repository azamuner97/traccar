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
import org.traccar.database.GameJokerService;
import org.traccar.database.GameRequestService;

import java.util.List;

/**
 * The timetable pings the signed-in member may see, with their state. Players and supporters see their own pings
 * that can carry a fake; hunters and spectators see every ping that reaches hunters, without its cell content, so
 * operator coordinates and fakes look like real pings.
 */
@Path("game-timetable")
@Produces(MediaType.APPLICATION_JSON)
public class GameTimetableResource extends BaseResource {

    public static final String INVALIDATION = "game-timetable";

    @Inject
    private GameRequestService requests;

    @Inject
    private GameJokerService jokers;

    @GET
    public List<GameJokerService.TimetablePing> timetable() throws Exception {
        var member = requests.membership(getUserId());
        return jokers.timetable(member.gameSessionId(), member.role(), member.assignedDeviceId());
    }
}
