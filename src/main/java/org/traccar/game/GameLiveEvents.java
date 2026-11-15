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
import java.util.List;
import java.util.Set;

/**
 * The execution events of server-run live uploads (plan phase 2, B5): category {@code live} for a participant's
 * uploads (live speedhunt ping, manual and geofence live tracking) and {@code relay} for live hunter relays, each
 * with {@code data.type} {@code started}, {@code sample}, {@code waitingForLocation} or {@code ended}. Unlike other
 * outcomes of Tool-sent intents, starts and stops are events too: the upload lives on the server, so its log line
 * is the server's. A {@code sample} is written when a tick's outcome differs from the previous tick's (the first
 * delivery, a failure, a recovery) or when it records something (a live ping's ping, a relay's joker), never for
 * every tick. Their English {@code message} is the Tool's fallback.
 */
public final class GameLiveEvents {

    public static final String STARTED = "started";
    public static final String SAMPLE = "sample";
    public static final String WAITING = "waitingForLocation";
    public static final String ENDED = "ended";

    /** End reasons of the server's own ends; a Tool's stop carries its own {@code endReason}. */
    public static final String DEADLINE = "deadline";
    public static final String CAUGHT = "caught";
    public static final String RETIRED = "retired";
    public static final String RUN_ENDED = "runEnded";
    public static final String NO_JOKER = "noJoker";
    public static final String REJECTED = "rejected";
    /** The Tool restarts the upload with other audiences: the live ping neither fails nor completes its run. */
    public static final String REPLACED = "replaced";

    /** Ends that never fail a live ping nor complete its speedhunt. */
    static final Set<String> QUIET_ENDS = Set.of(CAUGHT, RETIRED, RUN_ENDED, REPLACED);

    private GameLiveEvents() {
    }

    static String category(GameState.LiveUpload upload) {
        return upload.relay() ? GameEvent.RELAY : GameEvent.LIVE;
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        if (values != null) {
            values.forEach(array::add);
        }
        return array;
    }

    /** The fields every event of an upload carries; returns the member's display name. */
    private static String base(GameTransaction transaction, long sessionId, GameState.LiveUpload upload, String type,
                               ObjectNode data) throws SQLException {
        var member = GameMemberRepository.find(transaction, sessionId, upload.memberId()).orElse(null);
        String name = member == null ? "member " + upload.memberId() : member.displayName();
        data.put("type", type);
        data.put("liveUploadId", upload.id());
        data.put("purpose", upload.purpose());
        data.put("memberId", upload.memberId());
        if (member != null && member.trackerId() != null) {
            data.put("trackerId", member.trackerId());
        }
        data.put("displayName", name);
        return name;
    }

    private static String code(GameState.LiveUpload upload) {
        return TimetableInstruction.code(upload.audiences());
    }

    private static String label(GameState.LiveUpload upload) {
        return switch (upload.purpose()) {
            case GameState.SPEEDHUNT_PING -> "Live Ping";
            case GameState.GEOFENCE_AUTO -> "Geofence live ping";
            case GameState.HUNTER_RELAY -> "Hunter live relay";
            default -> "Live ping";
        };
    }

    static void started(GameTransaction transaction, GamePublisher publisher, long sessionId,
                        GameState.LiveUpload upload) throws SQLException {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        String name = base(transaction, sessionId, upload, STARTED, data);
        data.set("audiences", strings(upload.audiences()));
        data.put("intervalSeconds", upload.intervalSeconds());
        data.put("startedAt", upload.startedAt().toString());
        if (upload.deadline() != null) {
            data.put("deadline", upload.deadline().toString());
        }
        if (upload.reason() != null) {
            data.put("reason", upload.reason());
        }
        if (upload.requestId() != null) {
            data.put("requestId", upload.requestId());
        }
        if (upload.relay()) {
            data.put("useJoker", upload.useJoker());
            data.put("overAllocate", upload.overAllocate());
        }
        if (upload.pingNumber() != null) {
            data.put("pingNumber", upload.pingNumber());
        }
        String message = upload.relay() ? label(upload) + " enabled for " + name
                : label(upload) + " enabled for " + name + " to " + code(upload);
        GameLogService.event(transaction, publisher, sessionId, category(upload),
                message + (upload.deadline() == null ? "" : " until " + upload.deadline()), data);
    }

