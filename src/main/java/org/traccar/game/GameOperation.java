/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One typed game state operation of an effects delivery (plan §3.2), in JSON {@code {"type": "catch", ...}}.
 *
 * <p>The server validates each operation against the current state of its row and answers 409 for an illegal
 * transition; it compares deadlines but never schedules anything. Times are ISO-8601 instants. Speedhunt fakes are
 * not operations: a run's first delivered ping binds every armed speedhunt fake, and the server applies the target's
 * own fake to the target's hunter mirror positions.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = GameOperation.Catch.class, name = "catch"),
    @JsonSubTypes.Type(value = GameOperation.Release.class, name = "release"),
    @JsonSubTypes.Type(value = GameOperation.PingDelivered.class, name = "pingDelivered"),
    @JsonSubTypes.Type(value = GameOperation.PingFailed.class, name = "pingFailed"),
    @JsonSubTypes.Type(value = GameOperation.RunEnded.class, name = "runEnded"),
    @JsonSubTypes.Type(value = GameOperation.LiveUploadStarted.class, name = "liveUploadStarted"),
    @JsonSubTypes.Type(value = GameOperation.LiveUploadSample.class, name = "liveUploadSample"),
    @JsonSubTypes.Type(value = GameOperation.LiveUploadEnded.class, name = "liveUploadEnded"),
    @JsonSubTypes.Type(value = GameOperation.BreakPlanned.class, name = "breakPlanned"),
    @JsonSubTypes.Type(value = GameOperation.BreakWaiting.class, name = "breakWaiting"),
    @JsonSubTypes.Type(value = GameOperation.BreakStarted.class, name = "breakStarted"),
    @JsonSubTypes.Type(value = GameOperation.BreakEnded.class, name = "breakEnded"),
    @JsonSubTypes.Type(value = GameOperation.BreakCancelled.class, name = "breakCancelled"),
    @JsonSubTypes.Type(value = GameOperation.BreakMissed.class, name = "breakMissed"),
    @JsonSubTypes.Type(value = GameOperation.FakeUsed.class, name = "fakeUsed"),
    @JsonSubTypes.Type(value = GameOperation.FakeReleased.class, name = "fakeReleased"),
    @JsonSubTypes.Type(value = GameOperation.Checkpoint.class, name = "checkpoint"),
})
public sealed interface GameOperation {

    /** The operation's name in the delivery and its outcome. */
    default String name() {
        JsonSubTypes.Type[] types = GameOperation.class.getAnnotation(JsonSubTypes.class).value();
        for (JsonSubTypes.Type type : types) {
            if (type.value() == getClass()) {
                return type.name();
            }
        }
        return getClass().getSimpleName();
    }

    /** A player or supporter is caught: their open requests end and their armed plans and break end. */
    record Catch(long memberId, String actor) implements GameOperation {
    }

    record Release(long memberId) implements GameOperation {
    }

    /**
     * A logical speedhunt ping reached its audiences. Ping 1 of a member without a running speedhunt starts a run
     * with the audience flags, uses a joker of the hunters' pool (the one held for the target, or one more, beyond
     * the allocation only with {@code overAllocate}) and binds the armed speedhunt fakes. Later pings must be the
     * next number. A live ping names its live upload, whose first delivered sample this is. {@code source} is
     * {@code real} or {@code operatorFake}; the server stores {@code jokerFake} when the target's own fake applies.
     */
    record PingDelivered(long targetMemberId, int number, String mode, String source, Boolean toHunters,
                         Boolean toPlayers, Boolean toSupporters, Long requestId, Long liveUploadId,
                         String startedBy, Boolean overAllocate) implements GameOperation {
    }

    /**
     * A ping reached nobody; it leaves no row. Without a running speedhunt on the target, a pool joker armed for
     * the start of one is returned.
     */
    record PingFailed(long targetMemberId, String reason) implements GameOperation {
    }

    /** The target's running speedhunt ends: {@code completed} at the ping limit or {@code terminated}. */
    record RunEnded(long targetMemberId, String outcome, String reason) implements GameOperation {
    }

    /**
     * Repeated uploads start: a live speedhunt ping, manual or geofence live tracking of {@code sourceMemberId}, or a
     * live hunter relay to {@code recipientMemberId}. A hunter relay uses a "Get hunter positions" joker with its
     * first tick that delivers ({@code useJoker}), on this operation or a later sample.
     */
    record LiveUploadStarted(String purpose, Long sourceMemberId, Long recipientMemberId, boolean toHunters,
                             boolean toPlayers, boolean toSupporters, int intervalSeconds, String deadline,
                             String reason, Long requestId, Boolean useJoker, Boolean overAllocate)
            implements GameOperation {
    }

    /** A tick of an active live upload; its positions travel in the same delivery. */
    record LiveUploadSample(long liveUploadId, Boolean useJoker, Boolean overAllocate) implements GameOperation {
    }

    record LiveUploadEnded(long liveUploadId, String endReason) implements GameOperation {
    }

    /** An operator plans a member's break; without an available break joker it needs {@code overAllocate}. */
    record BreakPlanned(long memberId, String plannedStart, Boolean overAllocate) implements GameOperation {
    }

    /** A planned break reached its start while a speedhunt runs: it waits. */
    record BreakWaiting(long breakId) implements GameOperation {
    }

    /**
     * A break starts: a planned or waiting break ({@code breakId}), or the immediate break of a {@code jokerBreak}
     * request that succeeds in the same delivery ({@code requestId}). The deadline defaults to the configured break
     * duration from now.
     */
    record BreakStarted(Long breakId, Long requestId, String deadline) implements GameOperation {
    }

    record BreakEnded(long breakId) implements GameOperation {
    }

    /** A planned or waiting break is cancelled by an operator; its joker returns. */
    record BreakCancelled(long breakId, String detail) implements GameOperation {
    }

    /** A planned or waiting break was missed (its planned end passed before it could start); its joker returns. */
    record BreakMissed(long breakId) implements GameOperation {
    }

    /** An armed timetable fake reached the hunters with this delivery. */
    record FakeUsed(long reservationId) implements GameOperation {
    }

    /** An armed timetable fake is returned: its ping was skipped, failed, or no longer reaches hunters. */
    record FakeReleased(long reservationId, String detail) implements GameOperation {
    }

    /** A component checkpoint written with the delivery. */
    record Checkpoint(String component, long expectedRevision, int schemaVersion, JsonNode document)
            implements GameOperation {
    }

}
