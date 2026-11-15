package org.traccar.game;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 3d of game data model 2: idempotent effects deliveries with typed operations, ping intents, request outcomes,
 * joker accounting, notifications and the push outbox, published only after commit. A delivery carries no positions:
 * the ping engine writes them from intents ({@link GameOneOffPingTest} covers each one-off action). Runs on H2
 * ({@link GameEffectsH2Test}) and MySQL ({@link GameEffectsMySqlTest}).
 */
public abstract class GameEffectsTest extends GameTestBase {

    private static final long EPOCH = 21;

    private GameEffectsService effects;
    private GameRequestService requests;
    private GameJokerService jokers;
    private GameStateService state;
    private GameNotificationService notifications;
    private final List<String> sent = new ArrayList<>();
    private final List<Long> failing = new ArrayList<>();
    private GamePushOutbox outbox;

    @Override
    protected Set<GameBindingHook> hooks() {
        return Set.of(new GamePlanInvalidation(publisher), new GameAccessRule(transactions));
    }

    @Override
    protected void createServices() {
        effects = effectsService();
        requests = new GameRequestService(transactions, mapper, publisher);
        jokers = new GameJokerService(transactions, publisher);
        state = new GameStateService(transactions, mapper, publisher);
        notifications = new GameNotificationService(transactions, publisher);
        outbox = new GamePushOutbox(transactions, (userId, notificator, subject, message) -> {
            if (failing.contains(userId)) {
                throw new IllegalStateException("Notificator unavailable");
            }
            sent.add(userId + ":" + notificator + ":" + message);
        });
    }

    @AfterEach
    void resetClock() {
        clock.set(T0);
    }

    // Helpers -----------------------------------------------------------------------------------------------

    /** A running session whose players and supporters have mirrors to hunters and players. */
    private record Game(long session, GameMember anna, GameMember ben, GameMember hunter, long annaToHunters,
                        long annaToPlayers, long benToHunters) {
    }

    private Game game() throws Exception {
        long session = session("{\"break_duration_minutes\": 10}");
        lease(session, EPOCH);
        GameMember anna = bound(session, "player");
        GameMember ben = bound(session, "supporter");
        GameMember hunter = bound(session, "hunter");
        long annaToHunters = resources.addMirror(session, device(), anna.id(), "hunter", null).deviceId();
        long annaToPlayers = resources.addMirror(session, device(), anna.id(), "player", null).deviceId();
        long benToHunters = resources.addMirror(session, device(), ben.id(), "hunter", null).deviceId();
        return new Game(session, anna, ben, hunter, annaToHunters, annaToPlayers, benToHunters);
    }

    private static String id() {
        return "delivery-" + UUID.randomUUID();
    }

    private static GameEffectsService.Delivery delivery(String id, GameOperation... operations) {
        return new GameEffectsService.Delivery(id, List.of(operations), null, null, null, null, null, null);
    }

    private static GameEffectsService.Delivery pings(String id, GamePingEngine.Intent... intents) {
        return new GameEffectsService.Delivery(id, List.of(), List.of(intents), null, null, null, null, null);
    }

    private static GamePingEngine.Intent intent(GameMember source, List<String> audiences,
                                                GamePingEngine.Effects delivered,
                                                GamePingEngine.Effects notDelivered) {
        return new GamePingEngine.Intent(GamePingEngine.MANUAL, source.id(), null, audiences, null, null, null,
                delivered, notDelivered);
    }

    /** A speedhunt static ping of the target: {@code pingDelivered} when it reaches someone. */
    private static GamePingEngine.Intent speedhunt(GameMember target, int number, List<String> audiences) {
        return new GamePingEngine.Intent(GamePingEngine.SPEEDHUNT, target.id(), null, audiences, null, null, null,
                new GamePingEngine.Effects(List.of(ping(target, number, GameState.STATIC, null)), null, null),
                new GamePingEngine.Effects(List.of(new GameOperation.PingFailed(target.id(), "nobody")), null,
                        null));
    }

