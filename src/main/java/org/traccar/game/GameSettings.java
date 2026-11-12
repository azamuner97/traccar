/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.sql.SQLException;

/**
 * The game rules the server reads from a session's settings document ({@code tc_game_session_settings}): the
 * Tool's {@code game_settings} object, with the defaults model 1 used.
 */
public final class GameSettings {

    /** Pings per speedhunt when the settings do not say (the Tool's default). */
    public static final int DEFAULT_PINGS_PER_SPEEDHUNT = 4;
    /** The highest ping number a fake may name when speedhunts have no ping limit. */
    public static final int MAX_PING_NUMBER = 1000;

    private static final ObjectMapper READER = new ObjectMapper();

    private final JsonNode game;

    private GameSettings(JsonNode game) {
        this.game = game;
    }

    /** The settings of a session; missing or unreadable settings behave like an empty document. */
    public static GameSettings read(GameTransaction transaction, long sessionId) throws SQLException {
        String settings = transaction.first("SELECT settings FROM tc_game_session_settings WHERE gamesessionid = ?",
                rows -> rows.getString(1), sessionId).orElse(null);
        JsonNode game;
        try {
            game = settings == null ? null : READER.readTree(settings).path("game_settings");
        } catch (Exception error) {
            game = null;
        }
        return new GameSettings(game == null || !game.isObject() ? JsonNodeFactory.instance.objectNode() : game);
    }

    /** The break duration in minutes ({@code break_duration_minutes}), or null when breaks are not set up. */
    public Double breakMinutes() {
        JsonNode minutes = game.path("break_duration_minutes");
        if (!minutes.isNumber() || !Double.isFinite(minutes.asDouble()) || minutes.asDouble() <= 0) {
            return null;
        }
        return minutes.asDouble();
    }

    /** A break lasts whole seconds, at least one (the Tool's {@code break_length}). */
    public static long breakSeconds(double minutes) {
        return Math.max(1, (long) Math.ceil(Math.round(minutes * 60 * 1e6) / 1e6));
    }

    /** The ping limit of a speedhunt ({@code pings_per_speedhunt}), or null when speedhunts are unlimited. */
    public Integer pingsPerSpeedhunt() {
        JsonNode pings = game.path("pings_per_speedhunt");
        if (pings.isMissingNode() || pings.isNull()) {
            return DEFAULT_PINGS_PER_SPEEDHUNT;
        }
        if (pings.isTextual() && pings.asText().isBlank()) {
            return null;
        }
        return pings.canConvertToInt() && pings.asInt() > 0 ? pings.asInt() : DEFAULT_PINGS_PER_SPEEDHUNT;
    }

    /** The highest ping number of a speedhunt that a fake may name. */
    public int lastFakePing() {
        Integer limit = pingsPerSpeedhunt();
        return limit == null ? MAX_PING_NUMBER : Math.min(limit, MAX_PING_NUMBER);
    }

    /** The live ping duration in seconds ({@code fixed_speedhunt_live_duration_minutes}), or null when unset. */
    public Double liveDurationSeconds() {
        JsonNode minutes = game.path("fixed_speedhunt_live_duration_minutes");
        double seconds = minutes.asDouble() * 60;
        return minutes.isNumber() && Double.isFinite(seconds) && seconds >= 0.000001 ? seconds : null;
    }

    /** The live ping duration in minutes as configured, for display; null when unset. */
    public Double fixedLiveMinutes() {
        return liveDurationSeconds() == null ? null : game.path("fixed_speedhunt_live_duration_minutes").asDouble();
    }

    /** How exceeded limits are enforced: {@code block} or {@code warn} (the default). */
    public String limitEnforcement() {
        return limitsBlock() ? "block" : "warn";
    }

    public boolean allowConsecutiveSpeedhunts() {
        return game.path("allow_consecutive_speedhunts").asBoolean(false);
    }

    /** Whether exceeded limits block ({@code block}) instead of asking for approval ({@code warn}). */
    public boolean limitsBlock() {
        return "block".equals(game.path("limit_enforcement").asText());
    }

    /** Whether a request type needs the operator's approval ({@code request_approvals}); required by default. */
    public boolean approvalRequired(String key) {
        return key == null || game.path("request_approvals").path(key).asBoolean(true);
    }

}
