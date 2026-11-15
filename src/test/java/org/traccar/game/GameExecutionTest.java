package org.traccar.game;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 of game execution (plan B0, B1, B5): the game state of a session, independent of the controller lease;
 * stopping the game; the unfenced administrator stop; execution events and their catch-up; the delivery lookup.
 * Runs on H2 ({@link GameExecutionH2Test}) and MySQL ({@link GameExecutionMySqlTest}).
 */
public abstract class GameExecutionTest extends GameTestBase {

    private static final String A = "instance-a-0001";
    private static final String B = "instance-b-0002";

    private GameRequestService requests;
    private GameControllerService controller;
    private GameExecutionService execution;
    private GameStateService state;
    private GameJokerService jokers;
    private GameLogService logs;
    private GameEffectsService effects;
    private int alice;
    private int bob;

    @Override
    protected void createServices() throws Exception {
        requests = new GameRequestService(transactions, mapper, publisher);
        controller = new GameControllerService(source, clock, 30, 5, requests, publisher);
        execution = new GameExecutionService(transactions, controller, publisher);
        state = new GameStateService(transactions, mapper, publisher);
        jokers = new GameJokerService(transactions, publisher);
        logs = new GameLogService(transactions);
        effects = effectsService();
        alice = user();
        bob = user();
    }

    /** Every test starts with a free lease and no running game. */
    @BeforeEach
    void reset() throws Exception {
        clock.set(T0);
        execute("UPDATE tc_game_controller SET state = 'free', userid = NULL, instanceid = NULL WHERE id = 1");
        execute("UPDATE tc_game_session_state SET state = 'stopped', runningkey = NULL WHERE runningkey = 1");
        publisher.clear();
    }

    private long acquire(int user, String instance) throws Exception {
        return controller.acquire(user, instance, "Laptop").lease().epoch();
    }

    private GameExecutionService.State start(long session, long epoch) throws Exception {
        return execution.change(session, GameExecutionService.RUNNING, alice, epoch);
    }

    private GameExecutionService.State stop(long session, Long epoch, int user) throws Exception {
        return execution.change(session, GameExecutionService.STOPPED, user, epoch);
    }

    private List<String> history(long session) throws Exception {
        return strings("SELECT event FROM tc_game_controller_history WHERE gamesessionid = ? ORDER BY id", session);
    }

    private GameRequest request(long session, long id) throws Exception {
        return requests.operatorList(session, false, 0).stream().filter(item -> item.id() == id).findFirst()
                .orElseThrow();
    }

    // Game state --------------------------------------------------------------------------------------------

    @Test
    void startStopAndOneRunningGamePerServer() throws Exception {
        long first = session();
        long second = session();
        assertEquals(GameExecutionService.STOPPED, execution.get(first).state());
        assertNull(execution.get(first).changedAt());
        long epoch = acquire(alice, A);
        publisher.clear();

        GameExecutionService.State running = start(first, epoch);
        assertTrue(running.running());
        assertEquals(T0.toString(), running.changedAt());
        assertTrue(running.changedBy().endsWith(" on Laptop"));
        assertEquals(first, execution.runningSessionId());
        assertEquals(List.of(new GamePublisher.Invalidation(first, GamePublisher.STATUS, null)),
                publisher.topic(GamePublisher.STATUS));
        assertEquals(1, publisher.events.size());
        GameEvent event = publisher.events.getFirst();
        assertEquals(GameEvent.GAME, event.category());
        assertEquals("gameStarted", event.data().path("type").asText());
        assertEquals(List.of("gameStarted"), history(first));

        // Starting again changes nothing; another session cannot start while this one runs.
        publisher.clear();
        assertEquals(running, start(first, epoch));
        assertTrue(publisher.events.isEmpty());
        assertStatus(409, () -> start(second, epoch));
        assertEquals(GameExecutionService.STOPPED, execution.get(second).state());

        // The state is fenced: a wrong epoch, or a start without one, is refused.
        assertStatus(423, () -> stop(first, epoch + 5, alice));
        assertStatus(423, () -> execution.change(second, GameExecutionService.RUNNING, alice, null));
        assertStatus(400, () -> execution.change(first, "paused", alice, epoch));
        assertStatus(404, () -> execution.get(-1));

        // A stopped game can be started again; then the other session may run.
        assertFalse(stop(first, epoch, alice).running());
        assertTrue(start(second, epoch).running());
        assertEquals(second, execution.runningSessionId());
        stop(second, epoch, alice);
        assertTrue(start(first, epoch).running());
        assertEquals(List.of("gameStarted", "gameStopped", "gameStarted"), history(first));

        // A running session is not deleted.
        assertStatus(409, () -> sessions.delete(first));
    }