    private long tracker(Game game, GameMember member) throws Exception {
        return members.get(game.session(), member.id()).trackerId();
    }

    private GameEffectsService.Outcome deliver(long session, GameEffectsService.Delivery delivery) throws Exception {
        return effects.apply(session, EPOCH, delivery);
    }

    private static GameOperation.PingDelivered ping(GameMember target, int number, String mode, Long upload) {
        return new GameOperation.PingDelivered(target.id(), number, mode, GameState.REAL, true, true, true, null,
                upload, "Operator", true);
    }

    private double latitude(long positionId) throws Exception {
        return Double.parseDouble(text("SELECT latitude FROM tc_positions WHERE id = ?", positionId));
    }

    private long latestPosition(long device) throws Exception {
        return number("SELECT positionid FROM tc_devices WHERE id = ?", device);
    }

    private String attributes(long positionId) throws Exception {
        return text("SELECT attributes FROM tc_positions WHERE id = ?", positionId);
    }

    // Idempotency -------------------------------------------------------------------------------------------

    @Test
    void aDeliveryCommitsOnceAndARepeatedIdReturnsItsOutcome() throws Exception {
        Game game = game();
        jokers.change(game.session(), new GameJokerService.Change(null, GameJokerRepository.SPEEDHUNT, 1, null),
                "Operator");
        publisher.clear();
        // A second player, so Anna's players audience has a recipient.
        bound(game.session(), "player");
        trackerPosition(tracker(game, game.anna()), 47.1, 8.5, T0.minusSeconds(3));
        publisher.clear();
        String id = id();
        var delivery = new GameEffectsService.Delivery(id, List.of(),
                List.of(speedhunt(game.anna(), 1, List.of("hunters", "players"))), null, null,
                List.of(new GameLogService.LogInput(GameLogService.SPEEDHUNT, T0.toString(),
                        "Speedhunt started")), null, null);
        long before = revision(game.session());
        var outcome = deliver(game.session(), delivery);
        assertFalse(outcome.replayed());
        assertEquals(EPOCH, outcome.epoch());
        assertEquals(before + 1, outcome.sessionRevision());
        var written = outcome.pings().getFirst().positions();
        assertEquals(2, written.size());
        assertTrue(written.stream().allMatch(GamePositions.Written::latest));
        assertEquals(written.getFirst().positionId(), latestPosition(game.annaToHunters()));
        assertEquals(47.1, latitude(latestPosition(game.annaToPlayers())));
        assertEquals(1, outcome.pings().getFirst().operations().getFirst().number());
        assertEquals(Set.of(game.annaToHunters(), game.annaToPlayers()), Set.copyOf(publisher.devices));
        assertEquals(2, publisher.positions.size());
        assertEquals(1, publisher.topic(GamePublisher.STATUS).size());
        assertNull(publisher.topic(GamePublisher.STATUS).getFirst().members());
        assertEquals(1, publisher.topic(GamePublisher.SPEEDHUNT_HISTORY).size());

        publisher.clear();
        var replayed = deliver(game.session(), delivery);
        assertTrue(replayed.replayed());
        assertEquals(outcome.pings(), replayed.pings());
        assertEquals(outcome.operations(), replayed.operations());
        assertTrue(publisher.invalidations.isEmpty() && publisher.positions.isEmpty());
        assertEquals(1, state.runs(game.session()).getFirst().pings().size());
        assertEquals(1, jokers.pool(game.session()).inventories().getFirst().used());
        assertEquals(1, number("SELECT COUNT(*) FROM tc_game_session_logs WHERE gamesessionid = ?",
                game.session()));
        assertEquals(before + 1, revision(game.session()));

        assertStatus(409, () -> deliver(game.session(), pings(id, speedhunt(game.anna(), 1, List.of("hunters")))));
        long other = session();
        assertStatus(409, () -> deliver(other, delivery));
        assertStatus(400, () -> deliver(game.session(), delivery("short")));
        assertStatus(404, () -> deliver(-1, delivery(id())));
    }

