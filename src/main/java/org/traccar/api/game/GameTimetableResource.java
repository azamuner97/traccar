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
import org.traccar.game.GameMember;
import org.traccar.game.GameTimetableService;

import java.util.List;
import java.util.Map;

/**
 * Timetable pings a member may see, never with their cell content: players and supporters their own pings that can
 * carry a fake, spectators every ping that reaches hunters, hunters only the times of those pings.
 */
@Path("game-timetable")
@Produces(MediaType.APPLICATION_JSON)
public class GameTimetableResource extends GameApiResource {

    @Inject
    private GameTimetableService timetables;

    @GET
    public JsonNode timetable() throws Exception {
        GameMember member = caller();
        if (GameMember.HUNTER.equals(member.role())) {
            List<Map<String, String>> slots = timetables.hunterSlots(member.gameSessionId()).stream()
                    .map(time -> Map.of("scheduledAt", time.toString())).toList();
            return json(slots);
        }
        return json(timetables.pings(member));
    }

}
