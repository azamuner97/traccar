/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The server-stored, server-run ping timetable (plan 1.1–1.4). Operators read it ({@link #get}), edit it with
 * all-or-nothing changesets ({@link #changes}) and switch its execution on and off ({@link #setExecution}); the
 * {@link GameTimetableRunner} executes it while the game runs and the toggle is on. Members see their pings without
 * cell content ({@link #pings}, {@link #hunterSlots}).
 *
 * <p>Changeset rules, checked at commit under the session lock (the runner claims slots under the same lock): a
 * slot is editable while it is {@code scheduled} and more than {@value #FREEZE_SECONDS} s ahead (a move's new time
 * too); cells hold valid instructions ({@link TimetableInstruction}), stored trimmed and canonical, and a blank cell
 * is no entry ({@code setCell} with blank content clears the cell, {@code addSlot} leaves it out); cells name
 * active players and supporters listed as participants; slot times are unique; the plan holds at most
 * {@value #MAX_ENTRIES} present entries in slots still to run (done slots' history does not count). An armed fake
 * is released, and its owner told, when its entry is cancelled, its content becomes ineligible, or its slot moves.
 * {@code baseRevision} must be the current revision (409); a repeated {@code changeId} answers the recorded result,
 * or 409 with other content. A rejection lists every failing change (422, {@link Rejected}). Execution never changes
 * the plan revision.
 */
@Singleton
public class GameTimetableService {

    public static final int MAX_ENTRIES = 20000;
    public static final int MAX_CHANGES = 5000;
    /** Slots due within this horizon can't be edited. */
    public static final int FREEZE_SECONDS = 15;
    /** Timetable fakes are armed and offered at least this long before their ping. */
    public static final int FAKE_CUTOFF_SECONDS = 15;
    /** A slot not started this long after its time is missed. */
    public static final int START_GRACE_SECONDS = 30;
    /** A started entry's safety deadline is its slot start plus the wait plus this. */
    public static final int SAFETY_SECONDS = 5;
    /** Past pings members see: from this long ago, at most this many; and at most this many upcoming ones. */
    static final int HISTORY_MINUTES = 60;
    static final int PAST_PINGS = 20;
    static final int UPCOMING_PINGS = 200;

    public static final String ADD_SLOT = "addSlot";
    public static final String MOVE_SLOT = "moveSlot";
    public static final String REMOVE_SLOT = "removeSlot";
    public static final String SET_CELL = "setCell";
    public static final String CLEAR_CELL = "clearCell";
    public static final String ADD_PARTICIPANT = "addParticipant";
    public static final String REMOVE_PARTICIPANT = "removeParticipant";
    public static final String ORDER_PARTICIPANTS = "orderParticipants";

    // Views -------------------------------------------------------------------------------------------------

    /** A column: the member's current name, role, number, state and tracker. */
    public record Participant(long memberId, int position, String displayName, String role, int number,
                              String state, Long trackerId) {
    }

    /**
     * A present cell; {@code fakeReservationId} names the armed fake joker on it, {@code effect} what jokers and
     * breaks do to it (operators only; absent when neither applies, see {@link GameTimetableEffects}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(long id, long memberId, String content, String status, Instant statusAt, String reason,
                        String detail, Long fakeReservationId, GameTimetableEffects.Effect effect) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Slot(long id, Instant scheduledAt, String state, Instant claimedAt, Instant finishedAt,
                       List<Entry> entries) {
    }

    /** Execution: the toggle, the game state, the next slot, the running slot and the latest event ID. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Execution(boolean enabled, Instant enabledChangedAt, String gameState, Instant nextSlotAt,
                            Long currentSlotId, long lastEventId) {
    }

    /** The operators' timetable: plan revision, server time and freeze horizon, execution, columns and rows. */
    public record Timetable(long revision, Instant serverTime, Instant frozenUntil, Execution execution,
                            List<Participant> participants, List<Slot> slots) {
    }

    /**
     * A timetable ping a member may see, without its cell content. Owners also learn whether a fake can still be
     * planned on it ({@code fakeAllowed}).
     */
    public record Ping(long entryId, Instant scheduledAt, long memberId, String state, Instant stateAt,
                       Boolean fakeAllowed) {
    }

    // Changesets --------------------------------------------------------------------------------------------

    /**
     * One change: {@code addSlot} ({@code key}, {@code scheduledAt}, {@code cells} by member ID),
     * {@code moveSlot} ({@code slotId}, {@code scheduledAt}), {@code removeSlot} ({@code slotId}), {@code setCell}
     * ({@code slotId} or {@code key}, {@code memberId}, {@code content}), {@code clearCell} ({@code slotId} or
     * {@code key}, {@code memberId}), {@code addParticipant} ({@code memberId}, optional {@code position}),
     * {@code removeParticipant} ({@code memberId}), {@code orderParticipants} ({@code memberIds}).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Change(String type, String key, Long slotId, String scheduledAt, Map<String, String> cells,
                         Long memberId, String content, Integer position, List<Long> memberIds) {
    }

    public record ChangeSet(String changeId, Long baseRevision, List<Change> changes) {
    }

    /** A failing change: its index, slot or key, member, error code and message. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChangeError(Integer index, Long slotId, String key, Long memberId, String code, String message) {
    }

    /** The result of an applied changeset: the new revision and the slot ID of each added slot's key. */
    public record ChangeResult(String changeId, long revision, Map<String, Long> slots, boolean replayed) {
    }

    /** A rejected changeset: answered with 422 and the failing changes. */
    public static final class Rejected extends RuntimeException {
        private final transient List<ChangeError> errors;

        public Rejected(List<ChangeError> errors) {
            super("The timetable changes were rejected");
            this.errors = List.copyOf(errors);
        }

        public List<ChangeError> errors() {
            return errors;
        }
    }

    private final GameTransactions transactions;
    private final ObjectMapper objectMapper;
    private final GamePublisher publisher;

    @Inject
    public GameTimetableService(GameTransactions transactions, ObjectMapper objectMapper, GamePublisher publisher) {
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.publisher = publisher;
    }

    // Reading -----------------------------------------------------------------------------------------------

    static Execution execution(GameTransaction transaction, long sessionId) throws SQLException {
        var header = GameTimetableRepository.header(transaction, sessionId);
        var next = GameTimetableRepository.nextScheduled(transaction, sessionId);
        var claimed = GameTimetableRepository.slots(transaction, sessionId, GameTimetableRepository.CLAIMED);
        return new Execution(header.enabled(), header.enabledChangedAt(),
                GameExecutionService.read(transaction, sessionId).state(),
                next.map(GameTimetableRepository.Slot::scheduledAt).orElse(null),
                claimed.isEmpty() ? null : claimed.getFirst().id(),
                GameTimetableRepository.lastEventId(transaction, sessionId));
    }

    /**
     * The timetable of a session inside a transaction (also for the operator snapshot), with the joker and break
     * effects of its entries. Joker and break changes never change the plan revision.
     */
    static Timetable read(GameTransaction transaction, long sessionId) throws SQLException {
        Instant now = transaction.now();
        var header = GameTimetableRepository.header(transaction, sessionId);
        List<Participant> participants = new ArrayList<>();
        for (var participant : GameTimetableRepository.participants(transaction, sessionId)) {
            var member = GameMemberRepository.find(transaction, sessionId, participant.memberId()).orElseThrow();
            participants.add(new Participant(member.id(), participant.ordinal(), member.displayName(), member.role(),
                    member.number(), member.state(), member.trackerId()));
        }
        Map<Long, Long> fakes = new HashMap<>();
        for (var plan : GameJokerRepository.plans(transaction, sessionId,
                "p.timetableentryid IS NOT NULL AND " + GameJokerRepository.ARMED)) {
            fakes.put(plan.timetableEntryId(), plan.reservationId());
        }
        GameTimetableEffects.Reader effects = GameTimetableEffects.read(transaction, sessionId);
        Map<Long, List<Entry>> cells = new HashMap<>();
        for (var entry : GameTimetableRepository.present(transaction, sessionId)) {
            cells.computeIfAbsent(entry.slotId(), key -> new ArrayList<>()).add(new Entry(entry.id(),
                    entry.memberId(), entry.content(), entry.status(), entry.statusAt(), entry.reason(),
                    entry.detail(), fakes.get(entry.id()), effects.effect(entry)));
        }
        List<Slot> slots = new ArrayList<>();
        for (var slot : GameTimetableRepository.slots(transaction, sessionId)) {
            slots.add(new Slot(slot.id(), slot.scheduledAt(), slot.state(), slot.claimedAt(), slot.finishedAt(),
                    cells.getOrDefault(slot.id(), List.of())));
        }
        return new Timetable(header.revision(), now, now.plusSeconds(FREEZE_SECONDS), execution(transaction,
                sessionId), participants, slots);
    }

    /** The timetable of a session, for operators; 404 when the session does not exist. */
    public Timetable get(long sessionId) throws Exception {
        return transactions.read(transaction -> {
            GameDocumentService.sessionExists(transaction, sessionId);
            return read(transaction, sessionId);
        });
    }

    // Changes -----------------------------------------------------------------------------------------------

    private String hash(ChangeSet changeSet) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                objectMapper.writeValueAsString(changeSet).getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    /** Shape checks before any transaction. */
    private static void validate(ChangeSet changeSet) {
        if (changeSet == null || changeSet.changeId() == null || !changeSet.changeId().matches("[A-Za-z0-9_-]{8,64}")) {
            throw GameErrors.invalid("Invalid changeId");
        }
        if (changeSet.baseRevision() == null || changeSet.baseRevision() < 0) {
            throw GameErrors.invalid("baseRevision is required");
        }
        if (changeSet.changes() == null || changeSet.changes().size() > MAX_CHANGES) {
            throw GameErrors.invalid("A changeset holds at most " + MAX_CHANGES + " changes");
        }
    }

    /** What applying a changeset collects. */
    private static final class Apply {
        private final GameTransaction transaction;
        private final long sessionId;
        private final Instant now;
        private final List<ChangeError> errors = new ArrayList<>();
        private final Map<String, Long> keys = new LinkedHashMap<>();
        private final Set<Long> owners = new LinkedHashSet<>();
        private final List<Long> participants = new ArrayList<>();
        private int index;
        private Change change;

        Apply(GameTransaction transaction, long sessionId) throws SQLException {
            this.transaction = transaction;
            this.sessionId = sessionId;
            this.now = transaction.now();
            GameTimetableRepository.participants(transaction, sessionId)
                    .forEach(item -> participants.add(item.memberId()));
        }

        void error(Long slotId, Long memberId, String code, String message) {
            errors.add(new ChangeError(index, slotId, change.key(), memberId, code, message));
        }

        Instant time(String value) {
            try {
                return GameTime.parse(value, "scheduledAt");
            } catch (RuntimeException error) {
                return null;
            }
        }

        /** The editability problem of a slot: null when it may change. */
        boolean editable(GameTimetableRepository.Slot slot, Long slotId) {
            String code = switch (slot.state()) {
                case GameTimetableRepository.REMOVED -> "removed";
                case GameTimetableRepository.CLAIMED -> "claimed";
                case GameTimetableRepository.DONE -> "done";
                default -> null;
            };
            if (code != null) {
                error(slotId, null, code, "The row has " + (code.equals("claimed") ? "started" : "been " + code));
                return false;
            }
            if (!slot.scheduledAt().isAfter(now.plusSeconds(FREEZE_SECONDS))) {
                error(slotId, null, "frozen", "The row is due within " + FREEZE_SECONDS + " s and can't change");
                return false;
            }
            return true;
        }

        /** The slot a change names by {@code slotId} or {@code key}, or null after recording the error. */
        GameTimetableRepository.Slot slot() throws SQLException {
            Long id = change.slotId();
            if (id == null && change.key() != null) {
                id = keys.get(change.key());
                if (id == null) {
                    error(null, change.memberId(), "unknownKey", "No row was added with key " + change.key());
                    return null;
                }
            }
            if (id == null) {
                error(null, change.memberId(), "invalidChange", "The change names no row");
                return null;
            }
            var slot = GameTimetableRepository.slot(transaction, sessionId, id);
            if (slot.isEmpty()) {
                error(id, change.memberId(), "unknownSlot", "Row " + id + " not found");
                return null;
            }
            return slot.get();
        }

        boolean timeFree(Instant time, Long slotId) throws SQLException {
            if (GameTimetableRepository.timeTaken(transaction, sessionId, time, slotId)) {
                error(slotId, null, "duplicateTime", "Another row is at " + time);
                return false;
            }
            return true;
        }

        boolean futureTime(Instant time, Long slotId) {
            if (time == null) {
                error(slotId, null, "invalidTime", "Invalid scheduledAt");
                return false;
            }
            if (!time.isAfter(now.plusSeconds(FREEZE_SECONDS))) {
                error(slotId, null, "frozen", "A row must be more than " + FREEZE_SECONDS + " s ahead");
                return false;
            }
            return true;
        }

        /** Whether a member may have cells: an active player or supporter listed as participant. */
        boolean member(Long memberId, Long slotId) throws SQLException {
            if (memberId == null) {
                error(slotId, null, "invalidChange", "The change names no member");
                return false;
            }
            var member = GameMemberRepository.find(transaction, sessionId, memberId);
            if (member.isEmpty() || !GameMember.hasState(member.get().role()) || !member.get().active()) {
                error(slotId, memberId, "inactiveMember", "Member " + memberId
                        + " is not an active player or supporter of this session");
                return false;
            }
            if (!participants.contains(memberId)) {
                error(slotId, memberId, "notParticipant", "Member " + memberId + " is not a timetable column");
                return false;
            }
            return true;
        }

        /**
         * The canonical content of a valid cell ({@code ""} for blank, which is no entry), or null after recording
         * the error.
         */
        String content(String value, Long slotId, Long memberId) {
            if (value == null) {
                error(slotId, memberId, "invalidInstruction", "A cell needs content (blank \"\" clears it)");
                return null;
            }
            TimetableInstruction instruction = TimetableInstruction.parse(value);
            if (!instruction.valid()) {
                error(slotId, memberId, "invalidInstruction", instruction.error());
                return null;
            }
            return instruction.canonical();
        }

        void release(long entryId, String detail) throws SQLException {
            Long owner = releaseFake(transaction, sessionId, entryId, "Server", detail);
            if (owner != null) {
                owners.add(owner);
            }
        }

        void cancel(GameTimetableRepository.Entry entry, String detail) throws SQLException {
            GameTimetableRepository.setStatus(transaction, sessionId, entry.id(),
                    GameTimetableRepository.CANCELLED, null, null);
            release(entry.id(), detail);
        }

        void set(long slotId, long memberId, String content) throws SQLException {
            var existing = GameTimetableRepository.cell(transaction, sessionId, slotId, memberId);
            if (existing.isEmpty()) {
                GameTimetableRepository.insertEntry(transaction, sessionId, slotId, memberId, content);
                return;
            }
            var entry = existing.get();
            if (entry.present() && entry.content().equals(content)) {
                return;
            }
            GameTimetableRepository.setContent(transaction, sessionId, entry.id(), content);
            if (!TimetableInstruction.fakeEligible(content)) {
                release(entry.id(), "Its timetable ping no longer reaches the hunters");
            }
        }

        void apply(Change item) throws SQLException {
            change = item;
            if (item == null || item.type() == null) {
                errors.add(new ChangeError(index, null, null, null, "invalidChange", "Invalid change"));
                return;
            }
            switch (item.type()) {
                case ADD_SLOT -> addSlot();
                case MOVE_SLOT -> moveSlot();
                case REMOVE_SLOT -> removeSlot();
                case SET_CELL -> setCell();
                case CLEAR_CELL -> clearCell();
                case ADD_PARTICIPANT -> addParticipant();
                case REMOVE_PARTICIPANT -> removeParticipant();
                case ORDER_PARTICIPANTS -> orderParticipants();
                default -> error(item.slotId(), item.memberId(), "invalidChange", "Unknown change " + item.type());
            }
        }

        void addSlot() throws SQLException {
            String key = change.key();
            if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,64}") || keys.containsKey(key)) {
                error(null, null, "invalidKey", "An added row needs a unique key");
                return;
            }
            Instant time = time(change.scheduledAt());
            if (!futureTime(time, null) || !timeFree(time, null)) {
                return;
            }
            Map<Long, String> cells = new LinkedHashMap<>();
            boolean valid = true;
            for (var cell : (change.cells() == null ? Map.<String, String>of() : change.cells()).entrySet()) {
                Long memberId;
                try {
                    memberId = Long.parseLong(cell.getKey());
                } catch (NumberFormatException error) {
                    error(null, null, "invalidChange", "Cells are keyed by member ID");
                    valid = false;
                    continue;
                }
                String content = member(memberId, null) ? content(cell.getValue(), null, memberId) : null;
                valid &= content != null;
                if (content != null && !content.isEmpty()) {
                    // A blank cell is no entry.
                    cells.put(memberId, content);
                }
            }
            if (!valid) {
                return;
            }
            long slotId = GameTimetableRepository.insertSlot(transaction, sessionId, time);
            keys.put(key, slotId);
            for (var cell : cells.entrySet()) {
                GameTimetableRepository.insertEntry(transaction, sessionId, slotId, cell.getKey(), cell.getValue());
            }
        }

        void moveSlot() throws SQLException {
            var slot = slot();
            if (slot == null || !editable(slot, slot.id())) {
                return;
            }
            Instant time = time(change.scheduledAt());
            if (!futureTime(time, slot.id()) || !timeFree(time, slot.id())) {
                return;
            }
            if (time.equals(slot.scheduledAt())) {
                return;
            }
            GameTimetableRepository.moveSlot(transaction, sessionId, slot.id(), time);
            for (var entry : GameTimetableRepository.entries(transaction, sessionId, slot.id())) {
                release(entry.id(), "Its timetable ping was moved");
            }
        }

        void removeSlot() throws SQLException {
            var slot = slot();
            if (slot == null || !editable(slot, slot.id())) {
                return;
            }
            for (var entry : GameTimetableRepository.entries(transaction, sessionId, slot.id())) {
                if (entry.present()) {
                    cancel(entry, "Its timetable ping was removed");
                }
            }
            GameTimetableRepository.removeSlot(transaction, sessionId, slot.id());
        }

        void setCell() throws SQLException {
            var slot = slot();
            if (slot == null || !editable(slot, slot.id()) || !member(change.memberId(), slot.id())) {
                return;
            }
            String content = content(change.content(), slot.id(), change.memberId());
            if (content == null) {
                return;
            }
            if (content.isEmpty()) {
                // A blank cell is no entry: like clearCell.
                clear(slot.id(), change.memberId());
            } else {
                set(slot.id(), change.memberId(), content);
            }
        }

        void clear(long slotId, long memberId) throws SQLException {
            var entry = GameTimetableRepository.cell(transaction, sessionId, slotId, memberId);
            if (entry.isPresent() && entry.get().present()) {
                cancel(entry.get(), "Its timetable ping was removed");
            }
        }

        void clearCell() throws SQLException {
            var slot = slot();
            if (slot == null || !editable(slot, slot.id())) {
                return;
            }
            if (change.memberId() == null) {
                error(slot.id(), null, "invalidChange", "The change names no member");
                return;
            }
            clear(slot.id(), change.memberId());
        }

        void addParticipant() throws SQLException {
            Long memberId = change.memberId();
            if (memberId == null) {
                error(null, null, "invalidChange", "The change names no member");
                return;
            }
            var member = GameMemberRepository.find(transaction, sessionId, memberId);
            if (member.isEmpty() || !GameMember.hasState(member.get().role()) || !member.get().active()) {
                error(null, memberId, "inactiveMember", "Member " + memberId
                        + " is not an active player or supporter of this session");
                return;
            }
            if (participants.contains(memberId)) {
                error(null, memberId, "duplicateParticipant", "Member " + memberId + " is already a column");
                return;
            }
            int position = change.position() == null ? participants.size()
                    : Math.max(0, Math.min(change.position(), participants.size()));
            participants.add(position, memberId);
            GameTimetableRepository.order(transaction, sessionId, participants);
        }

        void removeParticipant() throws SQLException {
            Long memberId = change.memberId();
            if (memberId == null || !participants.contains(memberId)) {
                error(null, memberId, "notParticipant", "Member " + memberId + " is not a timetable column");
                return;
            }
            boolean history = false;
            for (var entry : GameTimetableRepository.ofMember(transaction, sessionId, memberId)) {
                if (editableEntry(entry, now)) {
                    cancel(entry, "Its timetable ping was removed");
                } else {
                    history = true;
                }
            }
            if (!history) {
                participants.remove(memberId);
                GameTimetableRepository.order(transaction, sessionId, participants);
            }
        }

        void orderParticipants() throws SQLException {
            List<Long> order = change.memberIds();
            if (order == null || order.size() != participants.size() || !new HashSet<>(order).equals(
                    new HashSet<>(participants)) || new HashSet<>(order).size() != order.size()) {
                error(null, null, "invalidOrder", "The order must name every column once");
                return;
            }
            participants.clear();
            participants.addAll(order);
            GameTimetableRepository.order(transaction, sessionId, participants);
        }
    }

    /** An entry that edits may still change: open, in a scheduled slot more than the freeze horizon ahead. */
    static boolean editableEntry(GameTimetableRepository.Entry entry, Instant now) {
        return entry.open() && GameTimetableRepository.SCHEDULED.equals(entry.slotState())
                && entry.scheduledAt().isAfter(now.plusSeconds(FREEZE_SECONDS));
    }

    /**
     * Applies a changeset all-or-nothing. A repeated {@code changeId} with the same content answers the recorded
     * result; with other content 409. {@code baseRevision} must be the current revision (409). Failing changes
     * reject the whole changeset ({@link Rejected}).
     */
    public ChangeResult changes(long sessionId, ChangeSet changeSet) throws Exception {
        validate(changeSet);
        String hash = hash(changeSet);
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            var recorded = GameTimetableRepository.change(transaction, sessionId, changeSet.changeId());
            if (recorded.isPresent()) {
                if (!recorded.get().hash().equals(hash)) {
                    throw GameErrors.conflict("changeId was already used for different changes");
                }
                ChangeResult result = objectMapper.readValue(recorded.get().result(), ChangeResult.class);
                return new ChangeResult(result.changeId(), result.revision(), result.slots(), true);
            }
            long current = GameTimetableRepository.header(transaction, sessionId).revision();
            if (changeSet.baseRevision() != current) {
                throw GameErrors.revisionConflict("Timetable", current);
            }
            Apply apply = new Apply(transaction, sessionId);
            List<Change> changes = changeSet.changes();
            for (int i = 0; i < changes.size(); i++) {
                apply.index = i;
                apply.apply(changes.get(i));
            }
            if (apply.errors.isEmpty()
                    && GameTimetableRepository.activeCount(transaction, sessionId) > MAX_ENTRIES) {
                apply.errors.add(new ChangeError(null, null, null, null, "tooManyEntries",
                        "A timetable holds at most " + MAX_ENTRIES + " cells still to run"));
            }
            if (!apply.errors.isEmpty()) {
                throw new Rejected(apply.errors);
            }
            long revision = changes.isEmpty() ? current : GameTimetableRepository.bumpRevision(transaction, sessionId);
            ChangeResult result = new ChangeResult(changeSet.changeId(), revision, apply.keys, false);
            GameTimetableRepository.recordChange(transaction, sessionId, changeSet.changeId(), hash,
                    objectMapper.writeValueAsString(result));
            if (!changes.isEmpty()) {
                transaction.bumpSession();
                changed(transaction, publisher, sessionId, apply.owners);
            }
            return result;
        });
    }

    /** After commit: members read their status again, whose {@code timetableRunning} follows the toggle. */
    static void executionChanged(GameTransaction transaction, GamePublisher publisher, long sessionId) {
        transaction.afterCommit(() -> publisher.invalidate(
                new GamePublisher.Invalidation(sessionId, GamePublisher.STATUS, null)));
    }

    /** After commit: members read their timetable again, fake owners their jokers. */
    static void changed(GameTransaction transaction, GamePublisher publisher, long sessionId, Set<Long> owners) {
        transaction.afterCommit(() -> publisher.invalidate(
                new GamePublisher.Invalidation(sessionId, GamePublisher.TIMETABLE, null)));
        if (!owners.isEmpty()) {
            Set<Long> recipients = Set.copyOf(owners);
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(sessionId, GamePublisher.JOKERS, recipients)));
        }
    }

    // Execution toggle --------------------------------------------------------------------------------------

    /**
     * Switches timetable execution on or off. Off lets entries already being written finish; entries still waiting
     * for a fresh fix are skipped ({@code stopped}). Writes an {@code execution} event.
     */
    public Execution setExecution(long sessionId, boolean enabled, long userId) throws Exception {
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            var header = GameTimetableRepository.header(transaction, sessionId);
            if (header.enabled() == enabled) {
                return execution(transaction, sessionId);
            }
            GameTimetableRepository.setEnabled(transaction, sessionId, enabled);
            Set<Long> owners = new LinkedHashSet<>();
            if (!enabled) {
                stopWaiting(transaction, publisher, sessionId, owners);
            }
            GameTimetableEvents.execution(transaction, publisher, sessionId, enabled ? "on" : "off",
                    GameExecutionService.userLabel(transaction, userId));
            transaction.bumpSession();
            changed(transaction, publisher, sessionId, owners);
            executionChanged(transaction, publisher, sessionId);
            return execution(transaction, sessionId);
        });
    }

    // Shared rules ------------------------------------------------------------------------------------------

    /** Releases the armed fake planned on an entry; returns its owner, or null when none was armed. */
    static Long releaseFake(GameTransaction transaction, long sessionId, long entryId, String actor, String detail)
            throws SQLException {
        Long owner = null;
        for (var plan : GameJokerRepository.plans(transaction, sessionId, "p.timetableentryid = ? AND "
                + GameJokerRepository.ARMED, entryId)) {
            GameJokerRepository.release(transaction,
                    GameJokerRepository.reservation(transaction, sessionId, plan.reservationId()).orElseThrow(),
                    actor, detail);
            owner = plan.memberId();
        }
        return owner;
    }

    /**
     * Skips ({@code stopped}) the open entries of claimed slots, which wait for a fresh fix or are about to be sent,
     * releases their fakes, writes their {@code ping} events and finishes their slots: the game stopped or the
     * toggle went off. An engine transaction that comes later finds them no longer open and writes nothing.
     *
     * @return the number of entries skipped
     */
    static int stopWaiting(GameTransaction transaction, GamePublisher publisher, long sessionId, Set<Long> owners)
            throws SQLException {
        int stopped = 0;
        for (var slot : GameTimetableRepository.slots(transaction, sessionId, GameTimetableRepository.CLAIMED)) {
            for (var entry : GameTimetableRepository.entries(transaction, sessionId, slot.id())) {
                if (!entry.open()) {
                    continue;
                }
                GameTimetableRepository.setStatus(transaction, sessionId, entry.id(),
                        GameTimetableRepository.SKIPPED, GameTimetableEvents.STOPPED, null);
                Long owner = releaseFake(transaction, sessionId, entry.id(), "Server",
                        "Its timetable ping was not sent");
                if (owner != null) {
                    owners.add(owner);
                }
                GameTimetableEvents.ping(transaction, publisher, sessionId, entry, GameTimetableRepository.SKIPPED,
                        GameTimetableEvents.STOPPED, null, null);
                stopped++;
            }
            GameTimetableRepository.finish(transaction, sessionId, slot.id());
            GameTimetableEvents.slotFinished(transaction, publisher, sessionId, slot);
        }
        return stopped;
    }

    /**
     * A retiring member's editable entries are cancelled and their fakes released; the column stays, so the
     * member's history remains visible. The plan revision advances when a cell changed.
     */
    static void memberRetired(GameTransaction transaction, GamePublisher publisher, long sessionId, long memberId)
            throws SQLException {
        Set<Long> owners = new LinkedHashSet<>();
        boolean changed = false;
        for (var entry : GameTimetableRepository.ofMember(transaction, sessionId, memberId)) {
            if (editableEntry(entry, transaction.now())) {
                GameTimetableRepository.setStatus(transaction, sessionId, entry.id(),
                        GameTimetableRepository.CANCELLED, null, null);
                Long owner = releaseFake(transaction, sessionId, entry.id(), "Server",
                        "Its owner left the timetable");
                if (owner != null) {
                    owners.add(owner);
                }
                changed = true;
            }
        }
        if (changed) {
            GameTimetableRepository.bumpRevision(transaction, sessionId);
            changed(transaction, publisher, sessionId, owners);
        }
    }

    // Member views ------------------------------------------------------------------------------------------

    /**
     * The timetable pings a member sees: players and supporters their own pings that can carry a fake, spectators
     * every ping that reaches hunters. The cell content is never included, so operator coordinates and fakes look
     * like real pings. Past pings of the last hour come first (at most {@value #PAST_PINGS}), then upcoming ones.
     * A claimed slot's entries read {@code scheduled} until their outcome.
     */
    public List<Ping> pings(GameMember viewer) throws Exception {
        boolean owner = GameMember.hasState(viewer.role());
        if (!owner && !GameMember.SPECTATOR.equals(viewer.role())) {
            throw new jakarta.ws.rs.ForbiddenException(
                    "The timetable is shown to players, supporters, hunters and spectators");
        }
        return transactions.read(transaction -> pings(transaction, viewer.gameSessionId(),
                owner ? viewer.id() : null, false));
    }

    /** Hunters see only the distinct times at which at least one ping reaches them. */
    public List<Instant> hunterSlots(long sessionId) throws Exception {
        return transactions.read(transaction -> pings(transaction, sessionId, null, true).stream()
                .map(Ping::scheduledAt).toList());
    }

    private static List<Ping> pings(GameTransaction transaction, long sessionId, Long ownMember,
                                    boolean distinctTimes) throws SQLException {
        Instant now = transaction.now();
        Set<Long> taken = ownMember == null ? Set.of()
                : new HashSet<>(GameJokerRepository.openFakeEntries(transaction, sessionId, ownMember));
        List<Ping> result = window(transaction, sessionId, ownMember, now, true, taken, distinctTimes);
        Collections.reverse(result);
        result.addAll(window(transaction, sessionId, ownMember, now, false, taken, distinctTimes));
        return result;
    }

    private static List<Ping> window(GameTransaction transaction, long sessionId, Long ownMember, Instant now,
                                     boolean past, Set<Long> taken, boolean distinctTimes) throws SQLException {
        int limit = past ? PAST_PINGS : UPCOMING_PINGS;
        Instant cutoff = now.plusSeconds(FAKE_CUTOFF_SECONDS);
        List<Ping> result = new ArrayList<>();
        Instant previous = null;
        for (var row : GameTimetableRepository.window(transaction, sessionId, ownMember,
                now.minusSeconds(HISTORY_MINUTES * 60L), now, past)) {
            if (result.size() >= limit) {
                break;
            }
            TimetableInstruction instruction = TimetableInstruction.parse(row.content());
            boolean visible = ownMember != null ? instruction.fakeEligible() : instruction.hunterFacing();
            if (!visible || distinctTimes && Objects.equals(row.scheduledAt(), previous)) {
                continue;
            }
            previous = row.scheduledAt();
            Boolean fakeAllowed = ownMember == null ? null
                    : row.scheduledAt().isAfter(cutoff) && row.open()
                            && GameTimetableRepository.SCHEDULED.equals(row.slotState()) && !taken.contains(row.id());
            result.add(new Ping(row.id(), row.scheduledAt(), row.memberId(), row.status(), row.statusAt(),
                    fakeAllowed));
        }
        return result;
    }

}
