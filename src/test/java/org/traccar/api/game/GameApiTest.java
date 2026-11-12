package org.traccar.api.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.traccar.api.security.PermissionsService;
import org.traccar.game.GameAccessRule;
import org.traccar.game.GameBindingHook;
import org.traccar.game.GameDocumentService;
import org.traccar.game.GameEffectsService;
import org.traccar.game.GameJokerRepository;
import org.traccar.game.GameJokerService;
import org.traccar.game.GameLogService;
import org.traccar.game.GameMember;
import org.traccar.game.GameNotificationService;
import org.traccar.game.GameOperation;
import org.traccar.game.GamePlanInvalidation;
import org.traccar.game.GameProximityService;
import org.traccar.game.GameRequest;
import org.traccar.game.GameRequestService;
import org.traccar.game.GameSessionService;
import org.traccar.game.GameSnapshotService;
import org.traccar.game.GameState;
import org.traccar.game.GameStatusService;
import org.traccar.game.GameTestBase;
import org.traccar.game.GameTimetableService;
import org.traccar.model.Device;
import org.traccar.model.User;
import org.traccar.storage.Storage;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The REST resources of game data model 2 (step 4), called directly on real services over the model-2 schema:
 * operator endpoints, members resolved from their accounts, ISO-8601 times, and model 1's privacy cases through the
 * JSON that members receive. Runs on H2 ({@link GameApiH2Test}) and MySQL ({@link GameApiMySqlTest}).
 */
public abstract class GameApiTest extends GameTestBase {

    private static final long EPOCH = 31;
    private static final String SETTINGS = "{\"pings_per_speedhunt\": 3, \"break_duration_minutes\": 10,"
            + " \"fixed_speedhunt_live_duration_minutes\": 2}";
    private static final String HEADER = Long.toString(EPOCH);
    /** Fields players and supporters must never receive from any member endpoint. */
    private static final Set<String> PRIVATE_FIELDS = Set.of("revision", "stateRevision", "source", "boundRunId",
            "targetMemberId", "liveShares", "lastSpeedhuntMemberId", "participants", "outcome", "fakePlanId",
            "epoch", "detail");

    private final PermissionsService permissions = mock(PermissionsService.class);
    private final Storage storage = mock(Storage.class);
    private final Set<Long> administrators = new HashSet<>();
    private final List<Object> services = new ArrayList<>();
    private long caller;
    private long operator;
    private int deliveries;

    private GameSessionResource sessionsApi;
    private GameStatusResource statusApi;
    private GameRequestResource requestsApi;
    private GameJokerResource jokersApi;
    private GameTimetableResource timetableApi;
    private GameNotificationResource notificationsApi;
    private GameProximityResource proximityApi;

    @Override
    protected Set<GameBindingHook> hooks() {
        return Set.of(new GamePlanInvalidation(publisher), new GameAccessRule(transactions));
    }

    @Override
    protected void createServices() throws Exception {
        services.addAll(List.of(permissions, storage, sessions, members, resources,
                new GameStatusService(transactions), new GameSnapshotService(transactions, mapper),
                new GameRequestService(transactions, mapper, publisher), new GameJokerService(transactions, publisher),
                new GameTimetableService(transactions, mapper, publisher),
                new GameDocumentService(transactions, mapper, cipher()), new GameLogService(transactions),
                new GameEffectsService(transactions, mapper, publisher),
                new GameNotificationService(transactions, publisher), new GameProximityService(transactions, publisher)));
        sessionsApi = wire(new GameSessionResource() {
            @Override
            protected long getUserId() {
                return caller;
            }
        });
        statusApi = wire(new GameStatusResource() {
            @Override
            protected long getUserId() {
                return caller;
            }
        });
        requestsApi = wire(new GameRequestResource() {
            @Override
            protected long getUserId() {
                return caller;
            }
        });
        jokersApi = wire(new GameJokerResource() {
            @Override
            protected long getUserId() {
                return caller;
            }
        });
        timetableApi = wire(new GameTimetableResource() {
            @Override
            protected long getUserId() {
                return caller;
            }
        });
        notificationsApi = wire(new GameNotificationResource() {
            @Override
            protected long getUserId() {
                return caller;
            }
        });
        proximityApi = wire(new GameProximityResource() {
            @Override
            protected long getUserId() {
                return caller;
            }
        });
    }

