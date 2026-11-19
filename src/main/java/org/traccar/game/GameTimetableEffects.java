/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Joker and break effects on timetable cells (Claude outputs/timetable-instructions-effects-plan.md, 8). They are
 * derived on each operator read from the instruction the operator wrote and the current joker and break state, and
 * never written into cells; execution works from the same instruction and the live state.
 *
 * <p>Rules ({@link #apply}): a break withholds the hunters from the real and the fake audiences
 * ({@link #withhold}, the same step {@link GamePingEngine#decide} runs). An armed fake-ping joker moves the hunters
 * from the real to the fake audiences with its coordinate; it applies only to cells that can take a fake
 * ({@link TimetableInstruction#fakeEligible}), never to coordinate cells. When both apply the break wins: a break
 * that starts releases the armed fakes inside it, and the joker is still listed so the operator sees it will be
 * released.
 *
 * <p>Which entries have an effect ({@link #read}): an open entry ({@code scheduled}) has the fake joker armed on
 * it, and the break of its member whose window ({@link GameBreakRunner#window}) contains its time. A finished entry
 * takes its effect from what is stored: reason {@code break} means a break (the member's break that protected them
 * when the entry was decided), and a used (consumed) fake plan on the entry means a fake joker.
 */
public final class GameTimetableEffects {

    private GameTimetableEffects() {
    }

    // Rules -------------------------------------------------------------------------------------------------

    /** Audiences after a step: who gets the real position, who the fake coordinate, and who is withheld. */
    public record Audiences(List<String> real, List<String> fake, List<String> withheld) {
    }

    /** The result of the rules: the audiences and the coordinate the fake audiences receive. */
    public record Result(List<String> real, List<String> fake, List<String> withheld, Double latitude,
                         Double longitude) {
    }

    static List<String> ordered(List<String> audiences) {
        return TimetableInstruction.AUDIENCES.stream().filter(audiences::contains).toList();
    }

    private static List<String> without(List<String> audiences, String removed) {
        return audiences.stream().filter(audience -> !audience.equals(removed)).toList();
    }

    /** The break step: a break withholds the hunters from a participant's pings, real or fake. */
    public static Audiences withhold(List<String> real, List<String> fake) {
        boolean hunters = real.contains(TimetableInstruction.HUNTERS) || fake.contains(TimetableInstruction.HUNTERS);
        return new Audiences(without(ordered(real), TimetableInstruction.HUNTERS),
                without(ordered(fake), TimetableInstruction.HUNTERS),
                hunters ? List.of(TimetableInstruction.HUNTERS) : List.of());
    }

    /**
     * The effect of a break and a fake joker on audiences: {@code real} and {@code fake} with the coordinate
     * {@code latitude}, {@code longitude} of the fake ones. The joker applies only while the hunters still get the
     * real position and nothing is fake.
     */
    public static Result apply(List<String> real, List<String> fake, Double latitude, Double longitude,
                               boolean onBreak, GamePingEngine.JokerFake joker) {
        Audiences audiences = onBreak ? withhold(real, fake) : new Audiences(ordered(real), ordered(fake), List.of());
        if (joker != null && audiences.real().contains(TimetableInstruction.HUNTERS) && audiences.fake().isEmpty()) {
            return new Result(without(audiences.real(), TimetableInstruction.HUNTERS),
                    List.of(TimetableInstruction.HUNTERS), audiences.withheld(), joker.latitude(), joker.longitude());
        }
        return new Result(audiences.real(), audiences.fake(), audiences.withheld(),
                audiences.fake().isEmpty() ? null : latitude, audiences.fake().isEmpty() ? null : longitude);
    }

    /** The effect on one cell; the joker is ignored unless the cell can take a fake. */
    public static Result apply(TimetableInstruction instruction, boolean onBreak, GamePingEngine.JokerFake joker) {
        return apply(instruction.audiences(), instruction.fakeAudiences(), instruction.latitude(),
                instruction.longitude(), onBreak, instruction.fakeEligible() ? joker : null);
    }

    // Read view ---------------------------------------------------------------------------------------------

    /** The fake joker on an entry: armed (open entries) or used (finished entries). */
    public record FakeJoker(long reservationId, double latitude, double longitude) {
    }

    /**
     * The break on an entry: its ID and current state, and when it protects the member ({@code from} inclusive,
     * {@code until} exclusive), {@code estimated} for a waiting break or one whose start is not known yet. On a
     * finished entry whose break can't be found anymore every field but {@code estimated} is null.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Break(Long breakId, String state, Instant from, Instant until, boolean estimated) {
    }

    /**
     * The effect of jokers and breaks on an entry: the fake joker and the break that apply (null when none), and
     * the result: who gets the real position ({@code real}), who the coordinate {@code latitude}, {@code longitude}
     * ({@code fake}; null when nobody), and who the break withholds. Audiences are in delivery order (hunters,
     * players, supporters).
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Effect(FakeJoker fakeJoker, @JsonProperty("break") Break pause, List<String> real,
                         List<String> fake, List<String> withheld, Double latitude, Double longitude) {
    }

    /** The effect of a joker and a break on a cell, or null when neither applies. */
    public static Effect effect(String content, FakeJoker joker, Break pause) {
        if (joker == null && pause == null) {
            return null;
        }
        TimetableInstruction instruction = TimetableInstruction.parse(content);
        if (!instruction.valid() || instruction.none()) {
            return null;
        }
        Result result = apply(instruction, pause != null, joker == null ? null
                : new GamePingEngine.JokerFake(joker.reservationId(), joker.latitude(), joker.longitude()));
        return new Effect(joker, pause, result.real(), result.fake(), result.withheld(), result.latitude(),
                result.longitude());
    }

    /** A current break of a member and its window. */
    private record Current(GameState.Break pause, GameBreakRunner.Window window) {
    }

    /** What {@link #read} needs to derive the effects of every entry of a session. */
    static final class Reader {
        private final Map<Long, FakeJoker> armed = new HashMap<>();
        private final Map<Long, FakeJoker> used = new HashMap<>();
        private final Map<Long, List<Current>> current = new HashMap<>();
        private final Map<Long, List<GameState.Break>> started = new HashMap<>();

        Reader(GameTransaction transaction, long sessionId) throws SQLException {
            Instant now = transaction.now();
            for (var plan : GameJokerRepository.plans(transaction, sessionId,
                    "p.timetableentryid IS NOT NULL AND " + GameJokerRepository.ARMED)) {
                armed.put(plan.timetableEntryId(), new FakeJoker(plan.reservationId(), plan.latitude(),
                        plan.longitude()));
            }
            // A used fake keeps its plan (only openentryid is cleared) and its reservation is consumed.
            for (var plan : GameJokerRepository.plans(transaction, sessionId,
                    "p.timetableentryid IS NOT NULL AND r.status = ?", GameJokerRepository.CONSUMED)) {
                used.put(plan.timetableEntryId(), new FakeJoker(plan.reservationId(), plan.latitude(),
                        plan.longitude()));
            }
            Double minutes = GameSettings.read(transaction, sessionId).breakMinutes();
            for (var pause : GameStateRepository.breaks(transaction, sessionId)) {
                if (pause.startedAt() != null) {
                    started.computeIfAbsent(pause.memberId(), key -> new ArrayList<>()).add(pause);
                }
                if (!pause.active() && !pause.pending()) {
                    continue;
                }
                boolean onTime = pause.pending() && !pause.plannedStart().isAfter(now)
                        && GameBreakRunner.onTime(transaction, sessionId, pause, now);
                var window = GameBreakRunner.window(pause, minutes, now, onTime);
                if (window != null) {
                    current.computeIfAbsent(pause.memberId(), key -> new ArrayList<>())
                            .add(new Current(pause, window));
                }
            }
            current.values().forEach(list -> list.sort(Comparator.comparing(item -> item.window().from())));
        }

        /** The effect of a present entry, or null. */
        Effect effect(GameTimetableRepository.Entry entry) {
            if (entry.open()) {
                Break pause = null;
                for (var item : current.getOrDefault(entry.memberId(), List.of())) {
                    if (item.window().contains(entry.scheduledAt())) {
                        pause = new Break(item.pause().id(), item.pause().state(), item.window().from(),
                                item.window().until(), item.window().estimated());
                        break;
                    }
                }
                return GameTimetableEffects.effect(entry.content(), armed.get(entry.id()), pause);
            }
            Break pause = null;
            if (GamePingEngine.BREAK.equals(entry.reason())) {
                pause = new Break(null, null, null, null, false);
                Instant decided = entry.statusAt() != null ? entry.statusAt() : entry.scheduledAt();
                for (var item : started.getOrDefault(entry.memberId(), List.of())) {
                    // It protected the member until its deadline, or until it ended earlier.
                    Instant until = item.endedAt() != null
                            && (item.deadline() == null || item.endedAt().isBefore(item.deadline()))
                            ? item.endedAt() : item.deadline();
                    if (!item.startedAt().isAfter(decided) && (until == null || until.isAfter(decided))) {
                        pause = new Break(item.id(), item.state(), item.startedAt(), until, false);
                        break;
                    }
                }
            }
            return GameTimetableEffects.effect(entry.content(), used.get(entry.id()), pause);
        }
    }

    /** Reads what the effects of a session's entries depend on, inside a transaction. */
    static Reader read(GameTransaction transaction, long sessionId) throws SQLException {
        return new Reader(transaction, sessionId);
    }

}