    /** What a tick recorded besides its positions. */
    record Recorded(GameOperations.Result ping, Integer limit, GameJokerRepository.Reservation joker) {
        static final Recorded NONE = new Recorded(null, null, null);

        boolean any() {
            return ping != null || joker != null;
        }
    }

    static void sample(GameTransaction transaction, GamePublisher publisher, long sessionId,
                       GameState.LiveUpload upload, GamePingEngine.Outcome outcome, Recorded recorded,
                       boolean resumed) throws SQLException {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        String name = base(transaction, sessionId, upload, SAMPLE, data);
        data.put("status", outcome.status());
        if (outcome.reason() != null) {
            data.put("reason", outcome.reason());
        }
        data.set("delivered", strings(outcome.delivered()));
        data.set("withheld", strings(outcome.withheld()));
        if (outcome.position() != null) {
            ObjectNode position = data.putObject("position");
            position.put("latitude", outcome.position().latitude());
            position.put("longitude", outcome.position().longitude());
            position.put("fixTime", outcome.position().fixTime());
            position.put("stale", outcome.position().stale());
        }
        if (upload.relay()) {
            data.put("hunters", outcome.hunters() == null ? 0 : outcome.hunters().size());
        }
        if (outcome.detail() != null) {
            data.put("detail", GameJokerRepository.clip(outcome.detail(), 512));
        }
        if (resumed) {
            data.put("resumed", true);
        }
        if (recorded.ping() != null) {
            ObjectNode ping = data.putObject("ping");
            ping.put("pingId", recorded.ping().id());
            ping.put("runId", recorded.ping().runId());
            ping.put("number", recorded.ping().number());
            ping.put("source", GameState.JOKER_FAKE.equals(recorded.ping().source()) ? GameState.REAL
                    : recorded.ping().source());
            ping.put("jokerFake", GameState.JOKER_FAKE.equals(recorded.ping().source()));
            if (recorded.limit() != null) {
                ping.put("limit", recorded.limit());
            }
            ping.set("audiences", strings(upload.audiences()));
            ping.put("startedAt", upload.startedAt().toString());
            if (upload.deadline() != null) {
                ping.put("deadline", upload.deadline().toString());
            }
        }
        if (recorded.joker() != null) {
            ObjectNode joker = data.putObject("joker");
            joker.put("reservationId", recorded.joker().id());
            joker.put("overAllocated", recorded.joker().overAllocated());
        }
        GameLogService.event(transaction, publisher, sessionId, category(upload),
                sampleMessage(upload, name, outcome, recorded), data);
    }

    private static String sampleMessage(GameState.LiveUpload upload, String name, GamePingEngine.Outcome outcome,
                                        Recorded recorded) {
        StringBuilder text = new StringBuilder();
        if (upload.relay()) {
            if (outcome.deliveredAny()) {
                text.append("Hunter positions relayed live to ").append(name).append(": ")
                        .append(outcome.hunters().size()).append(" hunter(s)");
            } else {
                text.append("No hunter position relayed live to ").append(name).append(": ")
                        .append(reasonText(outcome.reason()));
            }
        } else if (outcome.deliveredAny()) {
            text.append(label(upload)).append(" upload for ").append(name).append(" sent to ")
                    .append(TimetableInstruction.code(outcome.delivered()));
            if (!outcome.withheld().isEmpty()) {
                text.append(" (").append(String.join(", ", outcome.withheld())).append(" withheld during the break)");
            }
        } else {
            text.append("Nothing uploaded live for ").append(name).append(": ").append(reasonText(outcome.reason()));
        }
        if (outcome.detail() != null) {
            text.append(" (").append(outcome.detail()).append(')');
        }
        text.append('.');
        if (recorded.ping() != null) {
            text.append(" Ping ").append(recorded.ping().number())
                    .append(recorded.limit() == null ? "" : " of " + recorded.limit()).append(" delivered.");
        }
        if (recorded.joker() != null) {
            text.append(" One Get hunter positions joker of ").append(name).append(" used")
                    .append(recorded.joker().overAllocated() ? " beyond the allocation" : "").append('.');
        }
        return text.toString();
    }