    /** Sets every injected field of a resource from the service of its type. */
    private <T> T wire(T resource) throws Exception {
        for (Class<?> type = resource.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isAnnotationPresent(Inject.class)) {
                    Object value = services.stream().filter(field.getType()::isInstance).findFirst()
                            .orElseThrow(() -> new IllegalStateException("No service for " + field));
                    field.setAccessible(true);
                    field.set(resource, value);
                }
            }
        }
        return resource;
    }

    @BeforeEach
    void permissions() throws Exception {
        operator = user();
        administrators.add(operator);
        when(permissions.notAdmin(anyLong())).thenAnswer(call -> !administrators.contains((long) call.getArgument(0)));
        doAnswer(call -> {
            if (!administrators.contains((long) call.getArgument(0))) {
                throw new SecurityException("Administrator access required");
            }
            return null;
        }).when(permissions).checkAdmin(anyLong());
        when(permissions.getUser(anyLong())).thenAnswer(call -> {
            User user = new User();
            user.setName("Operator " + call.getArgument(0));
            return user;
        });
        when(storage.getObjects(eq(Device.class), any())).thenReturn(List.of());
        caller = operator;
    }

    @AfterEach
    void resetClock() {
        clock.set(T0);
    }

    // Helpers -----------------------------------------------------------------------------------------------

    private void as(GameMember member) {
        caller = member.userId();
    }

    private void asOperator() {
        caller = operator;
    }

    private static void assertCode(int expected, Executable call) {
        assertEquals(expected, assertThrows(WebApplicationException.class, call).getResponse().getStatus());
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> result = new HashSet<>();
        if (node.isObject()) {
            for (Iterator<String> names = node.fieldNames(); names.hasNext();) {
                String name = names.next();
                result.add(name);
                result.addAll(fieldNames(node.get(name)));
            }
        } else if (node.isArray()) {
            node.forEach(item -> result.addAll(fieldNames(item)));
        }
        return result;
    }

    private static void assertNoPrivateFields(JsonNode node) {
        Set<String> leaked = fieldNames(node);
        leaked.retainAll(PRIVATE_FIELDS);
        assertEquals(Set.of(), leaked, node.toString());
    }

    /** The status JSON without the caller's own identity. */
    private static JsonNode observable(JsonNode status) {
        ObjectNode copy = status.deepCopy();
        copy.remove(List.of("memberId", "number", "displayName"));
        return copy;
    }

    private record Game(long session, GameMember anna, GameMember ben, GameMember carl, GameMember hunter,
                        GameMember spectator) {
    }

    private Game game() throws Exception {
        long session = session(SETTINGS);
        lease(session, EPOCH);
        return new Game(session, bound(session, "player"), bound(session, "player"), bound(session, "supporter"),
                bound(session, "hunter"), bound(session, "spectator"));
    }

    private JsonNode deliver(long session, List<GameRequestService.Completion> completions,
                             GameOperation... operations) throws Exception {
        return sessionsApi.applyEffects(session, HEADER, new GameEffectsService.Delivery("api-delivery-"
                + ++deliveries, null, List.of(operations), null, null, null, completions, null));
    }

    private static GameOperation.PingDelivered ping(GameMember target, int number) {
        return new GameOperation.PingDelivered(target.id(), number, GameState.STATIC, GameState.REAL, true, true,
                true, null, null, "Operator", true);
    }

    private JsonNode grant(long session, GameMember member, String type, int quantity) throws Exception {
        return sessionsApi.changeJokers(session, new GameJokerService.Change(member == null ? null : member.id(),
                type, quantity, "test"));
    }

    // Operators ---------------------------------------------------------------------------------------------

    @Test
    void operatorsManageSessionsMembersAndResources() throws Exception {
        assertEquals(new GameSessionResource.Capabilities(5), sessionsApi.capabilities());
        assertEquals(5, GameSessionService.API_VERSION);
        String name = "api-" + UUID.randomUUID();
        JsonNode created = sessionsApi.create(new GameSessionResource.CreateRequest(name, 5, null,
                json("{\"game_settings\": " + SETTINGS + "}")));
        long session = created.get("id").asLong();
        assertEquals(1, created.get("revision").asLong());
        assertEquals(T0.toString(), created.get("createdAt").asText());
        assertEquals(5, created.path("settings").path("schemaVersion").asInt());
        assertEquals(3, created.path("settings").path("settings").path("game_settings")
                .path("pings_per_speedhunt").asInt());
        assertTrue(sessionsApi.list().findValues("name").stream().anyMatch(value -> value.asText().equals(name)));

        clock.advance(Duration.ofMinutes(1));
        JsonNode renamed = sessionsApi.rename(session, new GameSessionResource.RenameRequest(1L, name + "-b"));
        assertEquals(2, renamed.path("settings").path("revision").asLong());
        assertCode(409, () -> sessionsApi.updateSettings(session,
                new GameSessionResource.DocumentUpdate(1L, 5, json("{}"))));
        assertCode(400, () -> sessionsApi.rename(session, new GameSessionResource.RenameRequest(null, name)));
        JsonNode updated = sessionsApi.updateSettings(session, new GameSessionResource.DocumentUpdate(2L, 6,
                json("{\"game_settings\": {}}")));
        assertEquals(6, updated.path("settings").path("schemaVersion").asInt());

        JsonNode anna = sessionsApi.plan(session, new GameSessionResource.MemberInput("player", null, "Anna"));
        long annaId = anna.get("id").asLong();
        assertEquals(1, anna.get("number").asInt());
        assertEquals(1, anna.get("stateRevision").asLong());
        int annaUser = user();
        int annaTracker = device();
        sessionsApi.bindAccount(session, annaId, new GameSessionResource.AccountInput((long) annaUser));
        JsonNode bound = sessionsApi.bindTracker(session, annaId, new GameSessionResource.TrackerInput(
                (long) annaTracker));
        assertEquals(annaUser, bound.get("userId").asLong());
        assertEquals(annaTracker, bound.get("trackerId").asLong());
        assertEquals("Anna B", sessionsApi.renameMember(session, annaId,
                new GameSessionResource.NameInput("Anna B")).get("displayName").asText());
        JsonNode hunter = sessionsApi.plan(session, new GameSessionResource.MemberInput("hunter", null, "Hugo"));
        assertEquals(2, sessionsApi.members(session).size());
        assertEquals(annaId, sessionsApi.member(session, annaId).get("id").asLong());
        assertCode(404, () -> sessionsApi.member(session, annaId + 1000));

        // Resource rows, with their lifecycle.
        long mirrorDevice = device();
        JsonNode mirror = sessionsApi.addMirror(session, new GameSessionResource.MirrorInput(mirrorDevice, annaId,
                "hunter", null));
        long mirrorId = mirror.get("id").asLong();
        sessionsApi.addGroup(session, new GameSessionResource.GroupInput((long) group(), "player", null));
        sessionsApi.addGeofence(session, new GameSessionResource.GeofenceInput(
                insert("INSERT INTO tc_geofences (name, area) VALUES ('Zone', 'CIRCLE (1 1, 100)')"), "zone-1",
                "planned"));
        JsonNode rows = sessionsApi.resources(session);
        assertEquals(1, rows.get("mirrors").size());
        assertEquals(1, rows.get("groups").size());
        assertEquals(1, rows.get("geofences").size());
        assertEquals("retiring", sessionsApi.retireResource(session, "mirrors", mirrorId).get("state").asText());
        assertEquals("active", sessionsApi.reactivateResource(session, "mirrors", mirrorId).get("state").asText());
        long replacement = device();
        assertEquals(replacement, sessionsApi.rebindResource(session, "mirrors", mirrorId,
                new GameSessionResource.RebindInput(replacement)).get("deviceId").asLong());
        sessionsApi.retireResource(session, "mirrors", mirrorId);
        assertEquals(204, sessionsApi.removeResource(session, "mirrors", mirrorId).getStatus());
        assertCode(404, () -> sessionsApi.retireResource(session, "drawings", 1));

        // Documents, checkpoints, timetable and logs.
        assertEquals(1, sessionsApi.updateGeofencePlans(session, new GameSessionResource.DocumentUpdate(0L, 1,
                json("{\"zones\": []}"))).revision());
        assertEquals(1, sessionsApi.geofencePlans(session).get("revision").asLong());
        assertEquals(1, sessionsApi.updateCredentials(session, new GameSessionResource.DocumentUpdate(0L, 1,
                json("{\"base\": \"secret\"}"))).revision());
        assertEquals("secret", ((JsonNode) sessionsApi.credentials(session).getEntity()).path("document")
                .path("base").asText());
        JsonNode checkpoint = sessionsApi.putCheckpoint(session, "monitoring",
                new GameSessionResource.DocumentUpdate(0L, 1, json("{\"cursor\": 1}")));
        assertEquals(T0.plus(Duration.ofMinutes(1)).toString(), checkpoint.get("updatedAt").asText());
        assertEquals(1, sessionsApi.checkpoints(session).size());
        assertEquals(1, sessionsApi.checkpoint(session, "monitoring").get("revision").asLong());
        assertEquals(204, sessionsApi.deleteCheckpoint(session, "monitoring").getStatus());
        assertEquals(0, sessionsApi.checkpoint(session, "monitoring").get("revision").asLong());
        JsonNode timetable = sessionsApi.publishTimetable(session, new GameSessionResource.TimetablePublish(0L,
                json("[\"Time\", \"Anna B\"]"), null, List.of(new GameTimetableService.EntryInput(
                        T0.plus(Duration.ofHours(1)).toString(), "Anna B", ""))));
        assertEquals(1, timetable.get("revision").asLong());
        long entry = timetable.get("entries").get(0).get("id").asLong();
        assertEquals(annaId, timetable.get("entries").get(0).get("memberId").asLong());
        assertEquals(204, sessionsApi.timetableProgress(session, new GameSessionResource.ProgressUpdate(
                List.of(new GameTimetableService.Progress(entry, "sent")))).getStatus());
        assertEquals("sent", sessionsApi.timetable(session).get("entries").get(0).get("status").asText());
        sessionsApi.appendLogs(session, new GameSessionResource.LogAppend(List.of(new GameLogService.LogInput(
                "speedhunt", T0.toString(), "Started"))));
        JsonNode logs = sessionsApi.logs(session, null, 0, "speedhunt");
        assertEquals("Started", logs.get("items").get(0).get("message").asText());
        assertEquals(T0.toString(), logs.get("items").get(0).get("occurredAt").asText());

        // The snapshot carries the aggregate revision and ISO-8601 times.
        JsonNode snapshot = (JsonNode) sessionsApi.snapshot(session).getEntity();
        assertEquals(sessions.get(session).revision(), snapshot.get("revision").asLong());
        assertEquals(clock.instant().toString(), snapshot.get("readAt").asText());
        assertEquals(2, snapshot.get("members").size());
        assertEquals("no-store", sessionsApi.snapshot(session).getHeaderString("Cache-Control"));

        // Role change and retirement.
        JsonNode supporter = sessionsApi.changeRole(session, annaId, new GameSessionResource.MemberInput(
                "supporter", null, "Anna S"));
        assertEquals(annaUser, supporter.get("userId").asLong());
        assertEquals("retired", sessionsApi.member(session, annaId).get("state").asText());
        sessionsApi.retire(session, hunter.get("id").asLong());
        assertEquals(204, sessionsApi.delete(session).getStatus());
        assertCode(404, () -> sessionsApi.get(session));
    }

    @Test
    void onlyOperatorsUseTheOperatorEndpoints() throws Exception {
        Game game = game();
        as(game.anna());
        long session = game.session();
        List<Executable> calls = List.of(sessionsApi::capabilities, sessionsApi::list,
                () -> sessionsApi.get(session), () -> sessionsApi.snapshot(session),
                () -> sessionsApi.members(session), () -> sessionsApi.jokerOverview(session),
                () -> sessionsApi.listRequests(session, null, 0), () -> sessionsApi.timetable(session),
                () -> sessionsApi.credentials(session), () -> sessionsApi.logs(session, null, 0, null),
                () -> grant(session, game.anna(), GameJokerRepository.FAKE_PING, 1),
                () -> deliver(session, List.of(), ping(game.anna(), 1)),
                () -> notificationsApi.history(session, null, 0), () -> notificationsApi.initialize(session),
                () -> proximityApi.get(session), () -> proximityApi.reset(session));
        for (Executable call : calls) {
            assertCode(403, call);
        }
        assertEquals(0, number("SELECT COUNT(*) FROM tc_game_speedhunts WHERE gamesessionid = ?", session));
    }

    @Test
    void effectsRequestsAndJokersGoThroughTheFencedEndpoints() throws Exception {
        Game game = game();
        long session = game.session();
        JsonNode pool = grant(session, null, GameJokerRepository.SPEEDHUNT, 2);
        assertTrue(pool.get("memberId").isNull());
        assertEquals(2, pool.get("available").asInt());
        grant(session, game.anna(), GameJokerRepository.BREAK, 1);
        JsonNode planned = sessionsApi.planBreak(session, new GameSessionResource.BreakPlan(game.anna().id(),
                T0.plus(Duration.ofHours(1)).toString(), false));
        assertEquals("plannedBreak", planned.get("use").asText());
        assertEquals(T0.plus(Duration.ofHours(1)).toString(), planned.path("plannedBreak").path("startsAt").asText());

        // A delivery needs the epoch the fence admitted; without one it is refused before anything happens.
        assertCode(423, () -> sessionsApi.applyEffects(session, null, new GameEffectsService.Delivery(
                "api-unfenced-1", null, List.of(ping(game.anna(), 1)), null, null, null, null, null)));
        JsonNode outcome = deliver(session, List.of(), ping(game.anna(), 1));
        assertEquals(EPOCH, outcome.get("epoch").asLong());
        assertEquals("real", outcome.get("operations").get(0).get("source").asText());

        // A hunter's request is decided, claimed under the epoch and completed in a delivery.
        as(game.hunter());
        long revision = memberRevision(game.carl().id());
        JsonNode request = requestsApi.submit(new GameRequestService.Submission(GameRequest.STATIC_PING,
                game.carl().id(), revision, null));
        long requestId = request.get("id").asLong();
        assertEquals("Supporter 1", request.get("targetLabel").asText());
        asOperator();
        assertEquals(1, sessionsApi.listRequests(session, null, 0).size());
        sessionsApi.decideRequest(session, requestId, new GameSessionResource.RequestDecision("approve", null,
                null));
        assertCode(423, () -> sessionsApi.claimRequest(session, requestId, null));
        assertEquals("executing", sessionsApi.claimRequest(session, requestId, HEADER).get("status").asText());
        deliver(session, List.of(new GameRequestService.Completion(requestId, GameRequest.SUCCEEDED, null, null)),
                ping(game.carl(), 1));
        assertEquals(0, sessionsApi.listRequests(session, null, 0).size());
        assertEquals(1, sessionsApi.listRequests(session, "all", 0).size());

        JsonNode overview = sessionsApi.jokerOverview(session);
        assertTrue(overview.get("inventories").size() > 0);
        assertTrue(sessionsApi.jokerLedger(session, 0).size() > 0);
        assertEquals(0, sessionsApi.reconcileJokers(session).size());
        JsonNode snapshot = (JsonNode) sessionsApi.snapshot(session).getEntity();
        assertEquals(2, snapshot.path("state").path("runs").size());
        assertEquals(EPOCH, snapshot.get("executingEpoch").asLong());
    }

    // Members -----------------------------------------------------------------------------------------------

    @Test
    void membersGetTheirRoleProjectionAndATargetLooksLikeANonTarget() throws Exception {
        Game game = game();
        long session = game.session();
        grant(session, null, GameJokerRepository.SPEEDHUNT, 2);
        deliver(session, List.of(), ping(game.anna(), 1));

        as(game.anna());
        JsonNode anna = statusApi.status();
        as(game.ben());
        JsonNode ben = statusApi.status();
        assertEquals(observable(anna), observable(ben));
        assertEquals(game.ben().id(), ben.get("memberId").asLong());
        assertEquals("player", ben.get("role").asText());
        assertTrue(ben.get("controllerActive").asBoolean());
        assertEquals(T0.toString(), ben.get("serverTime").asText());
        assertTrue(ben.get("hash").asText().matches("[0-9a-f]{32}"));
        assertTrue(ben.path("status").path("speedhunt").path("active").asBoolean());
        assertFalse(ben.path("status").path("break").path("active").asBoolean());
        assertEquals(3, ben.path("status").path("participantOverview").size());
        assertNoPrivateFields(anna);
        as(game.carl());
        assertNoPrivateFields(statusApi.status());
        assertEquals(observable(anna).get("status"), observable(statusApi.status()).get("status"));

        as(game.hunter());
        JsonNode hunter = statusApi.status();
        JsonNode participant = hunter.path("status").path("participants").get(0);
        assertEquals("Player 1", participant.get("name").asText());
        assertEquals(memberRevision(game.anna().id()), participant.path("speedhunt").path("revision").asLong());
        assertFalse(participant.has("trackerId"));
        assertEquals(game.anna().id(), hunter.path("status").path("lastSpeedhuntMemberId").asLong());
        as(game.spectator());
        JsonNode spectator = statusApi.status();
        assertEquals(game.anna().trackerId(), spectator.path("status").path("participants").get(0)
                .get("trackerId").asLong());
        assertFalse(spectator.path("status").has("liveShares"));

        // Anonymous progress: members of the executing session and administrators.
        as(game.anna());
        JsonNode progress = (JsonNode) statusApi.speedhunt().getEntity();
        assertEquals(1, progress.get("pingCount").asInt());
        assertFalse(progress.has("pingHistory"));
        asOperator();
        assertEquals(progress, statusApi.speedhunt().getEntity());
        assertCode(403, statusApi::speedhuntHistory);
        as(game.anna());
        JsonNode history = (JsonNode) statusApi.speedhuntHistory().getEntity();
        as(game.hunter());
        assertEquals(history, statusApi.speedhuntHistory().getEntity());
        assertNoPrivateFields(history);
        assertEquals(T0.toString(), history.path("runs").get(0).path("pings").get(0).path("sentAt").asText());

        // Accounts outside every game get 403 from every member endpoint.
        caller = user();
        for (Executable call : List.<Executable>of(statusApi::status, statusApi::speedhunt,
                statusApi::speedhuntHistory, () -> requestsApi.list(0), jokersApi::own, timetableApi::timetable,
                () -> notificationsApi.list(null, null, null, 0), proximityApi::circles, proximityApi::access)) {
            assertCode(403, call);
        }
    }

    @Test
    void membersUseAndSeeOnlyTheirOwnJokersRequestsAndPings() throws Exception {
        Game game = game();
        long session = game.session();
        grant(session, null, GameJokerRepository.SPEEDHUNT, 2);
        grant(session, game.anna(), GameJokerRepository.FAKE_PING, 2);
        grant(session, game.ben(), GameJokerRepository.FAKE_PING, 1);
        sessionsApi.publishTimetable(session, new GameSessionResource.TimetablePublish(0L,
                json("[\"Time\", \"" + game.anna().displayName() + "\", \"" + game.ben().displayName() + "\"]"),
                null, List.of(new GameTimetableService.EntryInput(T0.plus(Duration.ofHours(1)).toString(),
                        game.anna().displayName(), ""), new GameTimetableService.EntryInput(
                        T0.plus(Duration.ofHours(1)).toString(), game.ben().displayName(), "b"))));

        as(game.anna());
        JsonNode own = jokersApi.own();
        assertEquals(3, own.get("inventories").size());
        assertEquals(1, own.get("timetableEntries").size());
        assertEquals(3, own.get("pingsPerSpeedhunt").asInt());
        JsonNode fake = requestsApi.submit(new GameRequestService.Submission(GameRequest.FAKE_PING, null, null,
                json("{\"pingNumber\": 2, \"latitude\": 1.5, \"longitude\": 7}")));
        assertEquals("pending", fake.get("status").asText());
        assertEquals(1, requestsApi.list(0).size());
        JsonNode timetable = timetableApi.timetable();
        assertEquals(1, timetable.size());
        assertEquals(game.anna().id(), timetable.get(0).get("memberId").asLong());
        assertTrue(timetable.get(0).get("fakeAllowed").asBoolean());
        assertFalse(timetable.get(0).has("content"));

        as(game.ben());
        assertEquals(0, requestsApi.list(0).size());
        assertCode(403, () -> requestsApi.cancel(fake.get("id").asLong()));
        JsonNode benFake = requestsApi.submit(new GameRequestService.Submission(GameRequest.FAKE_PING, null, null,
                json("{\"pingNumber\": 1, \"latitude\": 2.5, \"longitude\": 7}")));
        asOperator();
        for (JsonNode request : List.of(fake, benFake)) {
            long id = request.get("id").asLong();
            sessionsApi.claimRequest(session, id, HEADER);
            deliver(session, List.of(new GameRequestService.Completion(id, GameRequest.SUCCEEDED, null, null)));
        }
        as(game.anna());
        JsonNode reservations = jokersApi.own().get("reservations");
        long annaReservation = reservations.get(0).get("id").asLong();
        assertFalse(reservations.get(0).path("fake").has("boundRunId"));
        assertEquals(1, ((JsonNode) statusApi.status()).path("status").path("fakes").size());

        // Probing another owner's reservation answers exactly like a missing one.
        as(game.ben());
        long benReservation = jokersApi.own().get("reservations").get(0).get("id").asLong();
        WebApplicationException other = assertThrows(WebApplicationException.class,
                () -> jokersApi.cancel(annaReservation));
        WebApplicationException missing = assertThrows(WebApplicationException.class,
                () -> jokersApi.cancel(annaReservation + benReservation + 1000));
        assertEquals(404, other.getResponse().getStatus());
        assertEquals(missing.getResponse().getStatus(), other.getResponse().getStatus());
        assertEquals(missing.getMessage(), other.getMessage());
        assertEquals("released", jokersApi.cancel(benReservation).get("status").asText());

        // Hunters see the pool and the times of hunter-facing pings; spectators no jokers but the pings.
        as(game.hunter());
        JsonNode poolView = jokersApi.own();
        assertEquals(GameJokerRepository.SPEEDHUNT, poolView.get("inventories").get(0).get("type").asText());
        assertEquals(List.of(T0.plus(Duration.ofHours(1)).toString()),
                timetableApi.timetable().findValuesAsText("scheduledAt"));
        assertFalse(timetableApi.timetable().get(0).has("memberId"));
        assertEquals(0, requestsApi.list(0).size());
        as(game.spectator());
        assertCode(403, jokersApi::own);
        assertCode(403, () -> jokersApi.cancel(annaReservation));
        assertEquals(1, timetableApi.timetable().size());
        assertEquals(game.anna().id(), timetableApi.timetable().get(0).get("memberId").asLong());
    }

    @Test
    void notificationsAndProximityResolveTheCallersMembership() throws Exception {
        Game game = game();
        long session = game.session();
        long other = session();
        String generation = notificationsApi.initialize(session).get("generation").asText();
        assertEquals(generation, notificationsApi.state(session).get("generation").asText());
        String time = T0.toString();
        notificationsApi.create(new GameNotificationResource.CreateRequest(session, generation,
                new GameNotificationService.Alert("caught-1", "playerCaught", "session", time, "Europe/Zurich",
                        "Player caught", null, null)));
        notificationsApi.create(new GameNotificationResource.CreateRequest(session, generation,
                new GameNotificationService.Alert("exit-anna", "geofenceExit", "user", time, "Europe/Zurich",
                        "You left the zone", game.anna().id(), game.anna().id())));
        notificationsApi.create(new GameNotificationResource.CreateRequest(session, generation,
                new GameNotificationService.Alert("hunt-1", "speedhunt", "hunters", time, "Europe/Zurich",
                        "Speedhunt started", null, null)));

        as(game.anna());
        JsonNode page = notificationsApi.list(null, generation, null, 0);
        assertEquals(List.of("caught-1", "exit-anna"), page.get("items").findValuesAsText("id"));
        assertEquals(time, page.get("items").get(0).get("occurredAt").asText());
        assertCode(403, () -> notificationsApi.list(other, null, null, 0));
        assertCode(403, () -> notificationsApi.history(session, null, 0));
        as(game.ben());
        assertEquals(List.of("caught-1"), notificationsApi.list(null, null, null, 0).get("items")
                .findValuesAsText("id"));
        as(game.hunter());
        assertEquals(List.of("caught-1", "hunt-1"), notificationsApi.list(session, null, null, 0).get("items")
                .findValuesAsText("id"));
        asOperator();
        assertEquals(3, notificationsApi.list(session, null, null, 0).get("items").size());
        assertEquals(3, notificationsApi.history(session, null, 0).get("items").size());
        String replacement = UUID.randomUUID().toString();
        assertEquals(replacement, notificationsApi.reset(session, new GameNotificationResource.ResetRequest(
                generation, replacement)).get("generation").asText());
        assertEquals(0, notificationsApi.list(session, null, null, 0).get("items").size());

        // Proximity: the operator publishes a reciprocal grant pair and a circle.
        String expires = T0.plus(Duration.ofMinutes(10)).toString();
        JsonNode published = proximityApi.replace(session, new GameProximityResource.ReplaceRequest(0L, List.of(
                new GameProximityService.GrantInput("ep-1", game.anna().id(), game.ben().id(), expires),
                new GameProximityService.GrantInput("ep-1", game.ben().id(), game.anna().id(), expires)),
                List.of(new GameProximityService.CircleInput("circle-1", 47.0, 8.0, 200, expires,
                        List.of(game.anna().id(), game.carl().id())))));
        assertEquals(1, published.get("revision").asLong());
        assertEquals(1, proximityApi.get(session).get("circles").size());
        assertCode(409, () -> proximityApi.replace(session, new GameProximityResource.ReplaceRequest(0L,
                List.of(), List.of())));

        as(game.anna());
        assertEquals(List.of(new GameProximityResource.Access(game.ben().id(), game.ben().trackerId(), expires)),
                proximityApi.access());
        assertCode(403, proximityApi::circles);
        assertCode(403, () -> proximityApi.get(session));
        as(game.carl());
        assertEquals(List.of(), proximityApi.access());
        as(game.hunter());
        JsonNode circles = proximityApi.circles();
        assertEquals(1, circles.size());
        assertEquals(1, circles.get(0).get("playerCount").asInt());
        assertEquals(1, circles.get(0).get("supporterCount").asInt());
        assertFalse(fieldNames(circles).contains("memberIds"));
        assertEquals(expires, circles.get(0).get("expiresAt").asText());
        assertCode(403, proximityApi::access);
        as(game.spectator());
        assertEquals(1, proximityApi.circles().size());

        asOperator();
        assertEquals(2, proximityApi.reset(session).get("revision").asLong());
        as(game.anna());
        assertEquals(List.of(), proximityApi.access());
        assertTrue(Instant.parse(expires).isAfter(T0));
    }

}
