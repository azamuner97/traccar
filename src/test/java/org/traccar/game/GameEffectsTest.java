package org.traccar.game;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 3d of game data model 2: idempotent effects deliveries with typed operations, request outcomes, joker
 * accounting, notifications, the push outbox and mirror positions, published only after commit. Runs on H2
 * ({@link GameEffectsH2Test}) and MySQL ({@link GameEffectsMySqlTest}).
 */
public abstract class GameEffectsTest extends GameTestBase {

    private static final long EPOCH = 21;

    private GameEffectsService effects;
    private GameRequestService requests;
    private GameJokerService jokers;
    private GameStateService state;
    private GameTimetableService timetables;
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
        effects = new GameEffectsService(transactions, mapper, publisher);
        requests = new GameRequestService(transactions, mapper, publisher);
        jokers = new GameJokerService(transactions, publisher);
        state = new GameStateService(transactions, mapper, publisher);
        timetables = new GameTimetableService(transactions, mapper, publisher);
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

    private static GameEffectsService.PositionInput position(long device, double latitude) {
        return new GameEffectsService.PositionInput(device, null, latitude, 8.5, null, null, null, null, null);
    }

    private static GameEffectsService.Delivery delivery(String id, List<GameEffectsService.PositionInput> positions,
                                                        GameOperation... operations) {
        return new GameEffectsService.Delivery(id, positions, List.of(operations), null, null, null, null, null);
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
        String id = id();
        var delivery = new GameEffectsService.Delivery(id, List.of(position(game.annaToHunters(), 47.1),
                position(game.annaToPlayers(), 47.1)), List.of(ping(game.anna(), 1, GameState.STATIC, null)), null,
                null, List.of(new GameLogService.LogInput(GameLogService.SPEEDHUNT, T0.toString(),
                        "Speedhunt started")), null, null);
        long before = revision(game.session());
        var outcome = deliver(game.session(), delivery);
        assertFalse(outcome.replayed());
        assertEquals(EPOCH, outcome.epoch());
        assertEquals(before + 1, outcome.sessionRevision());
        assertEquals(2, outcome.positions().size());
        assertTrue(outcome.positions().stream().allMatch(GameEffectsService.PositionOutcome::latest));
        assertEquals(outcome.positions().getFirst().positionId(), latestPosition(game.annaToHunters()));
        assertEquals(1, outcome.operations().getFirst().number());
        assertEquals(Set.of(game.annaToHunters(), game.annaToPlayers()), Set.copyOf(publisher.devices));
        assertEquals(2, publisher.positions.size());
        assertEquals(1, publisher.topic(GamePublisher.STATUS).size());
        assertNull(publisher.topic(GamePublisher.STATUS).getFirst().members());
        assertEquals(1, publisher.topic(GamePublisher.SPEEDHUNT_HISTORY).size());

        publisher.clear();
        var replayed = deliver(game.session(), delivery);
        assertTrue(replayed.replayed());
        assertEquals(outcome.positions(), replayed.positions());
        assertEquals(outcome.operations(), replayed.operations());
        assertTrue(publisher.invalidations.isEmpty() && publisher.positions.isEmpty());
        assertEquals(1, state.runs(game.session()).getFirst().pings().size());
        assertEquals(1, jokers.pool(game.session()).inventories().getFirst().used());
        assertEquals(1, number("SELECT COUNT(*) FROM tc_game_session_logs WHERE gamesessionid = ?",
                game.session()));
        assertEquals(before + 1, revision(game.session()));

        assertStatus(409, () -> deliver(game.session(), delivery(id, List.of(position(game.annaToHunters(), 47.2)),
                ping(game.anna(), 1, GameState.STATIC, null))));
        long other = session();
        assertStatus(409, () -> deliver(other, delivery));
        assertStatus(400, () -> deliver(game.session(), delivery("short", List.of())));
        assertStatus(404, () -> deliver(-1, delivery(id(), List.of())));
    }

