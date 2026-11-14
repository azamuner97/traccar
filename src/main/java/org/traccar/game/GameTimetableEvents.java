/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code timetable} execution events (plan 1.5, B5): {@code slotStarted}, {@code ping}, {@code slotFinished},
 * {@code slotMissed} and {@code execution}, each with {@code data.type}. Their English {@code message} is the
 * Tool's fallback; the Tool formats {@code data} with its own wording, session time zone and device prefixes.
 */
public final class GameTimetableEvents {

    public static final String SLOT_STARTED = "slotStarted";
    public static final String PING = "ping";
    public static final String SLOT_FINISHED = "slotFinished";
    public static final String SLOT_MISSED = "slotMissed";
    public static final String EXECUTION = "execution";

    /** Reasons of entries the runner never sent. */
    public static final String SKIP = "skip";
    public static final String INVALID = "invalid";
    public static final String STOPPED = "stopped";
    public static final String ERROR = "error";

    private GameTimetableEvents() {
    }

    private static ObjectNode data(String type) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("type", type);
        return data;
    }

    static void slotStarted(GameTransaction transaction, GamePublisher publisher, long sessionId, long slotId,
                            Instant scheduledAt, int entries) throws SQLException {
        ObjectNode data = data(SLOT_STARTED);
        data.put("slotId", slotId);
        data.put("scheduledAt", scheduledAt.toString());
        data.put("entries", entries);
        GameLogService.event(transaction, publisher, sessionId, GameEvent.TIMETABLE,
                "Automated upload at " + scheduledAt, data);
    }

    static void slotMissed(GameTransaction transaction, GamePublisher publisher, long sessionId, long slotId,
                           Instant scheduledAt, int entries) throws SQLException {
        ObjectNode data = data(SLOT_MISSED);
        data.put("slotId", slotId);
        data.put("scheduledAt", scheduledAt.toString());
        data.put("entries", entries);
        GameLogService.event(transaction, publisher, sessionId, GameEvent.TIMETABLE,
                "Timetable row at " + scheduledAt + " was missed (" + entries + " pings)", data);
    }

    /** Writes {@code slotFinished} with the counts per status of the slot's present entries. */
    static void slotFinished(GameTransaction transaction, GamePublisher publisher, long sessionId,
                             GameTimetableRepository.Slot slot) throws SQLException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String status : List.of(GameTimetableRepository.SENT, GameTimetableRepository.SKIPPED,
                GameTimetableRepository.FAILED)) {
            counts.put(status, 0);
        }
        for (var entry : GameTimetableRepository.entries(transaction, sessionId, slot.id())) {
            if (counts.containsKey(entry.status())) {
                counts.merge(entry.status(), 1, Integer::sum);
            }
        }
        ObjectNode data = data(SLOT_FINISHED);
        data.put("slotId", slot.id());
        data.put("scheduledAt", slot.scheduledAt().toString());
        ObjectNode node = data.putObject("counts");
        counts.forEach(node::put);
        GameLogService.event(transaction, publisher, sessionId, GameEvent.TIMETABLE,
                "Timetable row at " + slot.scheduledAt() + " finished: " + counts.get("sent") + " sent, "
                        + counts.get("skipped") + " skipped, " + counts.get("failed") + " failed", data);
    }

    static void execution(GameTransaction transaction, GamePublisher publisher, long sessionId, String state,
                          String changedBy) throws SQLException {
        ObjectNode data = data(EXECUTION);
        data.put("state", state);
        if (changedBy != null) {
            data.put("changedBy", changedBy);
        }
        String message = switch (state) {
            case "on" -> "Timetable execution switched on";
            case "off" -> "Timetable execution switched off";
            default -> "Timetable completed: no scheduled row is left";
        };
        GameLogService.event(transaction, publisher, sessionId, GameEvent.TIMETABLE,
                message + (changedBy == null ? "" : " by " + changedBy), data);
    }

    private static ArrayNode audiences(List<String> values) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        if (values != null) {
            values.forEach(array::add);
        }
        return array;
    }

    /** Writes the {@code ping} event of one entry's outcome (engine outcome, or none for an entry never sent). */
    static void ping(GameTransaction transaction, GamePublisher publisher, long sessionId,
                     GameTimetableRepository.Entry entry, String status, String reason, String detail,
                     GamePingEngine.Outcome outcome) throws SQLException {
        var member = GameMemberRepository.find(transaction, sessionId, entry.memberId()).orElse(null);
        String name = member == null ? "member " + entry.memberId() : member.displayName();
        ObjectNode data = data(PING);
        data.put("slotId", entry.slotId());
        data.put("scheduledAt", entry.scheduledAt().toString());
        data.put("entryId", entry.id());
        data.put("memberId", entry.memberId());
        if (member != null && member.trackerId() != null) {
            data.put("trackerId", member.trackerId());
        }
        data.put("displayName", name);
        data.put("content", entry.content());
        data.put("status", status);
        if (reason != null) {
            data.put("reason", reason);
        }
        data.set("delivered", audiences(outcome == null ? List.of() : outcome.delivered()));
        data.set("withheld", audiences(outcome == null ? List.of() : outcome.withheld()));
        if (outcome != null && outcome.fake() != null) {
            ObjectNode coordinate = data.putObject("coordinate");
            coordinate.put("latitude", outcome.fake().latitude());
            coordinate.put("longitude", outcome.fake().longitude());
            coordinate.set("audiences", audiences(outcome.fake().audiences()));
        }
        if (outcome != null && outcome.jokerFake() != null) {
            ObjectNode fake = data.putObject("fake");
            fake.put("reservationId", outcome.jokerFake().reservationId());
            fake.put("status", outcome.jokerFake().status());
            fake.put("latitude", outcome.jokerFake().latitude());
            fake.put("longitude", outcome.jokerFake().longitude());
        }
        if (outcome != null && outcome.position() != null) {
            ObjectNode position = data.putObject("position");
            position.put("latitude", outcome.position().latitude());
            position.put("longitude", outcome.position().longitude());
            position.put("fixTime", outcome.position().fixTime());
            position.put("stale", outcome.position().stale());
        }
        if (outcome != null && outcome.delaySeconds() != null) {
            data.put("delaySeconds", outcome.delaySeconds());
        }
        if (detail != null) {
            data.put("detail", GameJokerRepository.clip(detail, 512));
        }
        GameLogService.event(transaction, publisher, sessionId, GameEvent.TIMETABLE,
                message(name, entry.content(), status, reason, detail, outcome), data);
    }

    private static String message(String name, String content, String status, String reason, String detail,
                                  GamePingEngine.Outcome outcome) {
        StringBuilder text = new StringBuilder();
        if (outcome != null && outcome.position() != null && outcome.position().stale()) {
            text.append("Using last known position for ").append(name).append(" from ")
                    .append(outcome.position().fixTime()).append(" because a fresh ping was not available yet. ");
        }
        if (outcome != null && outcome.deliveredAny()) {
            text.append("Uploaded for ").append(name).append(" to ")
                    .append(TimetableInstruction.code(outcome.delivered()));
            if (outcome.fake() != null) {
                text.append(" (coordinate ").append(outcome.fake().latitude()).append(',')
                        .append(outcome.fake().longitude()).append(" to ")
                        .append(TimetableInstruction.code(outcome.fake().audiences())).append(')');
            }
            if (!outcome.withheld().isEmpty()) {
                text.append(" (").append(String.join(", ", outcome.withheld())).append(" withheld during the break)");
            }
            text.append('.');
            if (outcome.jokerFake() != null && GameJokerRepository.CONSUMED.equals(outcome.jokerFake().status())) {
                text.append(" Hunters received ").append(name).append("'s fake ping joker coordinate.");
            }
        } else if (SKIP.equals(reason)) {
            text.append("Skipped ").append(name);
        } else {
            text.append(GamePingEngine.FAILED.equals(status) ? "Nothing uploaded for " : "Skipped ").append(name)
                    .append(content.isEmpty() ? "" : " (" + content + ")").append(": ").append(reasonText(reason));
        }
        if (detail != null && !(outcome != null && outcome.deliveredAny() && detail.startsWith("No real"))) {
            text.append(" (").append(detail).append(')');
        } else if (detail != null) {
            text.append(' ').append(detail).append('.');
        }
        return text.toString().strip();
    }

    private static String reasonText(String reason) {
        if (reason == null) {
            return "nothing was sent";
        }
        return switch (reason) {
            case GamePingEngine.INACTIVE -> "the participant is retired or has no tracker";
            case GamePingEngine.CAUGHT -> "the participant is caught";
            case GamePingEngine.BREAK -> "they are on a break, so hunters receive none of their positions";
            case GamePingEngine.NO_RECIPIENT -> "the target has no eligible recipients";
            case GamePingEngine.NO_MIRROR -> "a mirror device is missing";
            case GamePingEngine.NO_POSITION -> "the real device has not sent a position yet";
            case GamePingEngine.MISSED -> "the row was missed";
            case STOPPED -> "the timetable was stopped";
            case INVALID -> "invalid timetable value";
            default -> reason;
        };
    }

}