    @Test
    void theGameStateSurvivesALeaseLossATakeoverAndARestart() throws Exception {
        long session = session();
        long epoch = acquire(alice, A);
        start(session, epoch);

        clock.advance(Duration.ofSeconds(31));
        assertTrue(controller.recordExpiry());
        assertTrue(execution.get(session).running());

        long other = controller.takeover(bob, B, "Desk").lease().epoch();
        assertTrue(execution.get(session).running());

        controller = new GameControllerService(source, clock, 30, 5, requests, publisher);
        execution = new GameExecutionService(transactions, controller, publisher);
        assertTrue(controller.endAfterRestart());
        assertTrue(execution.get(session).running());
        assertEquals(session, execution.runningSessionId());
        assertEquals(other + 1, controller.acquire(bob, B, "Desk", other).lease().epoch());
        assertTrue(execution.get(session).running());
    }

    @Test
    void stoppingTheGameEndsOpenRequestsAndLiveUploadsInOneTransaction() throws Exception {
        long session = session("{\"request_approvals\": {\"speedhunt_static_ping\": false}}");
        GameMember anna = bound(session, "player");
        GameMember ben = bound(session, "supporter");
        GameMember hunter = bound(session, "hunter");
        jokers.change(session, new GameJokerService.Change(anna.id(), GameJokerRepository.HUNTER_POSITIONS, 1,
                null), "Operator");
        jokers.change(session, new GameJokerService.Change(null, GameJokerRepository.SPEEDHUNT, 2, null),
                "Operator");
        long epoch = acquire(alice, A);
        start(session, epoch);

        // Members submit while the game runs, whether or not a Tool controls it.
        clock.advance(Duration.ofSeconds(40));
        assertTrue(controller.recordExpiry());
        GameRequest joker = requests.submit(anna, new GameRequestService.Submission(GameRequest.HUNTER_POSITIONS,
                null, null, null));
        GameRequest ping = requests.submit(hunter, new GameRequestService.Submission(GameRequest.STATIC_PING,
                ben.id(), memberRevision(ben.id()), null));
        epoch = acquire(alice, A);
        requests.claim(session, ping.id(), epoch);
        long upload = state.apply(session, epoch, List.of(new GameOperation.LiveUploadStarted(GameState.MANUAL,
                anna.id(), null, true, true, true, 30, null, "Operator", null, null, null, null, null))).results()
                .getFirst().id();
        long annaRevision = memberRevision(anna.id());
        publisher.clear();

        assertFalse(stop(session, epoch, alice).running());
        assertEquals(GameRequest.CANCELLED, request(session, joker.id()).status());
        assertEquals(GameExecutionService.STOPPED_MESSAGE, request(session, joker.id()).message());
        assertEquals(GameRequest.FAILED, request(session, ping.id()).status());
        assertTrue(requests.operatorList(session, true, 0).isEmpty());
        assertEquals(0, number("SELECT COUNT(*) FROM tc_game_joker_reservations WHERE gamesessionid = ?"
                + " AND status = 'reserved'", session));
        GameState.LiveUpload ended = state.liveUploads(session).stream().filter(item -> item.id() == upload)
                .findFirst().orElseThrow();
        assertEquals(GameState.ENDED, ended.state());
        assertEquals(GameExecutionService.GAME_STOPPED, ended.endReason());
        assertEquals(annaRevision + 1, memberRevision(anna.id()));
        // The upload's ended event comes first, then the game's.
        assertEquals(List.of("live:ended", "game:gameStopped"), publisher.events.stream()
                .map(item -> item.category() + ":" + item.data().path("type").asText()).toList());
        assertEquals(GameExecutionService.GAME_STOPPED, publisher.events.getFirst().data().path("endReason")
                .asText());
        GameEvent event = publisher.events.getLast();
        assertEquals(2, event.data().path("endedRequests").asInt());
        assertEquals(1, event.data().path("endedLiveUploads").asInt());
        assertFalse(publisher.topic(GamePublisher.REQUESTS).isEmpty());
        assertFalse(publisher.topic(GamePublisher.JOKERS).isEmpty());

        // While stopped, new requests are refused.
        assertStatus(409, () -> requests.submit(anna, new GameRequestService.Submission(
                GameRequest.HUNTER_POSITIONS, null, null, null)));
    }