    @Test
    void aFailedDeliveryAppliesNothingAndItsRetryUsesNoJokerTwice() throws Exception {
        Game game = game();
        jokers.change(game.session(), new GameJokerService.Change(game.anna().id(),
                GameJokerRepository.HUNTER_POSITIONS, 1, null), "Operator");
        publisher.clear();
        String id = id();
        var use = List.of(new GameJokerService.Use(game.anna().id(), GameJokerRepository.HUNTER_POSITIONS, false,
                "Manual relay"));
        var broken = new GameEffectsService.Delivery(id, List.of(position(game.annaToPlayers(), 47.1),
                position(game.annaToPlayers(), 95)), List.of(), null, null, null, null, use);
        assertStatus(400, () -> deliver(game.session(), broken));
        assertEquals(0, number("SELECT COUNT(*) FROM tc_positions WHERE deviceid = ?", game.annaToPlayers()));
        assertEquals(0, number("SELECT COUNT(*) FROM tc_game_deliveries WHERE deliveryid = ?", id));
        assertTrue(publisher.invalidations.isEmpty() && publisher.positions.isEmpty());

        var fixed = new GameEffectsService.Delivery(id, List.of(position(game.annaToPlayers(), 47.1)), List.of(),
                null, null, null, null, use);
        var outcome = deliver(game.session(), fixed);
        assertFalse(outcome.jokers().getFirst().overAllocated());
        deliver(game.session(), fixed);
        var inventory = jokers.own(game.anna()).inventories().stream()
                .filter(item -> item.type().equals(GameJokerRepository.HUNTER_POSITIONS)).findFirst().orElseThrow();
        assertEquals(1, inventory.used());
        assertEquals(Set.of(game.anna().id()), publisher.topic(GamePublisher.JOKERS).getFirst().members());
        // Without an available joker, a relay needs the operator's confirmation and counts beyond the allocation.
        assertStatus(409, () -> deliver(game.session(), new GameEffectsService.Delivery(id(), List.of(), List.of(),
                null, null, null, null, use)));
        var over = deliver(game.session(), new GameEffectsService.Delivery(id(), List.of(), List.of(), null, null,
                null, null, List.of(new GameJokerService.Use(game.anna().id(), GameJokerRepository.HUNTER_POSITIONS,
                        true, null))));
        assertTrue(over.jokers().getFirst().overAllocated());
        assertStatus(400, () -> deliver(game.session(), new GameEffectsService.Delivery(id(), List.of(), List.of(),
                null, null, null, null, List.of(new GameJokerService.Use(game.anna().id(),
                        GameJokerRepository.BREAK, true, null)))));
        assertEquals(List.of(), jokers.reconcile(game.session()));
    }

    // Positions ---------------------------------------------------------------------------------------------

    @Test
    void olderFixTimesAreStoredButDoNotBecomeLatest() throws Exception {
        Game game = game();
        long device = game.annaToPlayers();
        var first = deliver(game.session(), delivery(id(), List.of(new GameEffectsService.PositionInput(device, null,
                47.1, 8.5, T0.minusSeconds(10).toString(), Map.of("source", "relay"), null, null, null))));
        var older = deliver(game.session(), delivery(id(), List.of(new GameEffectsService.PositionInput(device, null,
                47.2, 8.5, T0.minusSeconds(20).toString(), null, null, null, null))));
        assertFalse(older.positions().getFirst().latest());
        assertEquals(first.positions().getFirst().positionId(), latestPosition(device));
        assertTrue(attributes(first.positions().getFirst().positionId()).contains("\"source\":\"relay\""));
        // Deliveries within one second all become latest in order.
        for (int i = 0; i < 3; i++) {
            clock.set(T0.plusMillis(100 + i * 300));
            var outcome = deliver(game.session(), delivery(id(), List.of(position(device, 47.3 + i / 10.0))));
            assertTrue(outcome.positions().getFirst().latest());
            assertEquals(outcome.positions().getFirst().positionId(), latestPosition(device));
        }
        assertStatus(400, () -> deliver(game.session(), delivery(id(), List.of(new GameEffectsService.PositionInput(
                device, null, 47.1, 8.5, T0.plusSeconds(120).toString(), null, null, null, null)))));
        assertStatus(400, () -> deliver(game.session(), delivery(id(), List.of(new GameEffectsService.PositionInput(
                device, null, 47.1, 8.5, null, Map.of("bad key!", 1), null, null, null)))));
    }