    @Test
    void aFailedDeliveryAppliesNothingAndItsRetryUsesNoJokerTwice() throws Exception {
        Game game = game();
        jokers.change(game.session(), new GameJokerService.Change(game.anna().id(),
                GameJokerRepository.HUNTER_POSITIONS, 1, null), "Operator");
        trackerPosition(tracker(game, game.anna()), 47.1, 8.5, T0.minusSeconds(3));
        publisher.clear();
        String id = id();
        var use = List.of(new GameJokerService.Use(game.anna().id(), GameJokerRepository.HUNTER_POSITIONS, false,
                "Manual relay"));
        // The second intent names a member of no session: the whole delivery is refused, also its first ping.
        var broken = new GameEffectsService.Delivery(id, List.of(), List.of(intent(game.anna(), List.of("hunters"),
                null, null), new GamePingEngine.Intent(GamePingEngine.MANUAL, -5L, null, List.of("hunters"), null,
                        null, null, null, null)), null, null, null, null, use);
        assertStatus(400, () -> deliver(game.session(), broken));
        assertEquals(0, number("SELECT COUNT(*) FROM tc_positions WHERE deviceid = ?", game.annaToHunters()));
        assertEquals(0, number("SELECT COUNT(*) FROM tc_game_deliveries WHERE deliveryid = ?", id));
        assertTrue(publisher.invalidations.isEmpty() && publisher.positions.isEmpty());

        var fixed = new GameEffectsService.Delivery(id, List.of(), List.of(intent(game.anna(), List.of("hunters"),
                null, null)), null, null, null, null, use);
        var outcome = deliver(game.session(), fixed);
        assertFalse(outcome.jokers().getFirst().overAllocated());
        deliver(game.session(), fixed);
        var inventory = jokers.own(game.anna()).inventories().stream()
                .filter(item -> item.type().equals(GameJokerRepository.HUNTER_POSITIONS)).findFirst().orElseThrow();
        assertEquals(1, inventory.used());
        assertEquals(1, number("SELECT COUNT(*) FROM tc_positions WHERE deviceid = ?", game.annaToHunters()));
        assertEquals(Set.of(game.anna().id()), publisher.topic(GamePublisher.JOKERS).getFirst().members());
        // Without an available joker, a use needs the operator's confirmation and counts beyond the allocation.
        assertStatus(409, () -> deliver(game.session(), new GameEffectsService.Delivery(id(), List.of(), null,
                null, null, null, null, use)));
        var over = deliver(game.session(), new GameEffectsService.Delivery(id(), List.of(), null, null, null,
                null, null, List.of(new GameJokerService.Use(game.anna().id(), GameJokerRepository.HUNTER_POSITIONS,
                        true, null))));
        assertTrue(over.jokers().getFirst().overAllocated());
        assertStatus(400, () -> deliver(game.session(), new GameEffectsService.Delivery(id(), List.of(), null,
                null, null, null, null, List.of(new GameJokerService.Use(game.anna().id(),
                        GameJokerRepository.BREAK, true, null)))));
        assertEquals(List.of(), jokers.reconcile(game.session()));
    }

    // No raw positions --------------------------------------------------------------------------------------

