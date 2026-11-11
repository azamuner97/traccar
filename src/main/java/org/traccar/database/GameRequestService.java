/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.database;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Self-service game requests and the status document the controlling Tool publishes.
 * <p>
 * Members submit requests; the server authorizes them from session membership and the published status,
 * keeps at most one open request per participant, and expires requests awaiting approval or execution after
 * two minutes. The controlling Tool decides, claims and completes them; completions commit inside the effects
 * transaction. A change of control invalidates every open request and keeps its history.
 * <p>
 * Breaks are timed by the Tool and published in the status. A break request is refused while any speedhunt runs,
 * with the same message whoever the speedhunt targets; speedhunt pings on a participant on a break are refused.
 * A break request may name a later start ({@code startsAt}): the succeeded request arms the break joker until the
 * Tool starts the break, which waits at its start while any speedhunt runs. Breaks of one participant never
 * overlap.
 */
@Singleton
public class GameRequestService {

    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String EXECUTING = "executing";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";
    public static final String REJECTED = "rejected";
    public static final String EXPIRED = "expired";
    public static final String CANCELLED = "cancelled";

    public static final Set<String> OPEN = Set.of(PENDING, APPROVED, EXECUTING);
    public static final Set<String> HUNTER_TYPES = Set.of(
            "speedhuntStaticPing", "speedhuntLivePing", "speedhuntStopLivePing", "speedhuntTerminate");
    /** Joker requests players and supporters make for themselves. */
    public static final Set<String> JOKER_TYPES = GameJokerService.REQUEST_TYPES.keySet();
    public static final int EXPIRY_SECONDS = 120;
    public static final String BREAK_REQUEST = "jokerBreak";
    /** A fake ping on an own timetable ping ({@code entryId}) or a ping of the next speedhunt ({@code pingNumber}). */
    public static final String FAKE_PING_REQUEST = "jokerFakePing";
    /** The same answer for every participant, so it never reveals whom a speedhunt targets. */
    public static final String BREAK_UNAVAILABLE = "A break cannot start right now; try again later";
    public static final String BREAK_OVERLAP = "This break would overlap another break of yours";
    private static final Set<String> SPEEDHUNT_PINGS = Set.of("speedhuntStaticPing", "speedhuntLivePing");
    private static final Set<String> LIVE_SHARE_SOURCES = Set.of("manual", "geofence", "speedhunt");
    private static final String REPEAT_SPEEDHUNT = "The previous speedhunt was already on this participant";
    private static final String LIVE_DURATION_REQUIRED = "Set a positive live ping duration in the game settings";
    private static final Instant MAX_LIVE_DEADLINE = Instant.parse("9999-12-31T23:59:59Z");

    public record Membership(long gameSessionId, long userId, String role, Long assignedDeviceId) { }
    public record Submission(String type, Long targetDeviceId, Long baseRevision, JsonNode parameters) { }
    public record Request(long id, long gameSessionId, String type, Long requesterUserId, String requesterLabel,
                          String requesterRole, Long targetDeviceId, String targetLabel, Long baseRevision,
                          JsonNode parameters, String status, Boolean approvalRequired, String message,
                          String detail, String createdAt, String expiresAt, String decidedAt,
                          String completedAt) { }
    public record Completion(Long id, String status, String message, String detail) { }
    public record Status(long gameSessionId, String role, boolean controllerActive, String serverTime,
                         long revision, JsonNode status) { }
    public record SpeedhuntHistory(long gameSessionId, List<SpeedhuntHistoryRun> runs) { }
    public record SpeedhuntHistoryRun(String id, String startedAt, String status, List<SpeedhuntHistoryPing> pings) { }
    public record SpeedhuntHistoryPing(int number, String sentAt, String type) { }

