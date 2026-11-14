/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * An execution event (plan B5): a session log row with {@code data}, written by a server-initiated execution step
 * and pushed after commit to administrators only, as {@code {"gameEvents": [...]}}. Members never receive it,
 * because it can carry fake and operator coordinates. The Tool catches up from the log by {@code id}.
 */
public record GameEvent(long id, long gameSessionId, String category, String occurredAt, String message,
                        JsonNode data) {

    /** The game state changed, or a test event (phase 0). */
    public static final String GAME = "game";
    public static final String TIMETABLE = "timetable";
    public static final String LIVE = "live";
    public static final String RELAY = "relay";
    public static final String BREAK = "break";

    public static final Set<String> CATEGORIES = Set.of(GAME, TIMETABLE, LIVE, RELAY, BREAK);

    /** The largest serialized {@code data}, in UTF-8 bytes. */
    public static final int MAX_DATA = 4096;

}
