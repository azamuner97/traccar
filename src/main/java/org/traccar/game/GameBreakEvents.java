/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

/**
 * The execution events of breaks (plan phase 3, B5), category {@code break}, each with {@code data.type}:
 * <ul>
 * <li>{@code started}: a planned break started on the server ({@link GameBreakRunner}); {@code late} when its start
 * passed while the game was not running, so it ends at its planned end; {@code waited} when it waited first.</li>
 * <li>{@code waiting}: a planned break reached its start but cannot start yet ({@code reason} {@code speedhunt},
 * {@code onBreak} or {@code refused} with the refusal as {@code detail}); written once per wait and reason.</li>
 * <li>{@code ended}: an active break ended at its deadline ({@code endReason} {@code deadline}), whoever started
 * it.</li>
 * <li>{@code missed}: a planned break's planned end passed before it could start; its joker returned.</li>
 * </ul>
 * Every event names {@code breakId}, {@code memberId}, {@code displayName}, {@code trackerId} (when bound),
 * {@code plannedBy} and {@code reservationId}. Breaks the Tool starts with a request, cancellations and the end of
 * a caught member's break are outcomes of Tool-sent intents, not events. The English {@code message} is the
 * Tool's fallback.
 */
public final class GameBreakEvents {

    public static final String STARTED = "started";
    public static final String WAITING = "waiting";
    public static final String ENDED = "ended";
    public static final String MISSED = "missed";

    /** Why a planned break waits at its start. */
    public static final String SPEEDHUNT = "speedhunt";
    public static final String ON_BREAK = "onBreak";
    public static final String REFUSED = "refused";

    /** The end reason of a break that reached its deadline. */
    public static final String DEADLINE = "deadline";

    static final String SPEEDHUNT_TEXT = "a speedhunt is running";
    static final String ON_BREAK_TEXT = "the participant is still on a break";
    static final String MISSED_TEXT = "its planned time passed while the game was not running";

    private GameBreakEvents() {
    }

    /** The fields every event of a break carries; returns the member's display name. */
    private static String base(GameTransaction transaction, long sessionId, GameState.Break pause, String type,
                               ObjectNode data) throws SQLException {
        var member = GameMemberRepository.find(transaction, sessionId, pause.memberId()).orElse(null);
        String name = member == null ? "member " + pause.memberId() : member.displayName();
        data.put("type", type);
        data.put("breakId", pause.id());
        data.put("memberId", pause.memberId());
        if (member != null && member.trackerId() != null) {
            data.put("trackerId", member.trackerId());
        }
        data.put("displayName", name);
        if (pause.plannedBy() != null) {
            data.put("plannedBy", pause.plannedBy());
        }
        if (pause.reservationId() != null) {
            data.put("reservationId", pause.reservationId());
        }
        if (pause.requestId() != null) {
            data.put("requestId", pause.requestId());
        }
        if (pause.plannedStart() != null) {
            data.put("plannedStart", pause.plannedStart().toString());
        }
        return name;
    }

    /** A break's length in minutes as the Tool shows it: whole minutes, or one decimal (rounded half to even). */
    static String minutes(Instant from, Instant to) {
        long seconds = Duration.between(from, to).getSeconds();
        if (seconds % 60 == 0) {
            return String.valueOf(seconds / 60);
        }
        return BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_EVEN)
                .setScale(1, RoundingMode.HALF_EVEN).toPlainString();
    }

    static void started(GameTransaction transaction, GamePublisher publisher, long sessionId, GameState.Break pause,
                        Instant startedAt, Instant deadline, boolean late, boolean waited, boolean overAllocated,
                        int releasedFakes) throws SQLException {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        String name = base(transaction, sessionId, pause, STARTED, data);
        data.put("startedAt", startedAt.toString());
        data.put("deadline", deadline.toString());
        data.put("late", late);
        data.put("waited", waited);
        data.put("overAllocated", overAllocated);
        if (releasedFakes > 0) {
            data.put("releasedFakes", releasedFakes);
        }
        GameLogService.event(transaction, publisher, sessionId, GameEvent.BREAK, name + " is on a planned break until "
                + deadline + " (" + minutes(startedAt, deadline) + " min): hunters receive none of their positions"
                + " and no speedhunt can start on them.", data);
    }

    static void waiting(GameTransaction transaction, GamePublisher publisher, long sessionId, GameState.Break pause,
                        String reason, String detail) throws SQLException {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        String name = base(transaction, sessionId, pause, WAITING, data);
        data.put("reason", reason);
        if (detail != null) {
            data.put("detail", detail);
        }
        String why = switch (reason) {
            case SPEEDHUNT -> SPEEDHUNT_TEXT;
            case ON_BREAK -> ON_BREAK_TEXT;
            default -> detail == null ? "it cannot start yet" : detail;
        };
        GameLogService.event(transaction, publisher, sessionId, GameEvent.BREAK, "The planned break of " + name
                + " for " + pause.plannedStart() + " waits: " + why + ".", data);
    }

    static void ended(GameTransaction transaction, GamePublisher publisher, long sessionId, GameState.Break pause)
            throws SQLException {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        String name = base(transaction, sessionId, pause, ENDED, data);
        data.put("endReason", DEADLINE);
        if (pause.startedAt() != null) {
            data.put("startedAt", pause.startedAt().toString());
        }
        if (pause.deadline() != null) {
            data.put("deadline", pause.deadline().toString());
        }
        GameLogService.event(transaction, publisher, sessionId, GameEvent.BREAK, "The break of " + name
                + " ended (time is up).", data);
    }

    static void missed(GameTransaction transaction, GamePublisher publisher, long sessionId, GameState.Break pause,
                       Instant plannedEnd) throws SQLException {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        String name = base(transaction, sessionId, pause, MISSED, data);
        data.put("plannedEnd", plannedEnd.toString());
        data.put("reason", MISSED_TEXT);
        GameLogService.event(transaction, publisher, sessionId, GameEvent.BREAK, "The planned break of " + name
                + " for " + pause.plannedStart() + " is returned: " + MISSED_TEXT + ".", data);
    }

}
