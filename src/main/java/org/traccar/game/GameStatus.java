/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * What members see of the game (plan §3.3): the role projections of {@link GameStatusService}, computed from the
 * typed tables. Times are ISO-8601 strings, because Traccar's {@code ObjectMapper} has no JSR-310 module.
 *
 * <p>The projections follow the model-1 visibility matrix (implementation plan, Stage 5):
 * <ul>
 * <li>Players and supporters: the catch roster, anonymous speedhunt progress (also when it targets themselves),
 * the running-speedhunt fake choice, and only their own break, armed fakes and planned breaks.</li>
 * <li>Hunters: every participant's speedhunt state and active break under roster labels, the speedhunt limits, the
 * last target and the live shares that reach them.</li>
 * <li>Spectators: the hunters' view with display names and real trackers, without live shares.</li>
 * </ul>
 * Players and supporters never get member state revisions, ping sources, fake plans of others, bound run references
 * or anything that names a speedhunt's target, so a target and a non-target get identical projections.
 */
public final class GameStatus {

    private GameStatus() {
    }

    /**
     * A member's status: the caller's own membership, whether a Tool controls the game, the server time for
     * countdowns, and the role projection. {@code hash} identifies the content of the projection (not the session
     * revision, which members never see), so it changes only with what this member can observe.
     */
    public record Status(long gameSessionId, long memberId, String role, int number, String displayName,
                         boolean controllerActive, String serverTime, String hash, Projection status) {
    }

    /** One of the role projections. */
    public sealed interface Projection permits PlayerView, HunterView, SpectatorView {
    }

    /**
     * The speedhunt currently running, without its target. {@code pingCount} and {@code pingLimit} are left out
     * while zero or unlimited; {@code pingHistory} lists the delivered pings of the current run.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SpeedhuntProgress(boolean active, Integer pingCount, Integer pingLimit,
                                    List<PingTime> pingHistory) {

        static final SpeedhuntProgress INACTIVE = new SpeedhuntProgress(false, null, null, null);
    }

    public record PingTime(int number, String sentAt) {
    }

    /** A break as hunters see it: active from {@code startedAt} until {@code endsAt}; inactive has no times. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Break(boolean active, String startedAt, String endsAt) {

        static final Break NONE = new Break(false, null, null);
    }

    /** The ping slots of a running speedhunt that any player or supporter may plan a fake on. */
    public record RunningFake(String runToken, int firstPing, int lastPing) {
    }

    /** The catch roster that players and supporters see. */
    public record RosterEntry(long memberId, String role, int number, String name, boolean caught) {
    }

    /** An armed fake of the owner: its use, coordinate and its timetable ping or speedhunt ping. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OwnFake(long reservationId, String use, double latitude, double longitude, Long entryId,
                          String scheduledAt, Integer pingNumber, String runToken) {
    }

    /** A planned break of the owner, {@code planned} or {@code waiting}; hunters never see one. */
    public record PlannedBreak(long breakId, Long reservationId, String startsAt, String state, String plannedBy) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlayerView(SpeedhuntProgress speedhunt, @JsonProperty("break") Break pause, RunningFake runningFake,
                             List<RosterEntry> participantOverview, List<OwnFake> fakes,
                             List<PlannedBreak> plannedBreaks) implements Projection {
    }

    /** A participant's speedhunt state; {@code revision} is the member state revision hunter requests carry. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ParticipantSpeedhunt(boolean running, int pings, List<PingTime> pingHistory, boolean startsRun,
                                       boolean live, String liveUntil, long revision) {
    }

    /** A player or supporter in the hunters' and spectators' roster; spectators also get the real tracker. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Participant(long memberId, String role, int number, String name, Long trackerId, boolean caught,
                              ParticipantSpeedhunt speedhunt, @JsonProperty("break") Break pause) {
    }

    public record RequestApprovals(boolean speedhuntStaticPing, boolean speedhuntLivePing) {
    }

    /** The speedhunt rules hunters check before they ask; {@code pingsPerSpeedhunt} is null when unlimited. */
    public record Limits(Integer pingsPerSpeedhunt, Double fixedLiveMinutes, boolean allowConsecutiveSpeedhunts,
                         String limitEnforcement, RequestApprovals requestApprovals) {
    }

    /** A participant whose positions reach the hunters live; {@code source}: manual, geofence or speedhunt. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LiveShare(long memberId, String name, String endsAt, String source, String reason) {
    }

    public record HunterView(SpeedhuntProgress speedhunt, List<Participant> participants, Limits limits,
                             Long lastSpeedhuntMemberId, List<LiveShare> liveShares) implements Projection {
    }

    public record SpectatorView(SpeedhuntProgress speedhunt, List<Participant> participants, Limits limits)
            implements Projection {
    }

    /** A session's speedhunts, newest first, with the same anonymous projection for every member. */
    public record History(long gameSessionId, List<HistoryRun> runs) {
    }

    /** {@code status} is {@code inProgress} or {@code finished}; the target and the outcome stay with operators. */
    public record HistoryRun(long runId, String startedAt, String status, List<HistoryPing> pings) {
    }

    /** {@code mode} is {@code static} or {@code live}; a ping's source never leaves the operators. */
    public record HistoryPing(long pingId, int number, String sentAt, String mode) {
    }

}