    @Test
    void aDeliveryCarriesNoPositionsOnlyIntentsWriteMirrors() throws Exception {
        Game game = game();
        // The raw positions list left the API (phase 4): the JSON is refused, whatever it names.
        for (String body : List.of("{\"deliveryId\": \"" + id() + "\", \"positions\": []}",
                "{\"deliveryId\": \"" + id() + "\", \"positions\": [{\"deviceId\": " + game.annaToHunters()
                        + ", \"latitude\": 47.1, \"longitude\": 8.5}]}")) {
            assertThrows(Exception.class, () -> mapper.readValue(body, GameEffectsService.Delivery.class));
        }
        assertEquals(0, number("SELECT COUNT(*) FROM tc_positions WHERE deviceid = ?", game.annaToHunters()));

        // An intent resolves its mirrors on the server: only this session's active ones, by their route.
        trackerPosition(tracker(game, game.ben()), 47.3, 8.5, T0.minusSeconds(3));
        var sent = deliver(game.session(), pings(id(), intent(game.ben(), List.of("hunters"), null, null)));
        assertEquals(List.of(game.benToHunters()), sent.pings().getFirst().positions().stream()
                .map(GamePositions.Written::deviceId).toList());
        var mirror = resources.mirrors(game.session()).stream()
                .filter(item -> item.deviceId() == game.benToHunters()).findFirst().orElseThrow();
        resources.retire(GameResource.Kind.MIRROR, game.session(), mirror.id());
        var retired = deliver(game.session(), pings(id(), intent(game.ben(), List.of("hunters"), null, null)));
        assertEquals(GamePingEngine.FAILED, retired.pings().getFirst().status());
        assertEquals(GamePingEngine.NO_MIRROR, retired.pings().getFirst().reason());
        assertEquals(1, number("SELECT COUNT(*) FROM tc_positions WHERE deviceid = ?", game.benToHunters()));
        // A member of another session is no source of this one.
        Game other = game();
        assertStatus(400, () -> deliver(game.session(), pings(id(), intent(other.anna(), List.of("hunters"), null,
                null))));
        // Pings within one second all become latest in order.
        for (int i = 0; i < 3; i++) {
            clock.set(T0.plusMillis(100 + i * 300));
            trackerPosition(tracker(game, game.anna()), 47.3 + i / 10.0, 8.5, clock.instant());
            var outcome = deliver(game.session(), pings(id(), intent(game.anna(), List.of("hunters"), null, null)));
            var written = outcome.pings().getFirst().positions().getFirst();
            assertTrue(written.latest());
            assertEquals(written.positionId(), latestPosition(game.annaToHunters()));
            assertEquals(47.3 + i / 10.0, latitude(written.positionId()));
        }
        assertTrue(attributes(latestPosition(game.annaToHunters())).contains("\"activity\":\"still\""));
    }

    // Fakes -------------------------------------------------------------------------------------------------

