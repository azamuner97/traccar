/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.database;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

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
    private static final Set<String> SPEEDHUNT_PINGS = Set.of("speedhuntStaticPing", "speedhuntLivePing");
    private static final int MAX_LIVE_MINUTES = 240;

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

    /** The view a member sees: the operator-only detail is removed. */
    private static Request memberView(Request request) {
        return new Request(request.id(), request.gameSessionId(), request.type(), request.requesterUserId(),
                request.requesterLabel(), request.requesterRole(), request.targetDeviceId(), request.targetLabel(),
                request.baseRevision(), request.parameters(), request.status(), request.approvalRequired(),
                request.message(), null, request.createdAt(), request.expiresAt(), request.decidedAt(),
                request.completedAt());
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

    /** Whether any participant's speedhunt runs (a run or a live ping), as published by the Tool. */
    static boolean speedhuntRunning(JsonNode status) {
        if (status == null || !status.path("participants").isArray()) {
            return false;
        }
        for (JsonNode item : status.get("participants")) {
            JsonNode speedhunt = item.path("speedhunt");
            if (speedhunt.path("running").asBoolean(false) || speedhunt.path("live").asBoolean(false)) {
                return true;
            }
        }
        return false;
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

    /** Whether the controller lease is active and executes the given session. */
    private boolean executing(Connection connection, long gameSessionId) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT state,expiresat,gamesessionid FROM tc_game_controller WHERE id = 1")) {
            try (var rows = query.executeQuery()) {
                if (!rows.next()) {
                    return false;
                }
                Timestamp expires = rows.getTimestamp("expiresat", utc());
                Long session = nullableLong(rows, "gamesessionid");
                return GameControllerService.STATE_ACTIVE.equals(rows.getString("state")) && expires != null
                        && expires.toInstant().isAfter(now()) && session != null && session == gameSessionId;
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

    /**
     * The status a member may see. Hunters and spectators see the named speedhunt state and who is on a break;
     * players and supporters only learn whether any speedhunt runs, never whom it targets, and their own break.
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
            JsonNode filtered = filter(document, member.role(), active, member.assignedDeviceId());
            if (("player".equals(member.role()) || "supporter".equals(member.role()))
                    && member.assignedDeviceId() != null) {
                // Owners see their own armed fake plans; nobody else ever does.
                ((ObjectNode) filtered).set("fakes",
                        GameJokerService.ownFakes(connection, member.gameSessionId(), member.assignedDeviceId()));
            }
            return new Status(member.gameSessionId(), member.role(), active, now().toString(), revision, filtered);
        });
    }

    static JsonNode filter(JsonNode document, String role, boolean active, Long deviceId) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.putObject("speedhunt").put("active", active && speedhuntRunning(document));
        if (("hunter".equals(role) || "spectator".equals(role)) && document != null) {
            result.set("participants", document.path("participants").deepCopy());
            result.set("limits", document.path("limits").deepCopy());
        } else if (("player".equals(role) || "supporter".equals(role)) && deviceId != null) {
            JsonNode own = participant(document, deviceId);
            if (own != null && own.path("break").isObject()) {
                result.set("break", own.get("break").deepCopy());
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
        String serialized = objectMapper.writeValueAsString(document);
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
            result.put("latitude", coordinate(input, "latitude", 90));
            result.put("longitude", coordinate(input, "longitude", 180));
            return result;
        }
        if ("speedhuntLivePing".equals(submission.type()) && submission.parameters() != null
                && submission.parameters().has("durationMinutes")
                && !submission.parameters().get("durationMinutes").isNull()) {
            JsonNode minutes = submission.parameters().get("durationMinutes");
            if (!minutes.isNumber() || minutes.asDouble() <= 0 || minutes.asDouble() > MAX_LIVE_MINUTES) {
                throw new BadRequestException("Live ping duration must be between 0 and " + MAX_LIVE_MINUTES
                        + " minutes");
            }
            result.put("durationMinutes", minutes.asDouble());
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
            if (SPEEDHUNT_PINGS.contains(accepted.type()) && onBreak(target, now())) {
                throw conflict("This participant is on a break");
            }
            if (BREAK_REQUEST.equals(accepted.type())) {
                if (GameJokerService.breakMinutes(connection, member.gameSessionId()) == null) {
                    throw conflict("Breaks are not set up for this game");
                }
                if (onBreak(target, now())) {
                    throw conflict("You are already on a break");
                }
                if (speedhuntRunning(published)) {
                    throw conflict(BREAK_UNAVAILABLE);
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
                if (GameJokerService.speedhuntFakeHeld(connection, member.gameSessionId(),
                        accepted.targetDeviceId())) {
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
                // A fake's reservation carries its plan, which stays private to its owner and the operators.
                GameJokerService.reserve(connection, member.gameSessionId(), accepted.targetDeviceId(), jokerType, id,
                        userLabel(connection, member.userId()),
                        GameJokerService.FAKE_TYPES.contains(jokerType) ? parameters : null);
            }
            return memberView(load(connection, member.gameSessionId(), id, false));
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
                        result.add(memberView(request(rows)));
                    }
                }
            }
            return result;
        });
    }

    public Request cancel(Membership member, long id) throws Exception {
        return transaction(connection -> {
            Request request = load(connection, member.gameSessionId(), id, true);
            if (request.requesterUserId() == null || request.requesterUserId() != member.userId()) {
                throw new ForbiddenException("Only the requester can cancel this request");
            }
            if (!PENDING.equals(request.status()) && !APPROVED.equals(request.status())) {
                throw conflict("The request can no longer be cancelled");
            }
            update(connection, id, CANCELLED, "Cancelled by the requester", null, false);
            return memberView(load(connection, member.gameSessionId(), id, false));
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
