/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The published ping timetable of a session (model-1 semantics, with entries that reference members).
 *
 * <p>The Tool publishes the parsed workbook: {@code columns} are its headers, the first one being the time column
 * and every other one naming a participant. Publishing resolves each participant header to an active player or
 * supporter of the session, by display name, or else by the name of the member's tracker, and refuses headers that
 * name nobody or more than one member. Each entry is one participant cell of one timed row, identified by its
 * scheduled time and member:
 * <ul>
 * <li>an entry whose content changed keeps its ID;</li>
 * <li>a replacement keeps the ID and progress of every entry with the same time and member;</li>
 * <li>entries that disappeared are deactivated, and armed fakes on them are released.</li>
 * </ul>
 */
@Singleton
public class GameTimetableService {

    public static final int MAX_ENTRIES = 20000;
    public static final String SCHEDULED = "scheduled";
    public static final Set<String> STATUSES = Set.of(SCHEDULED, "sent", "skipped", "failed");
    /** Timetable fakes are armed and offered at least this long before their ping. */
    public static final int FAKE_CUTOFF_SECONDS = 60;
    /**
     * Cells a fake can replace: blank (every audience) and the target codes that reach hunters. The Tool parses the
     * same codes; {@code b}, {@code x}, operator coordinates and codes without hunters are not eligible.
     */
    public static final Set<String> FAKE_CONTENT = Set.of("", "2H", "2P2H", "2S2H", "2P2S2H");
    /** Past pings members see: from this long ago, at most this many; and at most this many upcoming ones. */
    static final int HISTORY_MINUTES = 60;
    static final int PAST_PINGS = 20;
    static final int UPCOMING_PINGS = 200;

    public record EntryInput(String scheduledAt, String participant, String content) {
    }

    public record Progress(long id, String status) {
    }

    public record Entry(long id, Instant scheduledAt, long memberId, String participant, String content,
                        String status, Instant statusAt) {
    }

    public record Timetable(long revision, JsonNode columns, JsonNode source, List<Entry> entries) {
    }

    public record Published(long revision, boolean structureChanged, List<Entry> entries) {
    }

    /**
     * A timetable ping a member may see, without its cell content. Owners also learn whether a fake can still be
     * planned on it ({@code fakeAllowed}).
     */
    public record Ping(long entryId, Instant scheduledAt, long memberId, String state, Instant stateAt,
                       Boolean fakeAllowed) {
    }

    private record Key(Instant scheduledAt, long memberId) {
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

    public static boolean fakeEligible(String content) {
        return content != null && FAKE_CONTENT.contains(content.trim());
    }

    /**
     * Whether hunters receive a position from a cell: the real one (blank or a target code with hunters) or, for an
     * operator coordinate, either its fake or the real position (the complement of its code).
     */
    public static boolean hunterFacing(String content) {
        return fakeEligible(content) || content != null && content.contains(",");
    }

    // Reading -----------------------------------------------------------------------------------------------

    /** The stored headers document: the columns and the member each participant column resolved to. */
    private ObjectNode headers(GameTimetableRepository.Header header) throws Exception {
        return headers(objectMapper, header);
    }

    private static ObjectNode headers(ObjectMapper objectMapper, GameTimetableRepository.Header header)
            throws Exception {
        JsonNode node = objectMapper.readTree(header.headers());
        return node.isObject() ? (ObjectNode) node : JsonNodeFactory.instance.objectNode();
    }

    private static Map<Long, String> participants(ObjectNode headers) {
        Map<Long, String> result = new HashMap<>();
        JsonNode columns = headers.path("columns");
        JsonNode members = headers.path("memberIds");
        for (int i = 1; i < columns.size(); i++) {
            if (members.path(i).canConvertToLong()) {
                result.put(members.get(i).asLong(), columns.get(i).asText());
            }
        }
        return result;
    }

    private Timetable read(GameTransaction transaction, long sessionId) throws Exception {
        return read(transaction, objectMapper, sessionId);
    }

    /** The published version with its active entries, inside a transaction (also for the operator snapshot). */
    static Timetable read(GameTransaction transaction, ObjectMapper objectMapper, long sessionId) throws Exception {
        var header = GameTimetableRepository.header(transaction, sessionId);
        if (header.isEmpty()) {
            return new Timetable(0, JsonNodeFactory.instance.arrayNode(), JsonNodeFactory.instance.objectNode(),
                    List.of());
        }
        ObjectNode headers = headers(objectMapper, header.get());
        Map<Long, String> names = participants(headers);
        List<Entry> entries = new ArrayList<>();
        for (var row : GameTimetableRepository.active(transaction, sessionId)) {
            entries.add(new Entry(row.id(), row.scheduledAt(), row.memberId(), names.get(row.memberId()),
                    row.content(), row.status(), row.statusAt()));
        }
        return new Timetable(header.get().revision(), headers.path("columns"),
                objectMapper.readTree(header.get().source()), entries);
    }

