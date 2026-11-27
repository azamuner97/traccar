/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the break timers of the running session (plan phase 3), in {@link TaskGameExecution}, whatever the controller
 * lease does. It ports the rules the Tool applied ({@code planned_breaks.py}, {@code breaks.py}):
 * <ul>
 * <li><b>Deadline.</b> An {@code active} break past its {@code deadline} ends ({@code ended} event). Deadlines are
 * absolute: a break whose deadline passed while the game was stopped protects nobody since then and ends as soon as
 * the game runs again.</li>
 * <li><b>Planned start.</b> A {@code planned} or {@code waiting} break at its start waits while any speedhunt runs
 * (a run or a live speedhunt ping, so a wait reveals no target), while its member is still on another break, or
 * while the access rule refuses it ({@code waiting} event, once per wait and reason). Once it can start it lasts its
 * full duration ({@code started} event; its joker is used, armed timetable fakes inside it are released).</li>
 * <li><b>Late start.</b> A start that passed while the game was not running (the game started more than
 * {@value #START_GRACE_SECONDS} s after it, or the runner first saw it later than that) starts when the game runs and
 * ends at its planned end; once that end has passed too, the break is missed and its joker returns ({@code missed}
 * event). A break that waited while the game ran keeps waiting and then lasts its full duration.</li>
 * </ul>
 * Ends run before starts, so a member whose break just ended can start the next one in the same tick. Immediate
 * breaks of {@code jokerBreak} requests, operator planning and cancellation stay Tool intents.
 */
@Singleton
public class GameBreakRunner implements GameExecutionRunner<GameBreakRunner.Work> {

    /** A planned start seen this late is still on time (the timetable's start grace). */
    public static final long START_GRACE_SECONDS = GameTimetableService.START_GRACE_SECONDS;

    public enum Kind { END, START }

    /** One step: end an active break at its deadline, or start (or wait, or miss) a planned one. */
    public record Work(Kind kind, long breakId) {
    }

    private final GameTransactions transactions;
    private final GamePublisher publisher;
    /**
     * Why each waiting break last waited, as announced. After a restart a waiting break goes on waiting without a
     * new event; a different reason is announced again.
     */
    private final Map<Long, String> waits = new ConcurrentHashMap<>();

    @Inject
    public GameBreakRunner(GameTransactions transactions, GamePublisher publisher) {
        this.transactions = transactions;
        this.publisher = publisher;
    }

    @Override
    public List<Work> due(long sessionId, Instant now) throws Exception {
        return transactions.read(transaction -> {
            if (!GameExecutionService.running(transaction, sessionId)) {
                return List.of();
            }
            List<GameState.Break> ends = new ArrayList<>();
            List<GameState.Break> starts = new ArrayList<>();
            Set<Long> pending = new HashSet<>();
            for (GameState.Break pause : GameStateRepository.currentBreaks(transaction, sessionId, null)) {
                if (pause.active()) {
                    if (pause.deadline() != null && !pause.deadline().isAfter(now)) {
                        ends.add(pause);
                    }
                } else if (pause.pending()) {
                    pending.add(pause.id());
                    if (!pause.plannedStart().isAfter(now)) {
                        starts.add(pause);
                    }
                }
            }
            waits.keySet().retainAll(pending);
            ends.sort(Comparator.comparing(GameState.Break::deadline).thenComparing(GameState.Break::id));
            starts.sort(Comparator.comparing(GameState.Break::plannedStart).thenComparing(GameState.Break::id));
            List<Work> work = new ArrayList<>();
            ends.forEach(pause -> work.add(new Work(Kind.END, pause.id())));
            starts.forEach(pause -> work.add(new Work(Kind.START, pause.id())));
            return work;
        });
    }

    @Override
    public void run(long sessionId, Work work) throws Exception {
        switch (work.kind()) {
            case END -> end(sessionId, work.breakId());
            case START -> start(sessionId, work.breakId());
            default -> throw new IllegalStateException();
        }
    }

    /** Commits what the operations changed: the session revision and the invalidations. */
    private void changed(GameTransaction transaction, long sessionId, GameOperations operations) throws Exception {
        transaction.bumpSession();
        GameOperations.publish(transaction, publisher, sessionId, operations);
    }

    // Deadlines ---------------------------------------------------------------------------------------------

    private void end(long sessionId, long breakId) throws Exception {
        transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            if (!GameExecutionService.running(transaction, sessionId)) {
                return null;
            }
            GameState.Break pause = GameStateRepository.pause(transaction, sessionId, breakId).orElse(null);
            if (pause == null || pause.protects(transaction.now()) || !pause.active()) {
                return null;
            }
            transaction.lockMemberStates(Set.of(pause.memberId()));
            GameOperations operations = new GameOperations(transaction, sessionId, "Server", null, publisher);
            operations.endBreak(pause);
            changed(transaction, sessionId, operations);
            return null;
        });
    }

    // Planned starts ----------------------------------------------------------------------------------------

    /**
     * Whether a planned break's start was seen in time: the game has run since at most the start grace after it,
     * and the break waited while it ran or the start is no older than the grace.
     */
    static boolean onTime(GameTransaction transaction, long sessionId, GameState.Break pause, Instant now)
            throws SQLException {
        Instant limit = pause.plannedStart().plusSeconds(START_GRACE_SECONDS);
        Instant since = GameExecutionService.runningSince(transaction, sessionId).orElse(null);
        if (since == null || since.isAfter(limit)) {
            return false;
        }
        return GameState.WAITING.equals(pause.state()) || !now.isAfter(limit);
    }

    /**
     * When a break protects its member, as these rules will run it ({@code from} inclusive, {@code until}
     * exclusive); {@code estimated} when its start is not known yet. Used for the timetable's effects
     * ({@link GameTimetableEffects}).
     */
    public record Window(Instant from, Instant until, boolean estimated) {
        public boolean contains(Instant time) {
            return !time.isBefore(from) && time.isBefore(until);
        }
    }

    /**
     * The window of a current break at {@code now}, or null when it protects nobody from now on:
     * <ul>
     * <li>active: from its start to its {@code deadline} (none once the deadline passed);</li>
     * <li>planned, start ahead: from {@code plannedStart} for {@code breakMinutes};</li>
     * <li>planned or waiting, start passed and on time ({@code onTime}, see {@link #onTime}): it starts as soon as it
     * can and lasts its full length, from now, estimated;</li>
     * <li>planned or waiting, start passed late (the game was not running): from now to its planned end, none once
     * that end passed (the break is missed).</li>
     * </ul>
     * A pending break has no window while {@code breakMinutes} is null, because it can't start.
     */
    public static Window window(GameState.Break pause, Double breakMinutes, Instant now, boolean onTime) {
        if (pause.active()) {
            return pause.deadline() != null && pause.deadline().isAfter(now)
                    ? new Window(pause.startedAt(), pause.deadline(), false) : null;
        }
        if (!pause.pending() || breakMinutes == null) {
            return null;
        }
        long seconds = GameSettings.breakSeconds(breakMinutes);
        if (pause.plannedStart().isAfter(now)) {
            return new Window(pause.plannedStart(), pause.plannedStart().plusSeconds(seconds), false);
        }
        if (onTime) {
            return new Window(now, now.plusSeconds(seconds), true);
        }
        Instant plannedEnd = pause.plannedStart().plusSeconds(seconds);
        return plannedEnd.isAfter(now) ? new Window(now, plannedEnd, false) : null;
    }

    private void start(long sessionId, long breakId) throws Exception {
        String[] announced = new String[1];
        boolean[] settled = new boolean[1];
        transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            if (!GameExecutionService.running(transaction, sessionId)) {
                return null;
            }
            Instant now = transaction.now();
            GameState.Break pause = GameStateRepository.pause(transaction, sessionId, breakId).orElse(null);
            if (pause == null || !pause.pending() || pause.plannedStart().isAfter(now)) {
                settled[0] = true;
                return null;
            }
            GameMember member = GameMemberRepository.find(transaction, sessionId, pause.memberId()).orElse(null);
            Double minutes = GameSettings.read(transaction, sessionId).breakMinutes();
            // Retirement and catches release planned breaks themselves; without a duration nothing can start yet.
            if (member == null || !member.active() || minutes == null
                    || GameStateRepository.activeCatch(transaction, sessionId, member.id()).isPresent()) {
                return null;
            }
            transaction.lockMemberStates(Set.of(member.id()));
            GameOperations operations = new GameOperations(transaction, sessionId, "Server", null, publisher);
            long seconds = GameSettings.breakSeconds(minutes);
            boolean late = !onTime(transaction, sessionId, pause, now);
            Instant plannedEnd = pause.plannedStart().plusSeconds(seconds);
            if (late && !plannedEnd.isAfter(now)) {
                operations.missBreak(pause);
                GameBreakEvents.missed(transaction, publisher, sessionId, pause, plannedEnd);
                changed(transaction, sessionId, operations);
                settled[0] = true;
                return null;
            }

            String reason = null;
            String detail = null;
            String blocked = operations.breakBlocked(member);
            if (GameOperations.BREAK_UNAVAILABLE.equals(blocked)) {
                reason = GameBreakEvents.SPEEDHUNT;
            } else if (blocked != null) {
                reason = GameBreakEvents.ON_BREAK;
            } else {
                try {
                    GameAccessRule.checkBreakStart(transaction, sessionId, member.id());
                } catch (WebApplicationException refusal) {
                    reason = GameBreakEvents.REFUSED;
                    detail = refusal.getMessage();
                }
            }
            if (reason != null) {
                // Announced when the break starts waiting and when the reason changes; a wait the runner does not
                // remember (after a restart) goes on silently.
                String key = reason + ":" + Objects.toString(detail, "");
                String previous = waits.get(pause.id());
                boolean planned = GameState.PLANNED.equals(pause.state());
                boolean announce = planned || previous != null && !previous.equals(key);
                operations.waitBreak(pause);
                if (announce) {
                    GameBreakEvents.waiting(transaction, publisher, sessionId, pause, reason, detail);
                }
                if (announce || operations.statusChanged()) {
                    changed(transaction, sessionId, operations);
                }
                announced[0] = key;
                return null;
            }

            Instant deadline = late ? plannedEnd : now.plusSeconds(seconds);
            boolean overAllocated = GameJokerRepository.reservation(transaction, sessionId, pause.reservationId())
                    .map(GameJokerRepository.Reservation::overAllocated).orElse(false);
            int released = operations.startPlannedBreak(pause, deadline);
            GameBreakEvents.started(transaction, publisher, sessionId, pause, now, deadline, late,
                    GameState.WAITING.equals(pause.state()), overAllocated, released);
            changed(transaction, sessionId, operations);
            settled[0] = true;
            return null;
        });
        if (settled[0]) {
            waits.remove(breakId);
        } else if (announced[0] != null) {
            waits.put(breakId, announced[0]);
        }
    }

}