    private record HunterLabels(Map<Long, String> byDevice, Map<Long, String> byUser) { }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws Exception;
    }

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Inject
    public GameRequestService(DataSource dataSource, ObjectMapper objectMapper) {
        this(dataSource, objectMapper, Clock.systemUTC());
    }

    public GameRequestService(DataSource dataSource, ObjectMapper objectMapper, Clock clock) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // --- helpers ------------------------------------------------------------------------------------------

    private <T> T transaction(Work<T> work) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static String iso(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column, utc());
        return value == null ? null : value.toInstant().toString();
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static String text(String value, int length) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("\\p{Cntrl}", " ").trim();
        return cleaned.length() > length ? cleaned.substring(0, length) : cleaned;
    }

    private static WebApplicationException conflict(String message) {
        return new WebApplicationException(message, 409);
    }

    private Request request(ResultSet rows) throws Exception {
        boolean approval = rows.getBoolean("approvalrequired");
        Boolean approvalRequired = rows.wasNull() ? null : approval;
        return new Request(rows.getLong("id"), rows.getLong("gamesessionid"), rows.getString("type"),
                nullableLong(rows, "requesteruserid"), rows.getString("requesterlabel"),
                rows.getString("requesterrole"), nullableLong(rows, "targetdeviceid"), rows.getString("targetlabel"),
                nullableLong(rows, "baserevision"), objectMapper.readTree(rows.getString("parameters")),
                rows.getString("status"), approvalRequired, rows.getString("message"), rows.getString("detail"),
                iso(rows, "createdat"), iso(rows, "expiresat"), iso(rows, "decidedat"), iso(rows, "completedat"));
    }

    /** Hunters receive roster labels and structured request status instead of free-text outcomes. */
    private static Request memberView(Request request, HunterLabels labels) {
        String requester = labels == null ? request.requesterLabel()
                : labels.byUser().getOrDefault(request.requesterUserId(), roleName(request.requesterRole()));
        String target = labels == null ? request.targetLabel()
                : labels.byDevice().getOrDefault(request.targetDeviceId(), "Participant");
        return new Request(request.id(), request.gameSessionId(), request.type(), request.requesterUserId(),
                requester, request.requesterRole(), request.targetDeviceId(), target,
                request.baseRevision(), request.parameters(), request.status(), request.approvalRequired(),
                labels == null ? request.message() : null, null, request.createdAt(), request.expiresAt(),
                request.decidedAt(), request.completedAt());
    }

    private static String roleName(String role) {
        return switch (role) {
            case "player" -> "Player";
            case "supporter" -> "Supporter";
            case "hunter" -> "Hunter";
            default -> "Participant";
        };
    }

    private HunterLabels hunterLabels(Connection connection, Membership member) throws Exception {
        if (!"hunter".equals(member.role())) {
            return null;
        }
        JsonNode groups = JsonNodeFactory.instance.objectNode();
        try (var query = connection.prepareStatement("SELECT resources FROM tc_game_sessions WHERE id = ?")) {
            query.setLong(1, member.gameSessionId());
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    groups = objectMapper.readTree(rows.getString(1)).path("groups");
                }
            }
        }
        Map<Long, String> byDevice = new HashMap<>();
        Map<Long, String> byUser = new HashMap<>();
        try (var query = connection.prepareStatement("SELECT userid,role,number,assigneddeviceid"
                + " FROM tc_game_session_members WHERE gamesessionid = ?"
                + " AND role IN ('player','supporter','hunter') ORDER BY userid")) {
            query.setLong(1, member.gameSessionId());
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    String role = rows.getString("role");
                    String group = groups.path(role + "_group").path("name").asText("").strip();
                    int number = rows.getInt("number");
                    String label = group.isEmpty() ? roleName(role) : group;
                    if (number > 0) {
                        label += " " + number;
                    }
                    byUser.put(rows.getLong("userid"), label);
                    Long device = nullableLong(rows, "assigneddeviceid");
                    if (device != null && !"hunter".equals(role)) {
                        byDevice.putIfAbsent(device, label);
                    }
                }
            }
        }
        return new HunterLabels(byDevice, byUser);
    }

    private Request load(Connection connection, long gameSessionId, long id, boolean lock) throws Exception {
        try (var query = connection.prepareStatement("SELECT * FROM tc_game_requests WHERE id = ?"
                + " AND gamesessionid = ?" + (lock ? " FOR UPDATE" : ""))) {
            query.setLong(1, id);
            query.setLong(2, gameSessionId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new NotFoundException("Game request not found");
                }
                return request(rows);
            }
        }
    }

    private JsonNode statusDocument(Connection connection, long gameSessionId) throws Exception {
        try (var query = connection.prepareStatement(
                "SELECT document FROM tc_game_session_status WHERE gamesessionid = ?")) {
            query.setLong(1, gameSessionId);
            try (var rows = query.executeQuery()) {
                return rows.next() ? objectMapper.readTree(rows.getString(1)) : null;
            }
        }
    }

    private JsonNode gameSettings(Connection connection, long gameSessionId) throws Exception {
        try (var query = connection.prepareStatement("SELECT config FROM tc_game_sessions WHERE id = ?")) {
            query.setLong(1, gameSessionId);
            try (var rows = query.executeQuery()) {
                return rows.next()
                        ? objectMapper.readTree(rows.getString(1)).path("game_settings")
                        : JsonNodeFactory.instance.objectNode();
            }
        }
    }

    private void checkSpeedhuntPing(
            Connection connection, long gameSessionId, String type, JsonNode published,
            JsonNode target, JsonNode parameters) throws Exception {
        JsonNode settings = gameSettings(connection, gameSessionId);
        boolean live = "speedhuntLivePing".equals(type);
        if (live) {
            JsonNode minutes = settings.path("fixed_speedhunt_live_duration_minutes");
            double seconds = minutes.asDouble() * 60;
            if (!minutes.isNumber() || !Double.isFinite(seconds) || seconds < 0.000001
                    || seconds > MAX_LIVE_DEADLINE.getEpochSecond() - now().getEpochSecond()) {
                throw conflict(LIVE_DURATION_REQUIRED);
            }
        }
        JsonNode speedhunt = target.path("speedhunt");
        boolean startsRun = speedhunt.path("startsRun").asBoolean(
                !speedhunt.path("operation").asBoolean() && !speedhunt.path("running").asBoolean()
                        || speedhunt.path("pings").asInt() == 0);
        if (startsRun && !settings.path("allow_consecutive_speedhunts").asBoolean(false)
                && published.path("lastSpeedhuntDeviceId").asLong(-1) == target.path("deviceId").asLong()) {
            String approval = live ? "speedhunt_live_ping" : "speedhunt_static_ping";
            if ("block".equals(settings.path("limit_enforcement").asText())
                    || !settings.path("request_approvals").path(approval).asBoolean(true)) {
                throw conflict(REPEAT_SPEEDHUNT);
            }
            if (!parameters.path("confirmConsecutive").asBoolean(false)) {
                throw conflict(REPEAT_SPEEDHUNT + "; confirm that you want to request approval");
            }
        }
    }

    /** The participant entry of the published status, or {@code null}. */
    private static JsonNode participant(JsonNode status, long deviceId) {
        if (status == null || !status.path("participants").isArray()) {
            return null;
        }
        for (JsonNode item : status.get("participants")) {
            if (item.path("deviceId").asLong() == deviceId) {
                return item;
            }
        }
        return null;
    }

    /** Select the most recently started active hunt using the Tool's published order. */
    private static JsonNode currentSpeedhunt(JsonNode status) {
        if (status == null || !status.path("participants").isArray()) {
            return null;
        }
        long lastTarget = status.path("lastSpeedhuntDeviceId").asLong(-1);
        long newestRunning = status.path("runningSpeedhunts").path(0).path("deviceId").asLong(-1);
        JsonNode fallback = null;
        for (JsonNode item : status.get("participants")) {
            JsonNode speedhunt = item.path("speedhunt");
            if (speedhunt.path("running").asBoolean(false) || speedhunt.path("live").asBoolean(false)) {
                long deviceId = item.path("deviceId").asLong();
                if (deviceId == lastTarget) {
                    return speedhunt;
                }
                if (fallback == null || deviceId == newestRunning) {
                    fallback = speedhunt;
                }
            }
        }
        return fallback;
    }

    static boolean speedhuntRunning(JsonNode status) {
        return currentSpeedhunt(status) != null;
    }

    private static int positiveInt(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() ? Math.max(0, value.intValue()) : 0;
    }

    private static ObjectNode speedhuntView(JsonNode document, boolean includeHistory) {
        JsonNode hunt = currentSpeedhunt(document);
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("active", hunt != null);
        if (hunt == null) {
            return result;
        }
        int count = positiveInt(hunt.path("pings"));
        int limit = positiveInt(document.path("limits").path("pingsPerSpeedhunt"));
        if (count > 0) {
            result.put("pingCount", count);
        }
        if (limit > 0) {
            result.put("pingLimit", limit);
        }
        if (includeHistory) {
            copyPingHistory(hunt, result);
        }
        return result;
    }

    private static void copyPingHistory(JsonNode hunt, ObjectNode result) {
        if (hunt.path("pingHistory").isArray()) {
            int count = positiveInt(hunt.path("pings"));
            var pings = new TreeMap<Integer, Instant>();
            for (JsonNode ping : hunt.get("pingHistory")) {
                int number = positiveInt(ping.path("number"));
                if (number > 0 && number <= count && ping.path("sentAt").isTextual()) {
                    try {
                        pings.putIfAbsent(number, Instant.parse(ping.get("sentAt").textValue()));
                    } catch (java.time.format.DateTimeParseException ignored) {
                        // Skip a ping without a valid send time.
                    }
                }
            }
            var history = result.putArray("pingHistory");
            pings.forEach((number, time) -> history.addObject().put("number", number).put("sentAt", time.toString()));
        }
    }

    /**
     * Whether the published status shows the participant on a break. The Tool ends breaks at their deadline; a
     * break past its deadline no longer protects anyone, even before the Tool has published its end.
     */
    static boolean onBreak(JsonNode participant, Instant now) {
        JsonNode pause = participant == null ? null : participant.path("break");
        if (pause == null || !pause.path("active").asBoolean(false)) {
            return false;
        }
        try {
            return !pause.hasNonNull("endsAt") || Instant.parse(pause.get("endsAt").asText()).isAfter(now);
        } catch (java.time.format.DateTimeParseException error) {
            return true;
        }
    }

    /** The session of the active controller lease, or null. */
    private Long executingSession(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT state,expiresat,gamesessionid FROM tc_game_controller WHERE id = 1")) {
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                Timestamp expires = rows.getTimestamp("expiresat", utc());
                if (GameControllerService.STATE_ACTIVE.equals(rows.getString("state"))
                        && expires != null && expires.toInstant().isAfter(now())) {
                    return nullableLong(rows, "gamesessionid");
                }
                return null;
            }
        }
    }

    private boolean executing(Connection connection, long gameSessionId) throws SQLException {
        Long session = executingSession(connection);
        return session != null && session == gameSessionId;
    }

    private long liveShareExecution(Connection connection, long gameSessionId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT MAX(h.id) FROM tc_game_controller c"
                + " JOIN tc_game_controller_history h ON h.epoch = c.epoch AND h.gamesessionid = c.gamesessionid"
                + " WHERE c.id = 1 AND c.gamesessionid = ? AND c.state = ? AND c.expiresat > ?"
                + " AND h.event = 'executing'")) {
            query.setLong(1, gameSessionId);
            query.setString(2, GameControllerService.STATE_ACTIVE);
            query.setTimestamp(3, Timestamp.from(now()), utc());
            try (var rows = query.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    private static String userLabel(Connection connection, long userId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT name,email FROM tc_users WHERE id = ?")) {
            query.setLong(1, userId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                String label = rows.getString("name");
                return text(label == null || label.isBlank() ? rows.getString("email") : label, 128);
            }
        }
    }

    // --- membership and status ----------------------------------------------------------------------------

    public Membership membership(long userId) throws Exception {
        try (var connection = dataSource.getConnection();
             var query = connection.prepareStatement("SELECT gamesessionid,role,assigneddeviceid"
                     + " FROM tc_game_session_members WHERE userid = ?")) {
            query.setLong(1, userId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new ForbiddenException("This account does not take part in a game");
                }
                return new Membership(rows.getLong("gamesessionid"), userId, rows.getString("role"),
                        nullableLong(rows, "assigneddeviceid"));
            }
        }
    }

    /** A session's saved runs, with the same anonymous projection for every member. */
    public SpeedhuntHistory speedhuntHistory(long userId) throws Exception {
        try (var connection = dataSource.getConnection();
             var query = connection.prepareStatement("SELECT s.id,s.runtime FROM tc_game_session_members m"
                     + " JOIN tc_game_sessions s ON s.id = m.gamesessionid WHERE m.userid = ?")) {
            query.setLong(1, userId);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    throw new ForbiddenException("This account does not take part in a game");
                }
                long sessionId = rows.getLong(1);
                JsonNode gameStatus = objectMapper.readTree(rows.getString(2)).path("game_status");
                return new SpeedhuntHistory(sessionId, speedhuntHistoryRuns(sessionId, gameStatus));
            }
        }
    }

    private static List<SpeedhuntHistoryRun> speedhuntHistoryRuns(long sessionId, JsonNode gameStatus) {
        record SavedRun(
                long participantId, Instant startedAt, String status, Map<Integer, SpeedhuntHistoryPing> pings) {
        }
        Map<String, SavedRun> runs = new HashMap<>();
        gameStatus.path("speedhunt_runs").properties().forEach(entry -> {
            JsonNode record = entry.getValue();
            Instant startedAt = historyTime(record.path("started_at"));
            long participantId = historyParticipant(record);
            String status = switch (record.path("status").asText()) {
                case "running" -> "inProgress";
                case "completed", "terminated" -> "finished";
                default -> null;
            };
            if (!entry.getKey().isBlank() && participantId > 0 && startedAt != null && status != null) {
                runs.put(entry.getKey(), new SavedRun(participantId, startedAt, status, new TreeMap<>()));
            }
        });
        gameStatus.path("speedhunt_pings").properties().forEach(entry -> {
            JsonNode record = entry.getValue();
            SavedRun run = runs.get(record.path("run_id").textValue());
            if (run != null && run.participantId() == historyParticipant(record)) {
                int number = positiveInt(record.path("number"));
                Instant sentAt = historyTime(record.path("sent_at"));
                String type = record.path("delivery").asText();
                if (number > 0 && sentAt != null && ("live".equals(type) || "static".equals(type))) {
                    run.pings().merge(number, new SpeedhuntHistoryPing(number, sentAt.toString(), type),
                            (previous, ping) -> sentAt.isBefore(Instant.parse(previous.sentAt())) ? ping : previous);
                }
            }
        });
        return runs.entrySet().stream()
                .sorted(Comparator
                        .comparing((Map.Entry<String, SavedRun> entry) -> entry.getValue().startedAt())
                        .reversed().thenComparing(Map.Entry::getKey))
                .map(entry -> {
                    SavedRun run = entry.getValue();
                    String key = "speedhunt-history:" + sessionId + ":" + entry.getKey();
                    String id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
                    return new SpeedhuntHistoryRun(
                            id, run.startedAt().toString(), run.status(), List.copyOf(run.pings().values()));
                })
                .toList();
    }

    private static long historyParticipant(JsonNode record) {
        JsonNode id = record.path("participant_id");
        return id.isIntegralNumber() && id.canConvertToLong() ? id.longValue() : 0;
    }

    private static Instant historyTime(JsonNode value) {
        if (value.isTextual()) {
            try {
                return OffsetDateTime.parse(value.textValue()).toInstant();
            } catch (DateTimeParseException ignored) {
                // Older or incomplete records may have no usable timestamp.
            }
        }
        return null;
    }

    /**
     * Hunters and spectators see per-participant speedhunt state and breaks, with roster labels for hunters;
     * players and supporters see the catch roster, anonymous speedhunt progress and their own break.
     */
    public Status status(Membership member) throws Exception {
        return transaction(connection -> {
            long revision = 0;
            JsonNode document = null;
            try (var query = connection.prepareStatement(
                    "SELECT revision,document FROM tc_game_session_status WHERE gamesessionid = ?")) {
                query.setLong(1, member.gameSessionId());
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        revision = rows.getLong(1);
                        document = objectMapper.readTree(rows.getString(2));
                    }
                }
            }
            boolean active = executing(connection, member.gameSessionId());
            HunterLabels labels = document == null ? null : hunterLabels(connection, member);
            ObjectNode filtered = filter(document, member.role(), active, member.assignedDeviceId(), labels);
            if ("hunter".equals(member.role())) {
                var shares = filtered.putArray("liveShares");
                if (active && labels != null && document.path("liveShareExecution").asLong() > 0
                        && document.path("liveShareExecution").asLong()
                                == liveShareExecution(connection, member.gameSessionId())) {
                    Instant current = now();
                    Set<Long> included = new HashSet<>();
                    for (JsonNode share : document.path("liveShares")) {
                        JsonNode id = share.path("deviceId");
                        if (!id.isIntegralNumber() || !id.canConvertToLong()) {
                            continue;
                        }
                        long device = id.asLong();
                        String name = labels.byDevice().get(device);
                        JsonNode participant = participant(document, device);
                        JsonNode end = share.path("endsAt");
                        if (name != null && participant != null && !participant.path("caught").asBoolean()
                                && !onBreak(participant, current) && liveShareUnexpired(end, current)
                                && included.add(device)) {
                            ObjectNode item = shares.addObject().put("deviceId", device).put("name", name);
                            if (end.isTextual()) {
                                item.set("endsAt", end);
                            }
                            String source = share.path("source").asText("");
                            if (LIVE_SHARE_SOURCES.contains(source)) {
                                item.put("source", source);
                            }
                            String reason = share.path("reason").asText("").strip();
                            if (share.path("reason").isTextual() && !reason.isEmpty()
                                    && reason.codePointCount(0, reason.length()) <= 500) {
                                item.put("reason", reason);
                            }
                        }
                    }
                }
            }
            if ("player".equals(member.role()) || "supporter".equals(member.role())) {
                filtered.set("participantOverview", participantOverview(connection, member.gameSessionId(), document));
                if (member.assignedDeviceId() != null) {
                    // Owners see their own armed fake plans; nobody else ever does.
                    filtered.set("fakes",
                            GameJokerService.ownFakes(connection, member.gameSessionId(), member.assignedDeviceId()));
                }
            }
            return new Status(member.gameSessionId(), member.role(), active, now().toString(), revision, filtered);
        });
    }

    private static ArrayNode participantOverview(Connection connection, long gameSessionId, JsonNode document)
            throws SQLException {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        if (document == null || !document.path("participants").isArray()) {
            return result;
        }
        Map<Long, String> roles = new HashMap<>();
        try (var query = connection.prepareStatement("SELECT assigneddeviceid,role FROM tc_game_session_members"
                + " WHERE gamesessionid = ? AND role IN ('player','supporter') AND assigneddeviceid IS NOT NULL"
                + " ORDER BY userid")) {
            query.setLong(1, gameSessionId);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    roles.putIfAbsent(rows.getLong("assigneddeviceid"), rows.getString("role"));
                }
            }
        }
        for (JsonNode item : document.path("participants")) {
            JsonNode id = item.path("deviceId");
            JsonNode caught = item.path("caught");
            if (id.isIntegralNumber() && id.canConvertToLong() && id.asLong() > 0 && caught.isBoolean()) {
                long deviceId = id.asLong();
                String role = roles.remove(deviceId);
                if (role != null) {
                    String name = text(item.path("name").textValue(), 128);
                    result.addObject().put("deviceId", deviceId)
                            .put("name", name == null || name.isBlank() ? "#" + deviceId : name)
                            .put("role", role).put("caught", caught.booleanValue());
                }
            }
        }
        return result;
    }

    private static boolean liveShareUnexpired(JsonNode end, Instant now) {
        if (end.isMissingNode() || end.isNull()) {
            return true;
        }
        try {
            return end.isTextual() && Instant.parse(end.asText()).isAfter(now);
        } catch (java.time.format.DateTimeParseException error) {
            return false;
        }
    }

    /** Anonymous speedhunt progress in the requested session. Administrators may omit the session filter. */
    public JsonNode speedhuntStatus(Long gameSessionId) throws Exception {
        return transaction(connection -> {
            Long session = executingSession(connection);
            JsonNode document = session != null && (gameSessionId == null || gameSessionId.equals(session))
                    ? statusDocument(connection, session) : null;
            return speedhuntView(document, false);
        });
    }

    private static ObjectNode fields(JsonNode source, String... names) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        for (String name : names) {
            JsonNode value = source.path(name);
            if (value.isValueNode()) {
                result.set(name, value);
            }
        }
        return result;
    }

    private static ObjectNode filter(
            JsonNode document, String role, boolean active, Long deviceId, HunterLabels labels) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("speedhunt", speedhuntView(active ? document : null, true));
        if (labels != null) {
            var participants = result.putArray("participants");
            for (JsonNode item : document.path("participants")) {
                JsonNode device = item.path("deviceId");
                if (device.isIntegralNumber() && device.canConvertToLong() && device.asLong() > 0) {
                    ObjectNode participant = fields(item, "deviceId", "role", "caught");
                    participant.put("name", labels.byDevice().getOrDefault(device.asLong(), "Participant"));
                    JsonNode hunt = item.path("speedhunt");
                    if (hunt.isObject()) {
                        ObjectNode speedhunt = fields(hunt, "operation", "running", "pings", "startsRun", "live",
                                "liveUntil", "revision");
                        copyPingHistory(hunt, speedhunt);
                        participant.set("speedhunt", speedhunt);
                    }
                    if (item.path("break").isObject()) {
                        participant.set("break", fields(item.get("break"), "active", "startedAt", "endsAt"));
                    }
                    participants.add(participant);
                }
            }
            result.set("limits", document.path("limits").deepCopy());
            if (document.has("lastSpeedhuntDeviceId")) {
                result.set("lastSpeedhuntDeviceId", document.get("lastSpeedhuntDeviceId"));
            }
        } else if ("spectator".equals(role) && document != null) {
            result.set("participants", document.path("participants").deepCopy());
            result.set("limits", document.path("limits").deepCopy());
        } else if (("player".equals(role) || "supporter".equals(role)) && deviceId != null) {
            JsonNode own = participant(document, deviceId);
            if (own != null && own.path("break").isObject()) {
                result.set("break", own.get("break").deepCopy());
            }
            if (active && document != null) {
                for (JsonNode run : document.path("runningSpeedhunts")) {
                    int first = run.path("firstPing").asInt();
                    int last = run.path("lastPing").asInt();
                    String token = run.path("runToken").asText();
                    if (first > 1 && first <= last && GameJokerService.validRunToken(token)) {
                        ObjectNode choice = result.putObject("runningFake");
                        choice.put("runToken", token);
                        choice.put("firstPing", first);
                        choice.put("lastPing", last);
                        break;
                    }
                }
            }
        }
        return result;
    }

    public JsonNode operatorStatus(long gameSessionId) throws Exception {
        return transaction(connection -> {
            JsonNode document = statusDocument(connection, gameSessionId);
            return document == null ? JsonNodeFactory.instance.objectNode() : document;
        });
    }

    /** Replaces the published status inside an effects transaction. */
    long putStatus(Connection connection, long gameSessionId, JsonNode document) throws Exception {
        if (document == null || !document.isObject()) {
            throw new BadRequestException("Status must be a JSON object");
        }
        ObjectNode status = document.deepCopy();
        status.remove("liveShareExecution");
        if (status.path("liveShares").isArray() && !status.path("liveShares").isEmpty()) {
            status.put("liveShareExecution", liveShareExecution(connection, gameSessionId));
        }
        JsonNode previous = statusDocument(connection, gameSessionId);
        for (JsonNode run : status.path("runningSpeedhunts")) {
            JsonNode old = runningSpeedhunt(previous, run.path("runToken").asText());
            if (old != null && run.isObject() && old.path("firstPing").asInt() > run.path("firstPing").asInt()) {
                ((ObjectNode) run).put("firstPing", old.path("firstPing").asInt());
            }
        }
        GameJokerService.releaseClosedRunningFakes(connection, gameSessionId, status);
        String serialized = objectMapper.writeValueAsString(status);
        if (serialized.length() > 1_000_000) {
            throw new BadRequestException("Status exceeds the size limit");
        }
        long revision = 0;
        try (var query = connection.prepareStatement(
                "SELECT revision FROM tc_game_session_status WHERE gamesessionid = ? FOR UPDATE")) {
            query.setLong(1, gameSessionId);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    revision = rows.getLong(1);
                }
            }
        }
        String sql = revision == 0
                ? "INSERT INTO tc_game_session_status (revision,document,updatedat,gamesessionid) VALUES (?,?,?,?)"
                : "UPDATE tc_game_session_status SET revision = ?, document = ?, updatedat = ? WHERE gamesessionid = ?";
        try (var query = connection.prepareStatement(sql)) {
            query.setLong(1, revision + 1);
            query.setString(2, serialized);
            query.setTimestamp(3, Timestamp.from(now()), utc());
            query.setLong(4, gameSessionId);
            query.executeUpdate();
        }
        return revision + 1;
    }

    static JsonNode runningSpeedhunt(JsonNode document, String token) {
        if (document != null && !token.isEmpty()) {
            for (JsonNode run : document.path("runningSpeedhunts")) {
                if (token.equals(run.path("runToken").asText())) {
                    return run;
                }
            }
        }
        return null;
    }

    static boolean runningFakeOpen(JsonNode document, JsonNode parameters) {
        JsonNode run = runningSpeedhunt(document, parameters.path("runToken").asText());
        int number = parameters.path("pingNumber").asInt();
        return run != null && number >= run.path("firstPing").asInt() && number <= run.path("lastPing").asInt();
    }

    Long advanceRunningPing(Connection connection, long gameSessionId, GameJokerService.RunningPing ping)
            throws Exception {
        JsonNode document = statusDocument(connection, gameSessionId);
        JsonNode run = runningSpeedhunt(document, ping.runToken());
        if (run == null) {
            return null; // The Tool can send a follow-up before the new run's first status publication.
        }
        if (run.path("deviceId").asLong() != ping.deviceId()) {
            throw new BadRequestException("Speedhunt run does not match the participant");
        }
        if (!ping.counted()) {
            return null;
        }
        if (run.path("firstPing").asInt() > ping.pingNumber()) {
            throw conflict("This speedhunt ping was already delivered");
        }
        ((ObjectNode) run).put("firstPing", ping.pingNumber() + 1);
        return putStatus(connection, gameSessionId, document);
    }

    // --- member requests ----------------------------------------------------------------------------------

    private static double coordinate(JsonNode input, String name, double limit) {
        JsonNode value = input == null ? null : input.get(name);
        if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())
                || Math.abs(value.asDouble()) > limit) {
            throw new BadRequestException("A fake needs a valid " + name);
        }
        return value.asDouble();
    }

    private ObjectNode parameters(Submission submission) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        JsonNode input = submission.parameters();
        boolean fake = FAKE_PING_REQUEST.equals(submission.type());
        boolean timetable = fake && input != null && input.hasNonNull("entryId");
        boolean speedhunt = fake && input != null && input.hasNonNull("pingNumber");
        if (fake && timetable == speedhunt) {
            throw new BadRequestException("Choose either one of your timetable pings or a ping of the next speedhunt");
        }
        if (timetable) {
            if (input.has("runToken")) {
                throw new BadRequestException("A timetable fake cannot name a speedhunt");
            }
            JsonNode entry = input.get("entryId");
            if (!entry.isIntegralNumber() || !entry.canConvertToLong() || entry.asLong() <= 0) {
                throw new BadRequestException("Choose one of your upcoming timetable pings");
            }
            result.put("entryId", entry.asLong());
            result.put("latitude", coordinate(input, "latitude", 90));
            result.put("longitude", coordinate(input, "longitude", 180));
            return result;
        }
        if (speedhunt) {
            JsonNode ping = input.get("pingNumber");
            if (ping == null || !ping.isIntegralNumber() || !ping.canConvertToInt() || ping.asInt() < 1
                    || ping.asInt() > GameJokerService.MAX_FAKE_PING_NUMBER) {
                throw new BadRequestException("Choose the ping of the next speedhunt the fake replaces");
            }
            result.put("pingNumber", ping.asInt());
            if (input.has("runToken")) {
                JsonNode token = input.get("runToken");
                if (!token.isTextual() || !GameJokerService.validRunToken(token.asText())) {
                    throw new BadRequestException("Choose a running speedhunt");
                }
                result.put("runToken", token.asText());
            }
            result.put("latitude", coordinate(input, "latitude", 90));
            result.put("longitude", coordinate(input, "longitude", 180));
            return result;
        }
        if (BREAK_REQUEST.equals(submission.type()) && input != null && input.hasNonNull("startsAt")) {
            // A planned start; one that has already passed is a break that starts now.
            Instant start;
            try {
                start = Instant.parse(input.get("startsAt").asText());
            } catch (java.time.format.DateTimeParseException error) {
                throw new BadRequestException("The start of a break must be a time such as 2026-11-09T14:00:00Z");
            }
            if (start.isAfter(now())) {
                result.put("startsAt", start.truncatedTo(ChronoUnit.SECONDS).toString());
            }
            return result;
        }
        if (SPEEDHUNT_PINGS.contains(submission.type()) && input != null && input.has("confirmConsecutive")) {
            JsonNode confirmation = input.get("confirmConsecutive");
            if (!confirmation.isBoolean()) {
                throw new BadRequestException("The consecutive speedhunt confirmation must be true or false");
            }
            result.put("confirmConsecutive", confirmation.asBoolean());
        }
        return result;
    }

    public Request submit(Membership member, Submission submission) throws Exception {
        if (submission == null || submission.type() == null) {
            throw new BadRequestException("Request type required");
        }
        boolean joker = JOKER_TYPES.contains(submission.type());
        if (!HUNTER_TYPES.contains(submission.type()) && !joker) {
            throw new BadRequestException("Unknown request type");
        }
        if (joker) {
            if (!"player".equals(member.role()) && !"supporter".equals(member.role())
                    || member.assignedDeviceId() == null) {
                throw new ForbiddenException("Only players and supporters use jokers");
            }
            // Jokers are always for the requester themselves.
            submission = new Submission(submission.type(), member.assignedDeviceId(), null,
                    submission.parameters());
        } else {
            if (!"hunter".equals(member.role())) {
                throw new ForbiddenException("Only hunters control speedhunts");
            }
            if (submission.targetDeviceId() == null || submission.baseRevision() == null) {
                throw new BadRequestException("Participant and speedhunt revision required");
            }
        }
        final Submission accepted = submission;
        ObjectNode parameters = parameters(accepted);
        return transaction(connection -> {
            try (var query = connection.prepareStatement(
                    "SELECT id FROM tc_game_sessions WHERE id = ? FOR UPDATE")) {
                query.setLong(1, member.gameSessionId());
                query.executeQuery().close();
            }
            if (!executing(connection, member.gameSessionId())) {
                throw conflict("The game is not running right now; try again later");
            }
            String targetLabel = null;
            try (var query = connection.prepareStatement("SELECT login FROM tc_game_session_members"
                    + " WHERE gamesessionid = ? AND assigneddeviceid = ? AND role IN ('player','supporter')")) {
                query.setLong(1, member.gameSessionId());
                query.setLong(2, accepted.targetDeviceId());
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) {
                        throw new BadRequestException("Unknown participant");
                    }
                    targetLabel = rows.getString(1);
                }
            }
            JsonNode published = statusDocument(connection, member.gameSessionId());
            JsonNode target = participant(published, accepted.targetDeviceId());
            if (target == null) {
                throw conflict("The game status is not available yet; try again shortly");
            }
            if (target.path("caught").asBoolean(false)) {
                throw conflict(joker ? "You are out of the game" : "This participant is out of the game");
            }
            if (!joker && target.path("speedhunt").path("revision").asLong(-1) != accepted.baseRevision()) {
                throw conflict("The speedhunt changed meanwhile; refresh and try again");
            }
            if (SPEEDHUNT_PINGS.contains(accepted.type())) {
                if (onBreak(target, now())) {
                    throw conflict("This participant is on a break");
                }
                checkSpeedhuntPing(connection, member.gameSessionId(), accepted.type(), published, target, parameters);
            }
            if (BREAK_REQUEST.equals(accepted.type())) {
                Double minutes = GameJokerService.breakMinutes(connection, member.gameSessionId());
                if (minutes == null) {
                    throw conflict("Breaks are not set up for this game");
                }
                boolean planned = parameters.has("startsAt");
                // A planned break waits at its start while any speedhunt runs; only a break that starts now is
                // refused during a speedhunt.
                if (!planned && onBreak(target, now())) {
                    throw conflict("You are already on a break");
                }
                if (!planned && speedhuntRunning(published)) {
                    throw conflict(BREAK_UNAVAILABLE);
                }
                Instant start = planned ? Instant.parse(parameters.get("startsAt").asText()) : now();
                if (GameJokerService.breakOverlaps(connection, member.gameSessionId(), accepted.targetDeviceId(),
                        start, minutes, target, now())) {
                    throw conflict(BREAK_OVERLAP);
                }
            }
            if (FAKE_PING_REQUEST.equals(accepted.type()) && parameters.has("entryId")) {
                Instant scheduled = fakeTimetableEntry(connection, member.gameSessionId(), accepted.targetDeviceId(),
                        parameters.get("entryId").asLong());
                JsonNode pause = target.path("break");
                if (onBreak(target, now()) && pause.hasNonNull("endsAt")
                        && !Instant.parse(pause.get("endsAt").asText()).isBefore(scheduled)) {
                    throw conflict("This ping falls inside your break");
                }
                if (GameJokerService.timetableFakeTaken(connection, member.gameSessionId(),
                        parameters.get("entryId").asLong())) {
                    throw conflict("This ping already has a fake");
                }
                parameters.put("scheduledAt", scheduled.toString());
            }
            if (FAKE_PING_REQUEST.equals(accepted.type()) && parameters.has("pingNumber")) {
                Integer limit = GameJokerService.pingsPerSpeedhunt(connection, member.gameSessionId());
                if (limit != null && parameters.get("pingNumber").asInt() > limit) {
                    throw conflict("A speedhunt has " + limit + " pings; choose one of them");
                }
                // One unbound speedhunt fake per participant; it waits for the next speedhunt, whoever it targets.
                if (parameters.has("runToken")) {
                    if (!runningFakeOpen(published, parameters)) {
                        throw conflict("That speedhunt ping is no longer available; choose another ping");
                    }
                    if (GameJokerService.speedhuntFakeHeld(connection, member.gameSessionId(),
                            accepted.targetDeviceId(), parameters.get("runToken").asText())) {
                        throw conflict("You already have a fake for this speedhunt");
                    }
                } else if (GameJokerService.speedhuntFakeHeld(connection, member.gameSessionId(),
                        accepted.targetDeviceId(), null)) {
                    throw conflict("You already have a fake for the next speedhunt");
                }
            }
            if (target.hasNonNull("name")) {
                targetLabel = target.get("name").asText();
            }
            // Speedhunt actions on one participant are serialized; a participant has one open request
            // per joker type.
            Set<String> conflicting = joker ? Set.of(accepted.type()) : HUNTER_TYPES;
            try (var query = connection.prepareStatement("SELECT type FROM tc_game_requests WHERE"
                    + " gamesessionid = ? AND targetdeviceid = ? AND status IN (?,?,?)")) {
                query.setLong(1, member.gameSessionId());
                query.setLong(2, accepted.targetDeviceId());
                query.setString(3, PENDING);
                query.setString(4, APPROVED);
                query.setString(5, EXECUTING);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        if (conflicting.contains(rows.getString(1))) {
                            throw conflict(joker ? "You already have an open request for this joker"
                                    : "Another request for this participant is still open");
                        }
                    }
                }
            }
            Instant now = now();
            long id;
            try (var query = connection.prepareStatement("INSERT INTO tc_game_requests (gamesessionid,type,"
                    + "requesteruserid,requesterlabel,requesterrole,targetdeviceid,targetlabel,baserevision,"
                    + "parameters,status,createdat,expiresat,updatedat) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                query.setLong(1, member.gameSessionId());
                query.setString(2, accepted.type());
                query.setLong(3, member.userId());
                query.setString(4, userLabel(connection, member.userId()));
                query.setString(5, member.role());
                query.setLong(6, accepted.targetDeviceId());
                query.setString(7, text(targetLabel, 128));
                if (accepted.baseRevision() == null) {
                    query.setNull(8, java.sql.Types.BIGINT);
                } else {
                    query.setLong(8, accepted.baseRevision());
                }
                query.setString(9, objectMapper.writeValueAsString(parameters));
                query.setString(10, PENDING);
                query.setTimestamp(11, Timestamp.from(now), utc());
                query.setTimestamp(12, Timestamp.from(now.plusSeconds(EXPIRY_SECONDS)), utc());
                query.setTimestamp(13, Timestamp.from(now), utc());
                query.executeUpdate();
                try (var keys = query.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            if (joker) {
                String jokerType = GameJokerService.REQUEST_TYPES.get(accepted.type());
                // A fake's or planned break's reservation carries its plan, which stays private to its owner and the
                // operators.
                boolean withPlan = GameJokerService.FAKE_TYPES.contains(jokerType)
                        || GameJokerService.BREAK.equals(jokerType) && parameters.has("startsAt");
                GameJokerService.reserve(connection, member.gameSessionId(), accepted.targetDeviceId(), jokerType, id,
                        userLabel(connection, member.userId()), withPlan ? parameters : null);
            }
            return memberView(load(connection, member.gameSessionId(), id, false), hunterLabels(connection, member));
        });
    }

    /**
     * An operator plans a participant's break (or starts one now, when {@code startsAt} is missing or has passed):
     * the break joker is armed until the Tool starts the break. The same rules as for a participant's planned break
     * apply, and without an available joker the operator must confirm a use beyond the allocation.
     */
    public GameJokerService.Reservation planBreak(long gameSessionId, GameJokerService.BreakPlan plan, String actor)
            throws Exception {
        if (plan == null || plan.deviceId() == null) {
            throw new BadRequestException("Participant required");
        }
        Instant requested = now();
        if (plan.startsAt() != null && !plan.startsAt().isBlank()) {
            try {
                requested = Instant.parse(plan.startsAt());
            } catch (java.time.format.DateTimeParseException error) {
                throw new BadRequestException("The start of a break must be a time such as 2026-11-09T14:00:00Z");
            }
        }
        Instant start = (requested.isAfter(now()) ? requested : now()).truncatedTo(ChronoUnit.SECONDS);
        return transaction(connection -> {
            try (var query = connection.prepareStatement(
                    "SELECT id FROM tc_game_sessions WHERE id = ? FOR UPDATE")) {
                query.setLong(1, gameSessionId);
                query.executeQuery().close();
            }
            if (!GameJokerService.participant(connection, gameSessionId, plan.deviceId())) {
                throw new BadRequestException("Breaks belong to players and supporters of this session");
            }
            Double minutes = GameJokerService.breakMinutes(connection, gameSessionId);
            if (minutes == null) {
                throw conflict("Set a positive break duration in the game settings first");
            }
            JsonNode target = participant(statusDocument(connection, gameSessionId), plan.deviceId());
            if (target != null && target.path("caught").asBoolean(false)) {
                throw conflict("This participant is out of the game");
            }
            if (GameJokerService.breakOverlaps(connection, gameSessionId, plan.deviceId(), start, minutes, target,
                    now())) {
                throw conflict("This break would overlap another break of this participant");
            }
            // Planning a break beyond the allocation grants a break joker in effect: hunters must see only mirrors.
            GameAccessRule.checkGrant(connection, gameSessionId, GameJokerService.BREAK);
            return GameJokerService.armBreak(connection, gameSessionId, plan.deviceId(), start,
                    Boolean.TRUE.equals(plan.overAllocate()), actor);
        });
    }

    /**
     * The scheduled time of a timetable entry a fake may replace: the owner's own active entry that reaches hunters
     * (blank or a target code with hunters) and is at least the cutoff ahead.
     */
    private Instant fakeTimetableEntry(Connection connection, long gameSessionId, long deviceId, long entryId)
            throws SQLException {
        String owner = GameJokerService.deviceName(connection, deviceId);
        try (var query = connection.prepareStatement("SELECT scheduledat,participant,content,active"
                + " FROM tc_game_timetable_entries WHERE id = ? AND gamesessionid = ?")) {
            query.setLong(1, entryId);
            query.setLong(2, gameSessionId);
            try (var rows = query.executeQuery()) {
                if (!rows.next() || !rows.getBoolean("active") || owner == null
                        || !owner.equals(rows.getString("participant"))) {
                    throw new BadRequestException("Choose one of your upcoming timetable pings");
                }
                if (!GameJokerService.fakeEligible(rows.getString("content"))) {
                    throw conflict("This ping does not reach the hunters, so it cannot carry a fake");
                }
                Instant scheduled = rows.getTimestamp("scheduledat", utc()).toInstant();
                if (!scheduled.isAfter(now().plusSeconds(GameJokerService.FAKE_TIMETABLE_CUTOFF_SECONDS))) {
                    throw conflict("This ping is too close to carry a fake");
                }
                return scheduled;
            }
        }
    }

    /** Hunters and spectators see every speedhunt request of their session; others only their own. */
    public List<Request> list(Membership member, int requestedLimit) throws Exception {
        int limit = requestedLimit <= 0 ? 50 : Math.min(requestedLimit, 200);
        boolean shared = "hunter".equals(member.role()) || "spectator".equals(member.role());
        return transaction(connection -> {
            HunterLabels labels = hunterLabels(connection, member);
            List<Request> result = new ArrayList<>();
            String sql = "SELECT * FROM tc_game_requests WHERE gamesessionid = ?"
                    + (shared ? " AND (requesteruserid = ? OR type IN ("
                            + String.join(",", HUNTER_TYPES.stream().map(type -> "'" + type + "'").toList())
                            + "))" : " AND requesteruserid = ?")
                    + " ORDER BY id DESC";
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                query.setLong(1, member.gameSessionId());
                query.setLong(2, member.userId());
                query.setMaxRows(limit);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        result.add(memberView(request(rows), labels));
                    }
                }
            }
            return result;
        });
    }

    public Request cancel(Membership member, long id) throws Exception {
        return transaction(connection -> {
            GameJokerService.lockSessionForCancellation(connection, member.gameSessionId());
            Request request = load(connection, member.gameSessionId(), id, true);
            if (request.requesterUserId() == null || request.requesterUserId() != member.userId()) {
                throw new ForbiddenException("Only the requester can cancel this request");
            }
            if (!PENDING.equals(request.status()) && !APPROVED.equals(request.status())) {
                throw conflict("The request can no longer be cancelled");
            }
            update(connection, id, CANCELLED, "Cancelled by the requester", null, false);
            return memberView(load(connection, member.gameSessionId(), id, false), hunterLabels(connection, member));
        });
    }

    // --- controller operations ----------------------------------------------------------------------------

    private void update(Connection connection, long id, String status, String message, String detail,
                        boolean decided) throws SQLException {
        Instant now = now();
        boolean terminal = !OPEN.contains(status);
        try (var query = connection.prepareStatement("UPDATE tc_game_requests SET status = ?, message = ?,"
                + " detail = ?, updatedat = ?" + (decided ? ", decidedat = ?" : "")
                + (terminal ? ", completedat = ?" : "") + " WHERE id = ?")) {
            int index = 1;
            query.setString(index++, status);
            query.setString(index++, text(message, 512));
            query.setString(index++, text(detail, 512));
            query.setTimestamp(index++, Timestamp.from(now), utc());
            if (decided) {
                query.setTimestamp(index++, Timestamp.from(now), utc());
            }
            if (terminal) {
                query.setTimestamp(index++, Timestamp.from(now), utc());
            }
            query.setLong(index, id);
            query.executeUpdate();
        }
        if (terminal) {
            GameJokerService.settle(connection);
        }
    }

    public List<Request> operatorList(long gameSessionId, boolean openOnly, int requestedLimit) throws Exception {
        int limit = requestedLimit <= 0 ? 100 : Math.min(requestedLimit, 500);
        return transaction(connection -> {
            List<Request> result = new ArrayList<>();
            try (var query = connection.prepareStatement("SELECT * FROM tc_game_requests WHERE gamesessionid = ?"
                    + (openOnly ? " AND status IN ('pending','approved','executing') ORDER BY id"
                            : " ORDER BY id DESC"))) {
                query.setLong(1, gameSessionId);
                query.setMaxRows(limit);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        result.add(request(rows));
                    }
                }
            }
            return result;
        });
    }

    /**
     * The controller's decision on a pending request: {@code await} (operator approval needed), {@code approve}
     * or {@code reject}.
     */
    public Request decide(long gameSessionId, long id, String decision, String message, String detail)
            throws Exception {
        return transaction(connection -> {
            Request request = load(connection, gameSessionId, id, true);
            switch (decision == null ? "" : decision) {
                case "await" -> {
                    if (!PENDING.equals(request.status())) {
                        throw conflict("Only pending requests can wait for approval");
                    }
                    try (var query = connection.prepareStatement("UPDATE tc_game_requests SET"
                            + " approvalrequired = ?, message = ?, updatedat = ? WHERE id = ?")) {
                        query.setBoolean(1, true);
                        query.setString(2, text(message, 512));
                        query.setTimestamp(3, Timestamp.from(now()), utc());
                        query.setLong(4, id);
                        query.executeUpdate();
                    }
                }
                case "approve" -> {
                    if (!PENDING.equals(request.status())) {
                        throw conflict("Only pending requests can be approved");
                    }
                    update(connection, id, APPROVED, message, detail, true);
                }
                case "reject" -> {
                    if (!PENDING.equals(request.status()) && !APPROVED.equals(request.status())) {
                        throw conflict("The request is no longer open");
                    }
                    update(connection, id, REJECTED, message, detail, true);
                }
                default -> throw new BadRequestException("Decision must be await, approve or reject");
            }
            return load(connection, gameSessionId, id, false);
        });
    }

    /** Claims a pending or approved request for execution; an expired request can no longer be claimed. */
    public Request claim(long gameSessionId, long id, long epoch) throws Exception {
        return transaction(connection -> {
            Request request = load(connection, gameSessionId, id, true);
            if (!PENDING.equals(request.status()) && !APPROVED.equals(request.status())) {
                throw conflict("The request is no longer open");
            }
            if (!Instant.parse(request.expiresAt()).isAfter(now())) {
                update(connection, id, EXPIRED, "No one handled this request in time", null, false);
                return load(connection, gameSessionId, id, false);
            }
            try (var query = connection.prepareStatement("UPDATE tc_game_requests SET status = ?, epoch = ?,"
                    + " updatedat = ? WHERE id = ?")) {
                query.setString(1, EXECUTING);
                query.setLong(2, epoch);
                query.setTimestamp(3, Timestamp.from(now()), utc());
                query.setLong(4, id);
                query.executeUpdate();
            }
            return load(connection, gameSessionId, id, false);
        });
    }

    /** Records outcomes inside an effects transaction. */
    List<Request> complete(Connection connection, long gameSessionId, List<Completion> completions) throws Exception {
        List<Request> result = new ArrayList<>();
        for (Completion completion : completions) {
            if (completion == null || completion.id() == null || completion.status() == null) {
                throw new BadRequestException("Invalid request outcome");
            }
            Request request = load(connection, gameSessionId, completion.id(), true);
            boolean allowed = switch (completion.status()) {
                case SUCCEEDED -> EXECUTING.equals(request.status());
                case FAILED -> OPEN.contains(request.status());
                case REJECTED -> PENDING.equals(request.status()) || APPROVED.equals(request.status());
                default -> false;
            };
            if (!allowed) {
                if (request.status().equals(completion.status())) {
                    result.add(request);
                    continue;
                }
                throw conflict("Request " + completion.id() + " cannot become " + completion.status()
                        + " from " + request.status());
            }
            if (SUCCEEDED.equals(completion.status()) && FAKE_PING_REQUEST.equals(request.type())
                    && request.parameters().hasNonNull("entryId")) {
                // Checked again right before the fake is armed: the timetable may have changed meanwhile. A refusal
                // is a 400, so the Tool records the request as failed and the joker returns.
                try {
                    fakeTimetableEntry(connection, gameSessionId, request.targetDeviceId(),
                            request.parameters().path("entryId").asLong(0));
                } catch (WebApplicationException error) {
                    throw new BadRequestException("The fake cannot be armed: " + error.getMessage());
                }
            }
            if (SUCCEEDED.equals(completion.status()) && FAKE_PING_REQUEST.equals(request.type())
                    && request.parameters().hasNonNull("runToken")
                    && !runningFakeOpen(statusDocument(connection, gameSessionId), request.parameters())) {
                throw new BadRequestException("That speedhunt ping is no longer available; choose another ping");
            }
            update(connection, completion.id(), completion.status(), completion.message(), completion.detail(),
                    REJECTED.equals(completion.status()));
            result.add(load(connection, gameSessionId, completion.id(), false));
        }
        return result;
    }

    /** Expires requests awaiting approval or execution past their deadline; returns the affected sessions. */
    public Set<Long> expire() throws Exception {
        return transaction(connection -> {
            Set<Long> sessions = new LinkedHashSet<>();
            List<Long> ids = new ArrayList<>();
            try (var query = connection.prepareStatement("SELECT id,gamesessionid FROM tc_game_requests"
                    + " WHERE status IN (?,?) AND expiresat <= ? FOR UPDATE")) {
                query.setString(1, PENDING);
                query.setString(2, APPROVED);
                query.setTimestamp(3, Timestamp.from(now()), utc());
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        ids.add(rows.getLong(1));
                        sessions.add(rows.getLong(2));
                    }
                }
            }
            for (long id : ids) {
                update(connection, id, EXPIRED, "No one handled this request in time", null, false);
            }
            return sessions;
        });
    }

    /**
     * Invalidates every open request when control of the game changes; history is kept. Runs inside the
     * controller's transaction.
     */
    static Set<Long> invalidateOpen(Connection connection, Instant now, String reason) throws SQLException {
        Set<Long> sessions = new LinkedHashSet<>();
        try (var query = connection.prepareStatement("SELECT DISTINCT gamesessionid FROM tc_game_requests"
                + " WHERE status IN ('pending','approved','executing')")) {
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    sessions.add(rows.getLong(1));
                }
            }
        }
        for (var change : Map.of("pending", EXPIRED, "approved", EXPIRED, "executing", FAILED).entrySet()) {
            try (var query = connection.prepareStatement("UPDATE tc_game_requests SET status = ?, message = ?,"
                    + " updatedat = ?, completedat = ? WHERE status = ?")) {
                query.setString(1, change.getValue());
                query.setString(2, reason);
                query.setTimestamp(3, Timestamp.from(now), utc());
                query.setTimestamp(4, Timestamp.from(now), utc());
                query.setString(5, change.getKey());
                query.executeUpdate();
            }
        }
        GameJokerService.settle(connection);
        return sessions;
    }

    /** The session each listed user is a member of; users that take part in no game are absent. */
    public Map<Long, Long> sessionsOf(java.util.Collection<Long> userIds) throws Exception {
        Map<Long, Long> result = new java.util.HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        try (var connection = dataSource.getConnection();
             var query = connection.prepareStatement("SELECT userid,gamesessionid FROM tc_game_session_members")) {
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    if (userIds.contains(rows.getLong(1))) {
                        result.put(rows.getLong(1), rows.getLong(2));
                    }
                }
            }
        }
        return result;
    }
}