    /** The published version with its active entries, for operators. */
    public Timetable get(long sessionId) throws Exception {
        return transactions.read(transaction -> {
            GameDocumentService.sessionExists(transaction, sessionId);
            return read(transaction, sessionId);
        });
    }

    // Publishing --------------------------------------------------------------------------------------------

    /** Resolves participant headers to members: by display name, or else by the name of the bound tracker. */
    private static long[] resolve(GameTransaction transaction, long sessionId, List<String> headers)
            throws SQLException {
        record Candidate(long id, String displayName, String trackerName) {
        }
        List<Candidate> candidates = transaction.list("SELECT m.id, m.displayname, d.name"
                + " FROM tc_game_session_members m LEFT JOIN tc_devices d ON d.id = m.trackerid"
                + " WHERE m.gamesessionid = ? AND m.state = ? AND m.role IN (?, ?) ORDER BY m.id",
                rows -> new Candidate(rows.getLong(1), rows.getString(2), rows.getString(3)),
                sessionId, GameMember.ACTIVE, GameMember.PLAYER, GameMember.SUPPORTER);
        long[] result = new long[headers.size()];
        Set<Long> used = new HashSet<>();
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            List<Long> matches = new ArrayList<>();
            for (Candidate candidate : candidates) {
                if (header.equals(candidate.displayName())) {
                    matches.add(candidate.id());
                }
            }
            if (matches.isEmpty()) {
                for (Candidate candidate : candidates) {
                    if (header.equals(candidate.trackerName())) {
                        matches.add(candidate.id());
                    }
                }
            }
            if (matches.isEmpty()) {
                throw GameErrors.invalid("Timetable column '" + header
                        + "' does not name an active player or supporter of this game session");
            }
            if (matches.size() > 1) {
                throw GameErrors.invalid("Timetable column '" + header + "' names more than one member");
            }
            if (!used.add(matches.getFirst())) {
                throw GameErrors.invalid("Two timetable columns name the same member ('" + header + "')");
            }
            result[i] = matches.getFirst();
        }
        return result;
    }

    private static List<String> participantHeaders(JsonNode columns) {
        if (columns == null || !columns.isArray() || columns.isEmpty()) {
            throw GameErrors.invalid("Timetable columns must be a non-empty JSON array");
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < columns.size(); i++) {
            JsonNode column = columns.get(i);
            if (!column.isTextual() || column.asText().length() > 128) {
                throw GameErrors.invalid("Invalid timetable column");
            }
            if (i > 0) {
                if (column.asText().isBlank() || !seen.add(column.asText())) {
                    throw GameErrors.invalid("Timetable participant columns must be distinct and not blank");
                }
                result.add(column.asText());
            }
        }
        return result;
    }

    /**
     * Publishes a parsed timetable based on the revision it was loaded with (0 for the first one). Retrying the
     * publication that already succeeded answers the current version.
     */
    public Published publish(long sessionId, long expectedRevision, JsonNode columns, JsonNode source,
                             List<EntryInput> entries) throws Exception {
        List<String> headers = participantHeaders(columns);
        String serializedSource = GameDocumentService.serialize(objectMapper,
                source == null ? JsonNodeFactory.instance.objectNode() : source, "Timetable source");
        if (entries == null || entries.size() > MAX_ENTRIES) {
            throw GameErrors.invalid("Invalid timetable entry list");
        }
        for (EntryInput entry : entries) {
            if (entry == null || entry.participant() == null || !headers.contains(entry.participant())
                    || entry.content() == null || entry.content().length() > 512) {
                throw GameErrors.invalid("Invalid timetable entry");
            }
        }
        Published published = transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            long[] resolved = resolve(transaction, sessionId, headers);
            Map<String, Long> members = new HashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                members.put(headers.get(i), resolved[i]);
            }
            Map<Key, String> desired = new LinkedHashMap<>();
            for (EntryInput entry : entries) {
                Key key = new Key(GameTime.parse(entry.scheduledAt(), "timetable entry time"),
                        members.get(entry.participant()));
                if (desired.put(key, entry.content()) != null) {
                    throw GameErrors.invalid("Duplicate timetable entry");
                }
            }
            ObjectNode headersDocument = JsonNodeFactory.instance.objectNode();
            headersDocument.set("columns", columns.deepCopy());
            ArrayNode memberIds = headersDocument.putArray("memberIds");
            memberIds.addNull();
            for (long member : resolved) {
                memberIds.add(member);
            }
            // As it reads back from storage, so the comparison below does not depend on JSON number types.
            String serializedHeaders = objectMapper.writeValueAsString(headersDocument);
            headersDocument = (ObjectNode) objectMapper.readTree(serializedHeaders);

