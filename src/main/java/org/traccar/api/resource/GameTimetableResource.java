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
 * The timetable pings the signed-in member may see. Hunters see distinct scheduled times. Spectators see each
 * ping that reaches hunters, and players and supporters see their own pings that can carry a fake.
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
    public List<?> timetable() throws Exception {
        var member = requests.membership(getUserId());
        if ("hunter".equals(member.role())) {
            return jokers.hunterTimetable(member.gameSessionId());
        }
        return jokers.timetable(member.gameSessionId(), member.role(), member.assignedDeviceId());
    }
}
