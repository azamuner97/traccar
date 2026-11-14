/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the timetable of the running session while its toggle is on (plan 1.5), in {@link TaskGameExecution}:
 * <ol>
 * <li><b>Missed slots.</b> A scheduled slot more than {@value GameTimetableService#START_GRACE_SECONDS} s past due
 * becomes {@code done}; its open entries are skipped ({@code missed}) and their fakes released
 * ({@code slotMissed}).</li>
 * <li><b>Interrupted slots.</b> Open entries of a claimed slot that no wait or transaction is working on (a
 * restart) were never delivered, because outcomes commit with their positions. They run again while the slot is
 * within its start grace, and are skipped ({@code missed}) after it.</li>
 * <li><b>Due slot.</b> The earliest due slot is claimed under the session lock ({@code slotStarted}). {@code x} is
 * skipped ({@code skip}), an invalid cell ({@code invalid}); every other entry becomes one engine intent with its
 * audiences, fake and armed joker fake, {@code minimumFixTime} = slot time − freshness window and the fresh-fix
 * wait, and {@code notAfter} = slot start + wait + {@value GameTimetableService#SAFETY_SECONDS} s. The entries wait
 * in parallel, each sent as soon as its own fix is fresh. Each outcome becomes the entry status and a {@code ping}
 * event.</li>
 * <li><b>Finishing.</b> A slot becomes {@code done} when every entry is terminal ({@code slotFinished}); with no
 * scheduled slot left the toggle switches off ({@code completed}).</li>
 * </ol>
 */
@Singleton
public class GameTimetableRunner implements GameExecutionRunner<GameTimetableRunner.Work> {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameTimetableRunner.class);

    public enum Kind { MISSED, RESUME, START, FINISH, COMPLETE }

    /** One step of the timetable: the kind and its slot (none for {@code COMPLETE}). */
    public record Work(Kind kind, long slotId) {
    }

    private final GameTransactions transactions;
    private final GamePublisher publisher;
    private final GamePingEngine engine;
    /** Entries an engine wait or transaction is working on. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    @Inject
    public GameTimetableRunner(GameTransactions transactions, GamePublisher publisher, GamePingEngine engine) {
        this.transactions = transactions;
        this.publisher = publisher;
        this.engine = engine;
    }

    /** Entries in flight, for tests. */
    Set<Long> inFlight() {
        return Set.copyOf(inFlight);
    }

    private static boolean past(Instant scheduledAt, Instant now) {
        return now.isAfter(scheduledAt.plusSeconds(GameTimetableService.START_GRACE_SECONDS));
    }

    @Override
    public List<Work> due(long sessionId, Instant now) throws Exception {
        return transactions.read(transaction -> {
            if (!GameTimetableRepository.header(transaction, sessionId).enabled()
                    || !GameExecutionService.running(transaction, sessionId)) {
                return List.of();
            }
            List<Work> work = new ArrayList<>();
            boolean pending = false;
            Work start = null;
            for (var slot : GameTimetableRepository.slots(transaction, sessionId,
                    GameTimetableRepository.SCHEDULED)) {
                if (past(slot.scheduledAt(), now)) {
                    work.add(new Work(Kind.MISSED, slot.id()));
                } else {
                    pending = true;
                    if (start == null && !slot.scheduledAt().isAfter(now)) {
                        start = new Work(Kind.START, slot.id());
                    }
                }
            }
            for (var slot : GameTimetableRepository.slots(transaction, sessionId,
                    GameTimetableRepository.CLAIMED)) {
                pending = true;
                List<GameTimetableRepository.Entry> open = GameTimetableRepository.entries(transaction, sessionId,
                        slot.id()).stream().filter(GameTimetableRepository.Entry::open).toList();
                if (open.isEmpty()) {
                    work.add(new Work(Kind.FINISH, slot.id()));
                } else if (open.stream().anyMatch(entry -> !inFlight.contains(entry.id()))) {
                    work.add(new Work(Kind.RESUME, slot.id()));
                }
            }
            if (start != null) {
                work.add(start);
            }
            if (!pending) {
                work.add(new Work(Kind.COMPLETE, 0));
            }
            return work;
        });
    }

    @Override
    public void run(long sessionId, Work work) throws Exception {
        switch (work.kind()) {
            case MISSED -> missed(sessionId, work.slotId());
            case RESUME -> start(sessionId, resume(sessionId, work.slotId()));
            case START -> start(sessionId, claim(sessionId, work.slotId()));
            case FINISH -> transactions.write(transaction -> {
                transaction.lockSession(sessionId);
                finishIfDone(transaction, sessionId, work.slotId());
                return null;
            });
            case COMPLETE -> complete(sessionId);
            default -> throw new IllegalStateException();
        }
    }

    // Steps -------------------------------------------------------------------------------------------------

    /** Entries to send and when their slot started. */
    private record Started(List<GameTimetableRepository.Entry> entries, Instant startedAt) {
        static final Started NONE = new Started(List.of(), null);
    }

    private void missed(long sessionId, long slotId) throws Exception {
        transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            var slot = GameTimetableRepository.slot(transaction, sessionId, slotId).orElseThrow();
            if (!slot.scheduled() || !past(slot.scheduledAt(), transaction.now())) {
                return null;
            }
            Set<Long> owners = new LinkedHashSet<>();
            int count = 0;
            for (var entry : GameTimetableRepository.entries(transaction, sessionId, slotId)) {
                if (entry.present() && entry.open()) {
                    skip(transaction, sessionId, entry, GamePingEngine.MISSED, owners, false);
                    count++;
                }
            }
            GameTimetableRepository.finish(transaction, sessionId, slotId);
            GameTimetableEvents.slotMissed(transaction, publisher, sessionId, slotId, slot.scheduledAt(), count);
            transaction.bumpSession();
            GameTimetableService.changed(transaction, publisher, sessionId, owners);
            return null;
        });
    }

    /** Skips an entry that is never sent; optionally with its {@code ping} event. */
    private void skip(GameTransaction transaction, long sessionId, GameTimetableRepository.Entry entry,
                      String reason, Set<Long> owners, boolean event) throws SQLException {
        GameTimetableRepository.setStatus(transaction, sessionId, entry.id(), GameTimetableRepository.SKIPPED,
                reason, null);
        Long owner = GameTimetableService.releaseFake(transaction, sessionId, entry.id(), "Server",
                "Its timetable ping was not sent");
        if (owner != null) {
            owners.add(owner);
        }
        if (event) {
            GameTimetableEvents.ping(transaction, publisher, sessionId, entry, GameTimetableRepository.SKIPPED,
                    reason, null, null);
        }
    }

    private boolean gate(GameTransaction transaction, long sessionId) throws SQLException {
        return GameTimetableRepository.header(transaction, sessionId).enabled()
                && GameExecutionService.running(transaction, sessionId);
    }

    /** Claims a due slot and settles the entries that send nothing; returns the ones to send. */
    private Started claim(long sessionId, long slotId) throws Exception {
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            var slot = GameTimetableRepository.slot(transaction, sessionId, slotId).orElseThrow();
            Instant now = transaction.now();
            if (!gate(transaction, sessionId) || !slot.scheduled() || slot.scheduledAt().isAfter(now)
                    || past(slot.scheduledAt(), now)
                    || !GameTimetableRepository.claim(transaction, sessionId, slotId)) {
                return Started.NONE;
            }
            List<GameTimetableRepository.Entry> entries = GameTimetableRepository.entries(transaction, sessionId,
                    slotId).stream().filter(entry -> entry.present() && entry.open()).toList();
            GameTimetableEvents.slotStarted(transaction, publisher, sessionId, slotId, slot.scheduledAt(),
                    entries.size());
            Set<Long> owners = new LinkedHashSet<>();
            List<GameTimetableRepository.Entry> send = new ArrayList<>();
            for (var entry : entries) {
                TimetableInstruction instruction = TimetableInstruction.parse(entry.content());
                if (instruction.skip() || !instruction.valid()) {
                    skip(transaction, sessionId, entry, instruction.skip() ? GameTimetableEvents.SKIP
                            : GameTimetableEvents.INVALID, owners, true);
                    continue;
                }
                if (!instruction.fakeEligible()) {
                    Long owner = GameTimetableService.releaseFake(transaction, sessionId, entry.id(), "Server",
                            "Its timetable ping no longer reaches the hunters");
                    if (owner != null) {
                        owners.add(owner);
                    }
                }
                send.add(entry);
            }
            finishIfDone(transaction, sessionId, slotId);
            transaction.bumpSession();
            GameTimetableService.changed(transaction, publisher, sessionId, owners);
            return new Started(send, now);
        });
    }

    /** The open entries of an interrupted slot: run again within the start grace, missed after it. */
    private Started resume(long sessionId, long slotId) throws Exception {
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            var slot = GameTimetableRepository.slot(transaction, sessionId, slotId).orElseThrow();
            if (!GameTimetableRepository.CLAIMED.equals(slot.state()) || !gate(transaction, sessionId)) {
                return Started.NONE;
            }
            boolean late = past(slot.scheduledAt(), transaction.now());
            Set<Long> owners = new LinkedHashSet<>();
            List<GameTimetableRepository.Entry> send = new ArrayList<>();
            for (var entry : GameTimetableRepository.entries(transaction, sessionId, slotId)) {
                if (!entry.open() || inFlight.contains(entry.id())) {
                    continue;
                }
                if (late) {
                    skip(transaction, sessionId, entry, GamePingEngine.MISSED, owners, true);
                } else {
                    send.add(entry);
                }
            }
            finishIfDone(transaction, sessionId, slotId);
            if (late) {
                transaction.bumpSession();
                GameTimetableService.changed(transaction, publisher, sessionId, owners);
            }
            return new Started(send, transaction.now());
        });
    }

    /** Finishes a claimed slot whose entries are all terminal. */
    private void finishIfDone(GameTransaction transaction, long sessionId, long slotId) throws SQLException {
        var slot = GameTimetableRepository.slot(transaction, sessionId, slotId).orElseThrow();
        if (!GameTimetableRepository.CLAIMED.equals(slot.state())) {
            return;
        }
        boolean open = GameTimetableRepository.entries(transaction, sessionId, slotId).stream()
                .anyMatch(entry -> entry.present() && entry.open());
        if (!open) {
            GameTimetableRepository.finish(transaction, sessionId, slotId);
            GameTimetableEvents.slotFinished(transaction, publisher, sessionId, slot);
        }
    }

    private void complete(long sessionId) throws Exception {
        transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            if (!GameTimetableRepository.header(transaction, sessionId).enabled()
                    || GameTimetableRepository.nextScheduled(transaction, sessionId).isPresent()
                    || !GameTimetableRepository.slots(transaction, sessionId, GameTimetableRepository.CLAIMED)
                            .isEmpty()) {
                return null;
            }
            GameTimetableRepository.setEnabled(transaction, sessionId, false);
            GameTimetableEvents.execution(transaction, publisher, sessionId, "completed", null);
            transaction.bumpSession();
            return null;
        });
    }

    // Sending -----------------------------------------------------------------------------------------------

    private void start(long sessionId, Started started) throws Exception {
        if (started.entries().isEmpty()) {
            return;
        }
        GameSettings settings = transactions.read(transaction -> GameSettings.read(transaction, sessionId));
        int wait = settings.timetableWaitSeconds();
        int window = settings.timetableFreshnessSeconds();
        for (var entry : started.entries()) {
            if (!inFlight.add(entry.id())) {
                continue;
            }
            GamePingEngine.Intent intent;
            GamePingEngine.Context context;
            try {
                TimetableInstruction instruction = TimetableInstruction.parse(entry.content());
                GamePingEngine.Fake fake = instruction.kind() == TimetableInstruction.Kind.COORDINATE
                        ? new GamePingEngine.Fake(instruction.latitude(), instruction.longitude(),
                                instruction.fakeAudiences()) : null;
                intent = new GamePingEngine.Intent(GamePingEngine.TIMETABLE, entry.memberId(), null,
                        instruction.audiences(), fake,
                        new GamePingEngine.Freshness(entry.scheduledAt().minusSeconds(window).toString(), wait),
                        started.startedAt().plusSeconds(wait + GameTimetableService.SAFETY_SECONDS).toString(),
                        null, null);
                context = new GamePingEngine.Context(entry.scheduledAt(),
                        instruction.fakeEligible() ? jokerFake(sessionId, entry.id()) : null);
            } catch (Exception error) {
                inFlight.remove(entry.id());
                throw error;
            }
            engine.execute(sessionId, intent, context, hooks(sessionId, entry))
                    .whenComplete((outcome, error) -> {
                        inFlight.remove(entry.id());
                        if (error != null) {
                            failed(sessionId, entry, error);
                        }
                    });
        }
    }

    private GamePingEngine.JokerFake jokerFake(long sessionId, long entryId) throws Exception {
        return transactions.read(transaction -> GameJokerRepository.plans(transaction, sessionId,
                "p.timetableentryid = ? AND " + GameJokerRepository.ARMED, entryId).stream().findFirst()
                .map(plan -> new GamePingEngine.JokerFake(plan.reservationId(), plan.latitude(), plan.longitude()))
                .orElse(null));
    }

    private GamePingEngine.Hooks hooks(long sessionId, GameTimetableRepository.Entry entry) {
        return new GamePingEngine.Hooks() {
            @Override
            public boolean proceed(GameTransaction transaction) throws Exception {
                return GameTimetableRepository.find(transaction, sessionId, entry.id())
                        .map(GameTimetableRepository.Entry::open).orElse(false);
            }

            @Override
            public void completed(GameTransaction transaction, GamePingEngine.Outcome outcome) throws Exception {
                GameTimetableRepository.setStatus(transaction, sessionId, entry.id(), outcome.status(),
                        outcome.reason(), outcome.detail());
                GameTimetableEvents.ping(transaction, publisher, sessionId, entry, outcome.status(),
                        outcome.reason(), outcome.detail(), outcome);
                finishIfDone(transaction, sessionId, entry.slotId());
                GameTimetableService.changed(transaction, publisher, sessionId, outcome.jokerFake() == null
                        ? Set.of() : Set.of(entry.memberId()));
            }

            @Override
            public boolean wanted() {
                try {
                    return transactions.read(transaction -> GameTimetableRepository.find(transaction, sessionId,
                            entry.id()).map(GameTimetableRepository.Entry::open).orElse(false));
                } catch (Exception error) {
                    return true;
                }
            }
        };
    }

    /** An engine failure (not a game rule): the entry fails with the error, so it is not retried forever. */
    private void failed(long sessionId, GameTimetableRepository.Entry entry, Throwable error) {
        LOGGER.warn("Timetable ping of entry {} failed", entry.id(), error);
        try {
            transactions.write(transaction -> {
                transaction.lockSession(sessionId);
                var current = GameTimetableRepository.find(transaction, sessionId, entry.id());
                if (current.isEmpty() || !current.get().open()) {
                    return null;
                }
                String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                GameTimetableRepository.setStatus(transaction, sessionId, entry.id(), GameTimetableRepository.FAILED,
                        GameTimetableEvents.ERROR, detail);
                Set<Long> owners = new LinkedHashSet<>();
                Long owner = GameTimetableService.releaseFake(transaction, sessionId, entry.id(), "Server",
                        "Its timetable ping could not be sent");
                if (owner != null) {
                    owners.add(owner);
                }
                GameTimetableEvents.ping(transaction, publisher, sessionId, current.get(),
                        GameTimetableRepository.FAILED, GameTimetableEvents.ERROR, detail, null);
                finishIfDone(transaction, sessionId, entry.slotId());
                transaction.bumpSession();
                GameTimetableService.changed(transaction, publisher, sessionId, owners);
                return null;
            });
        } catch (Exception failure) {
            LOGGER.warn("Timetable entry {} could not be marked failed", entry.id(), failure);
        }
    }

}