            var header = GameTimetableRepository.header(transaction, sessionId);
            long current = header.map(GameTimetableRepository.Header::revision).orElse(0L);
            ObjectNode storedHeaders = header.isPresent() ? headers(header.get())
                    : JsonNodeFactory.instance.objectNode();
            Map<Key, String> previous = new HashMap<>();
            Set<Instant> previousTimes = new HashSet<>();
            for (var row : GameTimetableRepository.active(transaction, sessionId)) {
                previous.put(new Key(row.scheduledAt(), row.memberId()), row.content());
                previousTimes.add(row.scheduledAt());
            }
            Set<Instant> desiredTimes = new HashSet<>();
            desired.keySet().forEach(key -> desiredTimes.add(key.scheduledAt()));
            boolean structureChanged = !storedHeaders.equals(headersDocument) || !previousTimes.equals(desiredTimes);
            boolean unchanged = !structureChanged && previous.equals(desired) && header.isPresent()
                    && objectMapper.readTree(header.get().source()).equals(objectMapper.readTree(serializedSource));
            if (expectedRevision != current) {
                if (expectedRevision + 1 == current && unchanged) {
                    return new Published(current, false, read(transaction, sessionId).entries());
                }
                throw GameErrors.revisionConflict("Timetable", current);
            }
            long revision = current + 1;
            GameTimetableRepository.putHeader(transaction, sessionId, revision, serializedHeaders,
                    serializedSource);
            GameTimetableRepository.deactivateAll(transaction, sessionId, revision);
            for (var item : desired.entrySet()) {
                GameTimetableRepository.upsert(transaction, sessionId, item.getKey().scheduledAt(),
                        item.getKey().memberId(), item.getValue(), revision);
            }
            // Fakes armed on a ping that disappeared or no longer reaches the hunters are released; only their
            // owners are told.
            Set<Long> owners = GameJokerRepository.timetableChanged(transaction, sessionId);
            transaction.bumpSession();
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(sessionId, GamePublisher.TIMETABLE, null)));
            if (!owners.isEmpty()) {
                transaction.afterCommit(() -> publisher.invalidate(
                        new GamePublisher.Invalidation(sessionId, GamePublisher.JOKERS, owners)));
            }
            return new Published(revision, structureChanged, read(transaction, sessionId).entries());
        });
        return published;
    }

    /** Records execution progress of entries: {@code scheduled}, {@code sent}, {@code skipped} or {@code failed}. */
    public void progress(long sessionId, List<Progress> progress) throws Exception {
        if (progress == null || progress.size() > MAX_ENTRIES) {
            throw GameErrors.invalid("Invalid timetable progress");
        }
        for (Progress item : progress) {
            if (item == null || item.id() <= 0 || !STATUSES.contains(item.status())) {
                throw GameErrors.invalid("Invalid timetable progress entry");
            }
        }
        transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            for (Progress item : progress) {
                if (GameTimetableRepository.progress(transaction, sessionId, item.id(), item.status()) == 0) {
                    throw GameErrors.invalid("Unknown timetable entry " + item.id());
                }
            }
            transaction.bumpSession();
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(sessionId, GamePublisher.TIMETABLE, null)));
            return null;
        });
    }

    // Member views ------------------------------------------------------------------------------------------

    /**
     * The timetable pings a member sees: players and supporters their own pings that can carry a fake, spectators
     * every ping that reaches hunters. The cell content is never included, so operator coordinates and fakes look
     * like real pings. Past pings of the last hour come first (at most {@value #PAST_PINGS}), then upcoming ones.
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
            boolean visible = ownMember != null ? fakeEligible(row.content()) : hunterFacing(row.content());
            if (!visible || distinctTimes && Objects.equals(row.scheduledAt(), previous)) {
                continue;
            }
            previous = row.scheduledAt();
            Boolean fakeAllowed = ownMember == null ? null
                    : row.scheduledAt().isAfter(cutoff) && !taken.contains(row.id());
            result.add(new Ping(row.id(), row.scheduledAt(), row.memberId(), row.status(), row.statusAt(),
                    fakeAllowed));
        }
        return result;
    }

}