    @Test
    void pingIntentsAreDecidedInTheDeliveryWithOneOutcomeEach() throws Exception {
        Game game = game();
        long tracker = members.get(game.session(), game.anna().id()).trackerId();
        trackerPosition(tracker, 46.95, 7.44, T0.minusSeconds(5));
        jokers.change(game.session(), new GameJokerService.Change(null, GameJokerRepository.SPEEDHUNT, 1, null),
                "Operator");
        publisher.clear();
        // A speedhunt ping whose operations depend on the outcome: delivered → pingDelivered, else pingFailed.
        var delivered = new GamePingEngine.Effects(List.of(ping(game.anna(), 1, GameState.STATIC, null)), null,
                null);
        var notDelivered = new GamePingEngine.Effects(List.of(new GameOperation.PingFailed(game.anna().id(),
                "no position")), null, null);
        String id = id();
        var delivery = pings(id, new GamePingEngine.Intent(GamePingEngine.SPEEDHUNT,
                game.anna().id(), null, List.of("hunters", "players"), null, null, null, delivered, notDelivered),
                intent(game.ben(), List.of("hunters"), null, null));
        var outcome = deliver(game.session(), delivery);
        assertEquals(2, outcome.pings().size());
        var anna = outcome.pings().getFirst();
        assertEquals(GamePingEngine.SENT, anna.status());
        // Anna is the only player: the players audience has no recipient and is dropped.
        assertEquals(List.of("hunters"), anna.delivered());
        assertEquals("pingDelivered", anna.operations().getFirst().operation());
        assertEquals(46.95, latitude(latestPosition(game.annaToHunters())));
        assertTrue(latestPosition(game.annaToPlayers()) <= 0);
        // Ben has no position yet: nothing is written for him.
        var ben = outcome.pings().get(1);
        assertEquals(GamePingEngine.FAILED, ben.status());
        assertEquals(GamePingEngine.NO_POSITION, ben.reason());
        assertTrue(latestPosition(game.benToHunters()) <= 0);
        assertEquals(List.of(game.annaToHunters()), publisher.devices);
        assertFalse(publisher.devices.contains(game.benToHunters()));
        // A replay answers the recorded outcomes and writes nothing.
        long positions = number("SELECT COUNT(*) FROM tc_positions");
        var replay = deliver(game.session(), delivery);
        assertTrue(replay.replayed());
        assertEquals(outcome.pings(), replay.pings());
        assertEquals(positions, number("SELECT COUNT(*) FROM tc_positions"));
        assertEquals(outcome.pings(), effects.lookup(game.session(), id).pings());

        // A caught source sends nothing; its onNotDelivered applies in the same transaction.
        deliver(game.session(), delivery(id(), new GameOperation.RunEnded(game.anna().id(),
                GameState.TERMINATED, null)));
        deliver(game.session(), delivery(id(), new GameOperation.Catch(game.anna().id(), "Hunter")));
        var caught = deliver(game.session(), pings(id(), intent(game.anna(), List.of("hunters"),
                null, new GamePingEngine.Effects(List.of(new GameOperation.PingFailed(game.anna().id(), "caught")),
                        null, null))));
        assertEquals(GamePingEngine.SKIPPED, caught.pings().getFirst().status());
        assertEquals(GamePingEngine.CAUGHT, caught.pings().getFirst().reason());
        assertEquals("pingFailed", caught.pings().getFirst().operations().getFirst().operation());

        // Intents of a delivery never wait, and are checked before any transaction.
        assertStatus(400, () -> deliver(game.session(), pings(id(), new GamePingEngine.Intent(
                GamePingEngine.MANUAL, game.ben().id(), null, List.of("hunters"), null,
                new GamePingEngine.Freshness(T0.toString(), 10), null, null, null))));
        assertStatus(400, () -> deliver(game.session(), pings(id(), new GamePingEngine.Intent(
                GamePingEngine.MANUAL, game.ben().id(), null, List.of("hunters"),
                new GamePingEngine.Fake(46.0, 7.0, List.of("hunters")), null, null, null, null))));
        assertStatus(400, () -> deliver(game.session(), pings(id(), new GamePingEngine.Intent(
                "teleport", game.ben().id(), null, List.of("hunters"), null, null, null, null, null))));
        assertStatus(400, () -> deliver(game.session(), pings(id(), intent(game.ben(), List.of(), null,
                null))));
        assertEquals(List.of(), jokers.reconcile(game.session()));
    }

    @Test
    void theRemovedFakeOperationsAreRejected() {
        for (String body : List.of("{\"deliveryId\": \"" + id() + "\", \"operations\": [{\"type\": \"fakeUsed\","
                + " \"reservationId\": 1}]}", "{\"deliveryId\": \"" + id() + "\", \"operations\": [{\"type\":"
                + " \"fakeReleased\", \"reservationId\": 1}]}")) {
            assertThrows(Exception.class, () -> mapper.readValue(body, GameEffectsService.Delivery.class));
        }
    }

    @Test
    void liveSpeedhuntPingsAndSamplesAreTheServersOwn() throws Exception {
        Game game = game();
        jokers.change(game.session(), new GameJokerService.Change(null, GameJokerRepository.SPEEDHUNT, 1, null),
                "Operator");
        long upload = deliver(game.session(), delivery(id(), new GameOperation.LiveUploadStarted(
                GameState.SPEEDHUNT_PING, game.anna().id(), null, true, true, true, 10, null, null, null, null,
                null, null, null))).operations().getFirst().id();
        // The live ping's first sample, and every later one, is the server's tick (GameLiveUploadRunnerTest).
        assertStatus(400, () -> deliver(game.session(), delivery(id(),
                ping(game.anna(), 1, GameState.LIVE, upload))));
        assertStatus(400, () -> deliver(game.session(), delivery(id(),
                new GameOperation.LiveUploadSample(upload, null, null))));
        assertStatus(400, () -> deliver(game.session(), pings(id(), intent(game.anna(), List.of("hunters"),
                new GamePingEngine.Effects(List.of(new GameOperation.LiveUploadSample(upload, null, null)), null,
                        null), null))));
        assertStatus(400, () -> deliver(game.session(), pings(id(), intent(game.anna(), List.of("hunters"),
                new GamePingEngine.Effects(List.of(ping(game.anna(), 1, GameState.LIVE, upload)), null, null),
                null))));
        // The JSON API does not know the operation at all.
        assertThrows(Exception.class, () -> mapper.readValue("{\"type\": \"liveUploadSample\", \"liveUploadId\": "
                + upload + "}", GameOperation.class));
    }