    @Test
    void onlyThisSessionsActiveMirrorsAreWritable() throws Exception {
        Game game = game();
        Game other = game();
        lease(game.session(), EPOCH);
        long tracker = members.get(game.session(), game.anna().id()).trackerId();
        for (long device : List.of(tracker, other.annaToHunters(), (long) device())) {
            assertStatus(400, () -> deliver(game.session(), delivery(id(), List.of(position(device, 47.1)))));
        }
        var mirror = resources.mirrors(game.session()).stream()
                .filter(item -> item.deviceId() == game.benToHunters()).findFirst().orElseThrow();
        resources.retire(GameResource.Kind.MIRROR, game.session(), mirror.id());
        assertStatus(400, () -> deliver(game.session(), delivery(id(), List.of(position(game.benToHunters(), 47.1)))));
        String uniqueId = text("SELECT uniqueid FROM tc_devices WHERE id = ?", game.annaToPlayers());
        assertEquals(game.annaToPlayers(), deliver(game.session(), delivery(id(), List.of(
                new GameEffectsService.PositionInput(null, uniqueId, 47.1, 8.5, null, null, null, null, null))))
                .positions().getFirst().deviceId());
        assertStatus(400, () -> deliver(game.session(), delivery(id(), List.of(new GameEffectsService.PositionInput(
                null, "unknown-" + UUID.randomUUID(), 47.1, 8.5, null, null, null, null, null)))));
    }

    // Fakes -------------------------------------------------------------------------------------------------

    @Test
    void guardedPositionsUseTheFakeOnlyWhenTheDeliveryUsesIt() throws Exception {
        Game game = game();
        Instant now = clock.instant();
        timetables.publish(game.session(), 0, json("[\"Time\",\"" + game.anna().displayName() + "\"]"), null,
                List.of(new GameTimetableService.EntryInput(now.plusSeconds(600).toString(),
                                game.anna().displayName(), ""),
                        new GameTimetableService.EntryInput(now.plusSeconds(1200).toString(),
                                game.anna().displayName(), "")));
        var entries = timetables.get(game.session()).entries();
        jokers.change(game.session(), new GameJokerService.Change(game.anna().id(), GameJokerRepository.FAKE_PING,
                2, null), "Operator");
        long[] reservations = new long[2];
        for (int i = 0; i < 2; i++) {
            var request = requests.submit(game.anna(), new GameRequestService.Submission(GameRequest.FAKE_PING,
                    null, null, json("{\"entryId\": " + entries.get(i).id() + ", \"latitude\": 46.9,"
                            + " \"longitude\": 7.4}")));
            requests.claim(game.session(), request.id(), EPOCH);
            deliver(game.session(), new GameEffectsService.Delivery(id(), List.of(), List.of(), null, null, null,
                    List.of(new GameRequestService.Completion(request.id(), GameRequest.SUCCEEDED, null, null)),
                    null));
            reservations[i] = jokers.own(game.anna()).reservations().getLast().id();
        }
        publisher.clear();

        var used = deliver(game.session(), delivery(id(), List.of(guarded(game.annaToHunters(), reservations[0]),
                position(game.annaToPlayers(), 47.1)), new GameOperation.FakeUsed(reservations[0])));
        assertEquals(46.9, latitude(used.positions().get(0).positionId()));
        assertEquals(47.1, latitude(used.positions().get(1).positionId()));
        assertEquals(List.of(new GameOperations.FakeOutcome(reservations[0], GameJokerRepository.CONSUMED)),
                used.fakes());
        // Fake and real positions carry the same payload.
        assertEquals(attributes(used.positions().get(1).positionId()),
                attributes(used.positions().get(0).positionId()));
        // Only the owner (and the operators) learn that the joker was used.
        assertEquals(Set.of(game.anna().id()), publisher.topic(GamePublisher.JOKERS).getFirst().members());
        assertTrue(publisher.topic(GamePublisher.STATUS).isEmpty());

        // The owner cancelled before the delivery: the hunters receive the real fallback.
        jokers.cancel(game.anna(), reservations[1]);
        var cancelled = deliver(game.session(), delivery(id(), List.of(guarded(game.annaToHunters(),
                reservations[1])), new GameOperation.FakeUsed(reservations[1])));
        assertEquals(47.0, latitude(cancelled.positions().getFirst().positionId()));
        assertEquals(GameOperations.SKIPPED, cancelled.fakes().getFirst().status());
        // A cancelled fake with an invalid coordinate still sends its fallback.
        var invalid = deliver(game.session(), delivery(id(), List.of(new GameEffectsService.PositionInput(
                game.annaToHunters(), null, 95.0, 8.5, null, null, reservations[1], 47.0, 8.5)),
                new GameOperation.FakeUsed(reservations[1])));
        assertEquals(47.0, latitude(invalid.positions().getFirst().positionId()));
        assertStatus(400, () -> deliver(game.session(), delivery(id(), List.of(guarded(game.annaToHunters(),
                reservations[1])))));
        assertStatus(400, () -> deliver(game.session(), delivery(id(), List.of(new GameEffectsService.PositionInput(
                game.annaToHunters(), null, 46.9, 7.4, null, null, reservations[1], null, null)),
                new GameOperation.FakeUsed(reservations[1]))));
        assertEquals(List.of(), jokers.reconcile(game.session()));
    }