    static void waiting(GameTransaction transaction, GamePublisher publisher, long sessionId,
                        GameState.LiveUpload upload, GamePingEngine.Outcome outcome) throws SQLException {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        String name = base(transaction, sessionId, upload, WAITING, data);
        if (outcome.detail() != null) {
            data.put("detail", GameJokerRepository.clip(outcome.detail(), 512));
        }
        GameLogService.event(transaction, publisher, sessionId, category(upload), upload.relay()
                ? "Hunter live relay for " + name + " is waiting for the first hunter position."
                : "Live tracking for " + name + " is waiting for first location data from the real device.", data);
    }

    /** What ending a live speedhunt ping did to its speedhunt. */
    record Ended(Long releasedReservationId, Long completedRunId) {
        static final Ended NONE = new Ended(null, null);
    }

    static void ended(GameTransaction transaction, GamePublisher publisher, long sessionId,
                      GameState.LiveUpload upload, String endReason, String detail, Ended effects)
            throws SQLException {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        String name = base(transaction, sessionId, upload, ENDED, data);
        data.put("endReason", endReason);
        if (detail != null) {
            data.put("detail", GameJokerRepository.clip(detail, 512));
        }
        data.set("audiences", strings(upload.audiences()));
        if (upload.lastSampleAt() != null) {
            data.put("lastSampleAt", upload.lastSampleAt().toString());
        }
        if (upload.reason() != null) {
            data.put("reason", upload.reason());
        }
        if (GameState.SPEEDHUNT_PING.equals(upload.purpose())) {
            data.put("pingDelivered", upload.pingId() != null);
            if (effects.releasedReservationId() != null) {
                data.put("pingFailed", true);
            }
            if (effects.completedRunId() != null) {
                data.put("runCompleted", effects.completedRunId());
            }
        }
        if (upload.relay()) {
            data.put("jokerUsed", upload.reservationId() != null);
        }
        String message = label(upload) + " stopped for " + name + " (" + endText(endReason) + ")"
                + (detail == null ? "" : ": " + detail);
        if (effects.releasedReservationId() != null) {
            message += "; it reached nobody, so the hunters' joker returned";
        }
        if (effects.completedRunId() != null) {
            message += "; the speedhunt is over";
        }
        GameLogService.event(transaction, publisher, sessionId, category(upload), message + ".", data);
    }

    static String endText(String endReason) {
        if (endReason == null) {
            return "stopped";
        }
        return switch (endReason) {
            case DEADLINE -> "timer expired";
            case CAUGHT -> "caught";
            case RETIRED -> "the member was retired";
            case RUN_ENDED -> "the speedhunt ended";
            case NO_JOKER -> "no Get hunter positions joker is left";
            case REPLACED -> "replaced with other targets";
            case GameExecutionService.GAME_STOPPED -> "the game was stopped";
            default -> endReason;
        };
    }

    private static String reasonText(String reason) {
        if (reason == null) {
            return "nothing was sent";
        }
        return switch (reason) {
            case GamePingEngine.INACTIVE -> "the participant is retired or has no tracker";
            case GamePingEngine.CAUGHT -> "the participant is caught";
            case GamePingEngine.BREAK -> "they are on a break, so hunters receive none of their positions";
            case GamePingEngine.NO_RECIPIENT -> "there is no eligible recipient";
            case GamePingEngine.NO_MIRROR -> "a mirror device is missing";
            case GamePingEngine.NO_POSITION -> "no position is known yet";
            default -> reason;
        };
    }

}