    private static void assertOffset(double latitude, double planned) {
        double meters = Math.abs(latitude - planned) * 111_320;
        assertTrue(meters <= 20.5, "offset " + meters);
    }

    @Test
    void liveFakeOffsetsStayInsideTheMapAtThePolesAndTheDateLine() {
        for (var point : List.of(new double[] {90, 180}, new double[] {-90, -180}, new double[] {0, 180},
                new double[] {47, 8})) {
            for (int i = 0; i < 100; i++) {
                double[] coordinate = GamePositions.fakeCoordinate(point[0], point[1], true);
                assertTrue(Math.abs(coordinate[0]) <= 90);
                assertTrue(Math.abs(coordinate[1]) <= 180);
            }
        }
        assertArrayEquals(new double[] {47, 8}, GamePositions.fakeCoordinate(47, 8, false));
    }

    // Requests, notifications and pushes --------------------------------------------------------------------

    @Test
    void requestOutcomesNotificationsAndPushesCommitWithTheDelivery() throws Exception {
        Game game = game();
        GameMember unbound = plan(game.session(), "player");
        jokers.change(game.session(), new GameJokerService.Change(game.ben().id(), GameJokerRepository.BREAK, 1,
                null), "Operator");
        String generation = notifications.initialize(game.session()).generation();
        var request = requests.submit(game.ben(), new GameRequestService.Submission(GameRequest.BREAK, null, null,
                null));
        requests.claim(game.session(), request.id(), EPOCH);
        var completion = List.of(new GameRequestService.Completion(request.id(), GameRequest.SUCCEEDED, "Started",
                null));
        // A break that starts now starts with its request's outcome.
        assertStatus(400, () -> deliver(game.session(), new GameEffectsService.Delivery(id(), List.of(), null,
                null, null, null, completion, null)));
        publisher.clear();
        var alert = new GameNotificationService.Alert("break-" + request.id(), "geofenceExit", "user",
                T0.toString(), "UTC", "Your break started", game.ben().id(), game.ben().id());
        String id = id();
        var delivery = new GameEffectsService.Delivery(id,
                List.of(new GameOperation.BreakStarted(request.id(), null)), null,
                new GameEffectsService.NotificationInput(generation, List.of(alert)),
                List.of(new GameEffectsService.PushInput(game.ben().id(), "Break", "Your break started", null,
                                alert.id()),
                        new GameEffectsService.PushInput(game.anna().id(), null, "Hello", "web", null),
                        new GameEffectsService.PushInput(unbound.id(), null, "Hello", null, null)),
                null, completion, null);
        var outcome = deliver(game.session(), delivery);
        assertEquals(List.of(new GameEffectsService.RequestOutcome(request.id(), GameRequest.SUCCEEDED)),
                outcome.requests());
        assertEquals(List.of(new GameEffectsService.AlertOutcome(alert.id(), true)), outcome.alerts());
        assertEquals(List.of(GamePushOutbox.QUEUED, GamePushOutbox.QUEUED, GamePushOutbox.REJECTED),
                outcome.pushes().stream().map(GameEffectsService.PushOutcome::status).toList());
        assertEquals(1, state.snapshot(game.session()).breaks().size());
        assertEquals(Set.of(game.ben().id()), publisher.topic(GamePublisher.NOTIFICATIONS).getFirst().members());
        assertEquals(1, publisher.topic(GamePublisher.REQUESTS).size());
        assertEquals(2, publisher.pushes.size());

        // Pushes are sent after commit, once; a repeated delivery queues nothing again.
        failing.add(game.anna().userId());
        assertEquals(2, outbox.sendQueued());
        assertEquals(0, outbox.sendQueued());
        assertEquals(List.of(game.ben().userId() + ":traccar:Your break started"), sent);
        var replayed = deliver(game.session(), delivery);
        assertEquals(List.of(GamePushOutbox.SENT, GamePushOutbox.FAILED, GamePushOutbox.REJECTED),
                replayed.pushes().stream().map(GameEffectsService.PushOutcome::status).toList());
        assertEquals(2, number("SELECT COUNT(*) FROM tc_game_push_outbox WHERE gamesessionid = ?",
                game.session()));

        // A push whose alert was archived before is not queued again.
        var again = deliver(game.session(), new GameEffectsService.Delivery(id(), List.of(), null,
                new GameEffectsService.NotificationInput(generation, List.of(alert)),
                List.of(new GameEffectsService.PushInput(game.ben().id(), null, "Again", null, alert.id())), null,
                null, null));
        assertEquals(List.of(new GameEffectsService.AlertOutcome(alert.id(), false)), again.alerts());
        assertEquals(GamePushOutbox.SKIPPED, again.pushes().getFirst().status());

        // An attempt interrupted by a restart is uncertain and never retried.
        var interrupted = deliver(game.session(), new GameEffectsService.Delivery(id(), List.of(), null, null,
                List.of(new GameEffectsService.PushInput(game.ben().id(), null, "Lost", null, null)), null, null,
                null));
        long row = interrupted.pushes().getFirst().id();
        execute("UPDATE tc_game_push_outbox SET status = ? WHERE id = ?", GamePushOutbox.ATTEMPTING, row);
        assertEquals(1, outbox.markInterrupted());
        assertEquals(0, outbox.sendQueued());
        assertEquals(GamePushOutbox.UNCERTAIN, text("SELECT status FROM tc_game_push_outbox WHERE id = ?", row));
        assertStatus(400, () -> deliver(game.session(), new GameEffectsService.Delivery(id(), List.of(), null,
                null, List.of(new GameEffectsService.PushInput(game.ben().id(), null, " ", null, null)), null, null,
                null)));
        assertStatus(409, () -> deliver(game.session(), new GameEffectsService.Delivery(id(), List.of(), null,
                new GameEffectsService.NotificationInput(UUID.randomUUID().toString(), List.of(alert)), null, null,
                null, null)));
        assertEquals(List.of(), jokers.reconcile(game.session()));
    }