    private static GameEffectsService.PositionInput guarded(long device, long reservation) {
        return new GameEffectsService.PositionInput(device, null, 46.9, 7.4, null, null, reservation, 47.0, 8.5);
    }

    @Test
    void theTargetsOwnSpeedhuntFakeReachesItsHunterMirrorsOnly() throws Exception {
        Game game = game();
        for (GameMember owner : List.of(game.anna(), game.ben())) {
            jokers.change(game.session(), new GameJokerService.Change(owner.id(), GameJokerRepository.FAKE_PING, 1,
                    null), "Operator");
            var request = requests.submit(owner, new GameRequestService.Submission(GameRequest.FAKE_PING, null,
                    null, json("{\"pingNumber\": 1, \"latitude\": 46.5, \"longitude\": 7.0}")));
            requests.claim(game.session(), request.id(), EPOCH);
            deliver(game.session(), new GameEffectsService.Delivery(id(), List.of(), List.of(), null, null, null,
                    List.of(new GameRequestService.Completion(request.id(), GameRequest.SUCCEEDED, null, null)),
                    null));
        }
        publisher.clear();

        // A live ping on Anna: its first sample binds both fakes; Anna's applies with a 5-20 m offset each time.
        long upload = deliver(game.session(), delivery(id(), List.of(), new GameOperation.LiveUploadStarted(
                GameState.SPEEDHUNT_PING, game.anna().id(), null, true, true, true, 10, null, null, null, null,
                null))).operations().getFirst().id();
        var first = deliver(game.session(), delivery(id(), List.of(position(game.annaToHunters(), 47.1),
                position(game.annaToPlayers(), 47.1), position(game.benToHunters(), 47.2)),
                ping(game.anna(), 1, GameState.LIVE, upload)));
        assertEquals(List.of(GameOperations.BOUND, GameOperations.BOUND), first.fakes().stream()
                .map(GameOperations.FakeOutcome::status).toList());
        assertEquals(GameState.JOKER_FAKE, first.operations().getFirst().source());
        assertOffset(latitude(first.positions().get(0).positionId()), 46.5);
        assertEquals(47.1, latitude(first.positions().get(1).positionId()));
        assertEquals(47.2, latitude(first.positions().get(2).positionId()));
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofSeconds(10));
            var sample = deliver(game.session(), delivery(id(), List.of(position(game.annaToHunters(), 47.1),
                    position(game.annaToPlayers(), 47.1)), new GameOperation.LiveUploadSample(upload, null, null)));
            assertOffset(latitude(sample.positions().get(0).positionId()), 46.5);
            assertEquals(47.1, latitude(sample.positions().get(1).positionId()));
        }
        // Both owners are told at the same moment, and nothing about the target reaches anyone else.
        var announced = publisher.topic(GamePublisher.JOKERS).getFirst().members();
        assertEquals(Set.of(game.anna().id(), game.ben().id(), game.hunter().id()), announced);
        assertTrue(publisher.topic(GamePublisher.STATUS).stream().allMatch(item -> item.members() == null));
        var annaView = jokers.own(game.anna()).reservations().getFirst();
        var benView = jokers.own(game.ben()).reservations().getFirst();
        assertEquals(annaView.fake().bound(), benView.fake().bound());
        assertNull(annaView.fake().boundRunId());
        assertNull(benView.fake().boundRunId());
        assertEquals(List.of(), jokers.reconcile(game.session()));
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
                double[] coordinate = GameEffectsService.fakeCoordinate(point[0], point[1], true);
                assertTrue(Math.abs(coordinate[0]) <= 90);
                assertTrue(Math.abs(coordinate[1]) <= 180);
            }
        }
        assertArrayEquals(new double[] {47, 8}, GameEffectsService.fakeCoordinate(47, 8, false));
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
        assertStatus(400, () -> deliver(game.session(), new GameEffectsService.Delivery(id(), List.of(), List.of(),
                null, null, null, completion, null)));
        publisher.clear();
        var alert = new GameNotificationService.Alert("break-" + request.id(), "geofenceExit", "user",
                T0.toString(), "UTC", "Your break started", game.ben().id(), game.ben().id());
        String id = id();
        var delivery = new GameEffectsService.Delivery(id, List.of(),
                List.of(new GameOperation.BreakStarted(null, request.id(), null)),
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
        var again = deliver(game.session(), new GameEffectsService.Delivery(id(), List.of(), List.of(),
                new GameEffectsService.NotificationInput(generation, List.of(alert)),
                List.of(new GameEffectsService.PushInput(game.ben().id(), null, "Again", null, alert.id())), null,
                null, null));
        assertEquals(List.of(new GameEffectsService.AlertOutcome(alert.id(), false)), again.alerts());
        assertEquals(GamePushOutbox.SKIPPED, again.pushes().getFirst().status());

        // An attempt interrupted by a restart is uncertain and never retried.
        var interrupted = deliver(game.session(), new GameEffectsService.Delivery(id(), List.of(), List.of(), null,
                List.of(new GameEffectsService.PushInput(game.ben().id(), null, "Lost", null, null)), null, null,
                null));
        long row = interrupted.pushes().getFirst().id();
        execute("UPDATE tc_game_push_outbox SET status = ? WHERE id = ?", GamePushOutbox.ATTEMPTING, row);
        assertEquals(1, outbox.markInterrupted());
        assertEquals(0, outbox.sendQueued());
        assertEquals(GamePushOutbox.UNCERTAIN, text("SELECT status FROM tc_game_push_outbox WHERE id = ?", row));
        assertStatus(400, () -> deliver(game.session(), new GameEffectsService.Delivery(id(), List.of(), List.of(),
                null, List.of(new GameEffectsService.PushInput(game.ben().id(), null, " ", null, null)), null, null,
                null)));
        assertStatus(409, () -> deliver(game.session(), new GameEffectsService.Delivery(id(), List.of(), List.of(),
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
        deliver(game.session(), delivery(id(), List.of(), new GameOperation.Catch(game.anna().id(), "Hunter")));
        assertEquals(1, publisher.topic(GamePublisher.STATUS).size());
        assertEquals(1, publisher.topic(GamePublisher.REQUESTS).size());
        assertEquals(Set.of(game.anna().id()), publisher.topic(GamePublisher.JOKERS).getFirst().members());
        assertEquals(1, jokers.own(game.anna()).inventories().stream()
                .filter(item -> item.type().equals(GameJokerRepository.HUNTER_POSITIONS)).findFirst().orElseThrow()
                .available());
        WebApplicationException error = assertThrows(WebApplicationException.class, () -> deliver(game.session(),
                delivery(id(), List.of(), new GameOperation.Catch(game.anna().id(), null))));
        assertEquals(409, error.getResponse().getStatus());
    }

}