    @Test
    void anAdministratorStopsAGameWithoutControlOnlyWhileNoLeaseIsActive() throws Exception {
        long session = session();
        long epoch = acquire(alice, A);
        start(session, epoch);

        assertStatus(423, () -> stop(session, null, bob));
        assertTrue(execution.get(session).running());

        clock.advance(Duration.ofSeconds(31));
        GameExecutionService.State stopped = stop(session, null, bob);
        assertFalse(stopped.running());
        assertFalse(stopped.changedBy().contains(" on "));
        assertEquals(List.of("gameStarted", "gameStopped"), history(session));
        assertEquals("Stopped without control of the game", text("SELECT detail FROM tc_game_controller_history"
                + " WHERE gamesessionid = ? AND event = 'gameStopped'", session));
        assertEquals(bob, number("SELECT userid FROM tc_game_controller_history WHERE gamesessionid = ?"
                + " AND event = 'gameStopped'", session));

        // The next acquisition reports it as a change made without control.
        var acquisition = controller.acquire(alice, A, "Laptop", epoch);
        assertTrue(acquisition.drift().stream().anyMatch(entry -> "gameStopped".equals(entry.event())));
    }

    // Execution events --------------------------------------------------------------------------------------

    @Test
    void executionEventsAreLoggedWithDataAndCaughtUpByIdentifier() throws Exception {
        long session = session();
        logs.append(session, List.of(new GameLogService.LogInput("upload", T0.toString(), "A Tool line")));
        GameEvent first = execution.testEvent(session, "Drill", alice);
        GameEvent second = execution.testEvent(session, null, alice);
        assertEquals(List.of(first, second), publisher.events);
        assertEquals("test", first.data().path("type").asText());
        assertEquals("Drill", first.data().path("message").asText());
        assertEquals("Test event", second.data().path("message").asText());

        var all = logs.list(session, null, 0, null, false).items();
        assertEquals(3, all.size());
        assertNull(all.getFirst().data());
        var events = logs.list(session, null, 0, null, true).items();
        assertEquals(List.of(first.id(), second.id()), events.stream().map(GameLogService.LogEntry::id).toList());
        assertEquals("Drill", events.getFirst().data().path("message").asText());
        assertEquals(List.of(second.id()), logs.list(session, Long.toString(first.id()), 0, null, true).items()
                .stream().map(GameLogService.LogEntry::id).toList());
        assertTrue(logs.list(session, Long.toString(second.id()), 0, null, true).items().isEmpty());

        // Data is a JSON object of at most 4 KB, in a known category.
        var large = JsonNodeFactory.instance.objectNode().put("text", "x".repeat(GameEvent.MAX_DATA));
        assertThrows(IllegalArgumentException.class, () -> transactions.write(transaction -> {
            transaction.lockSession(session);
            return GameLogService.event(transaction, publisher, session, GameEvent.GAME, "Too large", large);
        }));
        assertThrows(IllegalArgumentException.class, () -> transactions.write(transaction -> {
            transaction.lockSession(session);
            return GameLogService.event(transaction, publisher, session, "speedhunt", "Unknown",
                    JsonNodeFactory.instance.objectNode());
        }));
        assertStatus(400, () -> execution.testEvent(session, "y".repeat(513), alice));
        assertStatus(404, () -> execution.testEvent(-1, null, alice));
    }

    // Delivery lookup ---------------------------------------------------------------------------------------

    @Test
    void aRecordedDeliveryIsLookedUpUnderAnyEpoch() throws Exception {
        long session = session();
        GameMember anna = bound(session, "player");
        long epoch = acquire(alice, A);
        var delivery = new GameEffectsService.Delivery("delivery-lookup-1", null,
                List.of(new GameOperation.Catch(anna.id(), "Operator")), null, null, null, null, null, null);
        var outcome = effects.apply(session, epoch, delivery);
        assertFalse(outcome.replayed());

        controller.takeover(bob, B, "Desk");
        var recorded = effects.lookup(session, "delivery-lookup-1");
        assertTrue(recorded.replayed());
        assertEquals(epoch, recorded.epoch());
        assertEquals(outcome.operations(), recorded.operations());
        assertStatus(404, () -> effects.lookup(session, "delivery-never-sent"));
        assertStatus(404, () -> effects.lookup(session(), "delivery-lookup-1"));
        assertStatus(400, () -> effects.lookup(session, "bad id"));
    }

}