    @Test
    void catchesInDeliveriesEndRequestsAndAnnounceOnlyToOwners() throws Exception {
        Game game = game();
        jokers.change(game.session(), new GameJokerService.Change(game.anna().id(),
                GameJokerRepository.HUNTER_POSITIONS, 1, null), "Operator");
        requests.submit(game.anna(), new GameRequestService.Submission(GameRequest.HUNTER_POSITIONS, null, null,
                null));
        publisher.clear();
        deliver(game.session(), delivery(id(), new GameOperation.Catch(game.anna().id(), "Hunter")));
        assertEquals(1, publisher.topic(GamePublisher.STATUS).size());
        assertEquals(1, publisher.topic(GamePublisher.REQUESTS).size());
        assertEquals(Set.of(game.anna().id()), publisher.topic(GamePublisher.JOKERS).getFirst().members());
        assertEquals(1, jokers.own(game.anna()).inventories().stream()
                .filter(item -> item.type().equals(GameJokerRepository.HUNTER_POSITIONS)).findFirst().orElseThrow()
                .available());
        WebApplicationException error = assertThrows(WebApplicationException.class, () -> deliver(game.session(),
                delivery(id(), new GameOperation.Catch(game.anna().id(), null))));
        assertEquals(409, error.getResponse().getStatus());
    }

}
