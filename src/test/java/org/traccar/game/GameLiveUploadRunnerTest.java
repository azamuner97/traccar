package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.traccar.schedule.TaskGameExecution;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live uploads and relays run by the server (plan phase 2), driven by {@link TaskGameExecution} on virtual time:
 * ticks at their interval and never replayed, without a lease and across changes of control; every ending
 * (deadline, stop intent, catch, end of the speedhunt, game stop, retirement); the relay joker on the first
 * delivering tick; a live speedhunt ping's first-sample {@code pingDelivered}, its joker fake and the completion at
 * the ping limit; {@code waitingForLocation} once; and the cases of the Tool's live loops
 * ({@code test_manual_and_live.py}, {@code test_tracking.py}, {@code test_relay_actions.py},
 * {@code test_speedhunt_live_ping_operations.py}). Runs on H2 ({@link GameLiveUploadRunnerH2Test}) and MySQL
 * ({@link GameLiveUploadRunnerMySqlTest}).
 */
public abstract class GameLiveUploadRunnerTest extends GameTestBase {

    private static final long EPOCH = 31;

    private GameStateService state;
    private GameJokerService jokers;
    private GameRequestService requests;
    private GameEffectsService effects;
    private GameExecutionService execution;
    private GameLiveUploadRunner runner;
    private int operator;

    @Override
    protected Set<GameBindingHook> hooks() {
        return Set.of(new GamePlanInvalidation(publisher), new GameAccessRule(transactions));
    }

    @Override
    protected void createServices() throws Exception {
        state = new GameStateService(transactions, mapper, publisher);
        jokers = new GameJokerService(transactions, publisher);
        requests = new GameRequestService(transactions, mapper, publisher);
        effects = effectsService();
        execution = new GameExecutionService(transactions, new GameControllerService(source, clock, 30, 5, null,
                publisher), publisher);
        operator = user();
    }

    private void startRunner() {
        runner = new GameLiveUploadRunner(transactions, publisher, engine());
        new TaskGameExecution(transactions, scheduler, List.of(runner)).schedule(null);
    }

    @AfterEach
    void reset() throws Exception {
        scheduler.shutdown();
        execute("UPDATE tc_game_session_state SET state = 'stopped', runningkey = NULL WHERE runningkey = 1");
        clock.set(T0);
        publisher.clear();
    }

    // Fixture -----------------------------------------------------------------------------------------------

    /** Anna and Ben (players), Sue (supporter) and two hunters; mirrors to every role and per-hunter mirrors. */
    private record Game(long session, GameMember anna, GameMember ben, GameMember sue, GameMember hunter,
                        GameMember hunter2) {
    }

    private Game game(String settings) throws Exception {
        long session = session(settings);
        GameMember anna = bound(session, GameMember.PLAYER);
        GameMember ben = bound(session, GameMember.PLAYER);
        GameMember sue = bound(session, GameMember.SUPPORTER);
        GameMember hunter = bound(session, GameMember.HUNTER);
        GameMember hunter2 = bound(session, GameMember.HUNTER);
        for (GameMember member : List.of(anna, ben, sue)) {
            for (String role : List.of(GameMember.HUNTER, GameMember.PLAYER, GameMember.SUPPORTER)) {
                resources.addMirror(session, device(), member.id(), role, null);
            }
            for (GameMember source : List.of(hunter, hunter2)) {
                resources.addMirror(session, device(), source.id(), null, member.id());
            }
        }
        return new Game(session, anna, ben, sue, hunter, hunter2);
    }

    private Game game() throws Exception {
        return game("{\"pings_per_speedhunt\": 2, \"fixed_speedhunt_live_duration_minutes\": 2}");
    }

    /** The mirror device of a source for a destination role, or for a hunter relayed to {@code recipient}. */
    private long mirror(Game game, GameMember source, String role, GameMember recipient) throws Exception {
        return transactions.read(transaction -> GameResourceRepository.mirrorByRoute(transaction, game.session(),
                source.id(), recipient == null ? role : null, recipient == null ? null : recipient.id())
                .orElseThrow().deviceId());
    }

    private long mirror(Game game, GameMember source, String role) throws Exception {
        return mirror(game, source, role, null);
    }

    private long positions(long device) throws Exception {
        return number("SELECT COUNT(*) FROM tc_positions WHERE deviceid = ?", device);
    }

    private static GameOperation.LiveUploadStarted live(String purpose, GameMember member, boolean hunters,
                                                        boolean players, boolean supporters, int interval,
                                                        Instant deadline) {
        return new GameOperation.LiveUploadStarted(purpose, member.id(), null, hunters, players, supporters,
                interval, deadline == null ? null : deadline.toString(), null, null, null,
                GameState.SPEEDHUNT_PING.equals(purpose) ? Boolean.TRUE : null, null, null);
    }

    private static GameOperation.LiveUploadStarted relay(GameMember recipient, int interval, boolean useJoker,
                                                         boolean overAllocate) {
        return new GameOperation.LiveUploadStarted(GameState.HUNTER_RELAY, null, recipient.id(), false, false,
                false, interval, null, null, null, useJoker, overAllocate, null, null);
    }

    private static String id() {
        return "delivery-" + UUID.randomUUID();
    }

    /** Sends operations as the Tool does: an effects delivery. */
    private GameEffectsService.Outcome tool(Game game, GameOperation... operations) throws Exception {
        return effects.apply(game.session(), EPOCH, new GameEffectsService.Delivery(id(),
                List.of(operations), null, null, null, null, null, null));
    }

    private long start(Game game, GameOperation.LiveUploadStarted operation) throws Exception {
        return tool(game, operation).operations().getFirst().id();
    }

    private GameState.LiveUpload upload(Game game, long id) throws Exception {
        return state.liveUploads(game.session()).stream().filter(item -> item.id() == id).findFirst()
                .orElseThrow();
    }

    private List<JsonNode> events(String category, String type) {
        return publisher.events.stream().filter(event -> event.category().equals(category)
                && event.data().path("type").asText().equals(type)).map(GameEvent::data).toList();
    }

    private List<String> types(long uploadId) {
        return publisher.events.stream().filter(event -> event.data().path("liveUploadId").asLong() == uploadId)
                .map(event -> event.data().path("type").asText()).toList();
    }

    private void advance(long seconds) {
        scheduler.advance(Duration.ofSeconds(seconds));
    }

    private void advanceTo(Instant time) {
        scheduler.advance(Duration.between(clock.instant(), time));
    }

    /** Makes the controller lease inactive: no Tool controls the game. */
    private void noLease() throws Exception {
        execute("UPDATE tc_game_controller SET expiresat = ? WHERE id = 1", clock.instant().minusSeconds(1));
    }

    private static void assertOffset(double latitude, double planned) {
        double meters = Math.abs(latitude - planned) * 111_320;
        assertTrue(meters <= 20.5, "offset " + meters);
    }

    // Ticks -------------------------------------------------------------------------------------------------

    @Test
    void aLiveUploadTicksAtItsIntervalWhateverTheLeaseDoesAndNeverReplaysALateTick() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        lease(game.session(), EPOCH);
        trackerPosition(anna.trackerId(), 46.0, 7.0, T0.minusSeconds(10));
        long toHunters = mirror(game, anna, GameMember.HUNTER);
        long toPlayers = mirror(game, anna, GameMember.PLAYER);
        long toSupporters = mirror(game, anna, GameMember.SUPPORTER);
        long upload = start(game, new GameOperation.LiveUploadStarted(GameState.MANUAL, anna.id(), null, true, true,
                false, 5, T0.plusSeconds(60).toString(), "Left the zone", null, null, null, null, null));
        JsonNode started = events(GameEvent.LIVE, GameLiveEvents.STARTED).getFirst();
        assertEquals(List.of("hunters", "players"), GameVectors.strings(started.get("audiences")));
        assertEquals("Left the zone", started.get("reason").asText());
        assertEquals(anna.displayName(), started.get("displayName").asText());
        // The Tool loses control: the upload goes on.
        noLease();
        startRunner();

        advance(1);
        assertArrayEquals(new double[] {46.0, 7.0}, latest(toHunters));
        assertArrayEquals(new double[] {46.0, 7.0}, latest(toPlayers));
        assertNull(latest(toSupporters));
        assertEquals(T0.plusSeconds(1), upload(game, upload).lastSampleAt());
        assertEquals(GamePingEngine.SENT, upload(game, upload).lastStatus());
        assertEquals(List.of("started", "sample"), types(upload));
        JsonNode sample = events(GameEvent.LIVE, GameLiveEvents.SAMPLE).getFirst();
        assertEquals(List.of("hunters", "players"), GameVectors.strings(sample.get("delivered")));
        assertEquals(46.0, sample.get("position").get("latitude").asDouble());
        // Hunters see the share while the upload is active, whatever the lease does.
        assertEquals(List.of(anna.id()), state.liveShares(game.session()).stream()
                .map(GameStateService.LiveShare::memberId).toList());

        // Ticks follow the interval: 1, 6, 11, 16 s; each sends the current position; an unchanged outcome
        // writes no event.
        trackerPosition(anna.trackerId(), 46.1, 7.1, T0.plusSeconds(3));
        advanceTo(T0.plusSeconds(5));
        assertEquals(1, positions(toHunters));
        advanceTo(T0.plusSeconds(6));
        assertEquals(2, positions(toHunters));
        assertArrayEquals(new double[] {46.1, 7.1}, latest(toHunters));
        assertEquals(T0.plusSeconds(11), runner.nextTick(upload));
        // Another Tool takes control (a new epoch): the upload goes on.
        lease(game.session(), EPOCH + 1);
        assertEquals(List.of(anna.id()), state.liveShares(game.session()).stream()
                .map(GameStateService.LiveShare::memberId).toList());
        advanceTo(T0.plusSeconds(16));
        assertEquals(4, positions(toHunters));
        assertEquals(List.of("started", "sample"), types(upload));

        // A gap (the execution was held up for 25 s): one late tick sends the current position, nothing is replayed.
        stopGame(game.session());
        advanceTo(T0.plusSeconds(41));
        assertEquals(4, positions(toHunters));
        startGame(game.session());
        advance(1);
        assertEquals(5, positions(toHunters));
        assertEquals(T0.plusSeconds(47), runner.nextTick(upload));
        advanceTo(T0.plusSeconds(46));
        assertEquals(5, positions(toHunters));

        // After a restart, a new runner ticks every active upload at once.
        scheduler.shutdown();
        startRunner();
        advance(1);
        assertEquals(6, positions(toHunters));

        // The deadline ends it.
        advanceTo(T0.plusSeconds(60));
        GameState.LiveUpload ended = upload(game, upload);
        assertEquals(GameState.ENDED, ended.state());
        assertEquals(GameLiveEvents.DEADLINE, ended.endReason());
        JsonNode end = events(GameEvent.LIVE, GameLiveEvents.ENDED).getFirst();
        assertEquals(GameLiveEvents.DEADLINE, end.get("endReason").asText());
        assertEquals(List.of("hunters", "players"), GameVectors.strings(end.get("audiences")));
        long sent = positions(toHunters);
        advance(20);
        assertEquals(sent, positions(toHunters));
        assertTrue(state.liveShares(game.session()).isEmpty());
        assertEquals(List.of("started", "sample", "ended"), types(upload));
    }

    @Test
    void aTickThatReachesItsTransactionAfterTheDeadlineSendsNothing() throws Exception {
        // Review SE-7: a tick decided before the deadline but run after it writes no sample.
        Game game = game();
        lease(game.session(), EPOCH);
        long upload = start(game, new GameOperation.LiveUploadStarted(GameState.MANUAL, game.anna().id(), null, true,
                false, false, 5, T0.plusSeconds(30).toString(), null, null, null, null, null, null));
        startRunner();
        GamePingEngine.Hooks hooks = runner.hooks(game.session(), upload);
        GameTransactions.Work<Boolean> proceed = transaction -> {
            transaction.lockSession(game.session());
            return hooks.proceed(transaction);
        };
        clock.set(T0.plusSeconds(29));
        assertTrue(transactions.write(proceed));
        clock.set(T0.plusSeconds(30));
        assertFalse(transactions.write(proceed));
    }

    @Test
    void waitingForLocationIsWrittenOnceAndTheFirstPositionResumesTheUploads() throws Exception {
        Game game = game();
        GameMember ben = game.ben();
        lease(game.session(), EPOCH);
        long toHunters = mirror(game, ben, GameMember.HUNTER);
        long upload = start(game, live(GameState.GEOFENCE_AUTO, ben, true, false, false, 2, null));
        startRunner();

        advance(7);
        assertEquals(List.of("started", "waitingForLocation"), types(upload));
        assertEquals(GamePingEngine.FAILED, upload(game, upload).lastStatus());
        assertEquals(GamePingEngine.NO_POSITION, upload(game, upload).lastReason());
        assertNull(upload(game, upload).lastSampleAt());
        assertNull(latest(toHunters));

        trackerPosition(ben.trackerId(), 45.0, 6.0, clock.instant());
        advance(2);
        assertArrayEquals(new double[] {45.0, 6.0}, latest(toHunters));
        JsonNode sample = events(GameEvent.LIVE, GameLiveEvents.SAMPLE).getFirst();
        assertTrue(sample.get("resumed").asBoolean());
        assertEquals(GameState.GEOFENCE_AUTO, sample.get("purpose").asText());
        assertEquals(List.of("started", "waitingForLocation", "sample"), types(upload));
    }

    @Test
    void aBreakWithholdsTheHuntersAndAFailingTickKeepsTheUploadRunning() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        lease(game.session(), EPOCH);
        trackerPosition(anna.trackerId(), 46.0, 7.0, T0);
        long toHunters = mirror(game, anna, GameMember.HUNTER);
        long toPlayers = mirror(game, anna, GameMember.PLAYER);
        long upload = start(game, live(GameState.MANUAL, anna, true, true, false, 5, null));
        startRunner();
        advance(1);
        assertEquals(1, positions(toHunters));

        // A break starting during a live upload keeps only the other audiences.
        onBreak(game.session(), anna, clock.instant().plusSeconds(60));
        advance(5);
        assertEquals(1, positions(toHunters));
        assertEquals(2, positions(toPlayers));
        JsonNode withheld = events(GameEvent.LIVE, GameLiveEvents.SAMPLE).getLast();
        assertEquals(GamePingEngine.SKIPPED, withheld.get("status").asText());
        assertEquals(GamePingEngine.BREAK, withheld.get("reason").asText());
        assertEquals(List.of("hunters"), GameVectors.strings(withheld.get("withheld")));
        assertTrue(state.liveShares(game.session()).isEmpty());

        // The players' mirror goes missing: the tick fails, the upload keeps ticking and recovers.
        execute("UPDATE tc_game_mirrors SET state = 'retiring' WHERE deviceid = ?", toPlayers);
        advance(5);
        assertEquals(GamePingEngine.NO_MIRROR, upload(game, upload).lastReason());
        assertEquals(GameState.ACTIVE, upload(game, upload).state());
        execute("UPDATE tc_game_mirrors SET state = 'active' WHERE deviceid = ?", toPlayers);
        advance(5);
        assertEquals(GamePingEngine.BREAK, upload(game, upload).lastReason());
        assertEquals(List.of("started", "sample", "sample", "sample", "sample"), types(upload));
    }

    // Endings -----------------------------------------------------------------------------------------------

    @Test
    void eachEndingEndsTheUploadWithItsEndedEvent() throws Exception {
        Game game = game();
        lease(game.session(), EPOCH);
        for (GameMember member : List.of(game.anna(), game.ben(), game.sue())) {
            trackerPosition(member.trackerId(), 46.0, 7.0, T0);
        }
        trackerPosition(game.hunter().trackerId(), 46.2, 7.2, T0);
        long stopped = start(game, live(GameState.MANUAL, game.ben(), true, false, false, 5, null));
        long annaLive = start(game, live(GameState.MANUAL, game.anna(), true, true, true, 5, null));
        long annaRelay = start(game, relay(game.anna(), 5, false, false));
        long sueLive = start(game, live(GameState.MANUAL, game.sue(), false, true, false, 5, null));
        long sueRelay = start(game, relay(game.sue(), 5, false, false));
        startRunner();
        advance(1);
        assertArrayEquals(new double[] {46.2, 7.2}, latest(mirror(game, game.hunter(), null, game.anna())));

        // An operator's stop (a liveUploadEnded intent).
        tool(game, new GameOperation.LiveUploadEnded(stopped, "stopped"));
        assertEquals("stopped", upload(game, stopped).endReason());
        long sent = positions(mirror(game, game.ben(), GameMember.HUNTER));
        advance(10);
        assertEquals(sent, positions(mirror(game, game.ben(), GameMember.HUNTER)));
        assertStatus(409, () -> tool(game, new GameOperation.LiveUploadEnded(stopped, "stopped")));

        // A catch ends the member's own uploads and the relays to them, in the catch's transaction.
        tool(game, new GameOperation.Catch(game.anna().id(), null));
        assertEquals(GameLiveEvents.CAUGHT, upload(game, annaLive).endReason());
        assertEquals(GameLiveEvents.CAUGHT, upload(game, annaRelay).endReason());
        assertEquals(GameLiveEvents.CAUGHT, events(GameEvent.RELAY, GameLiveEvents.ENDED).getFirst()
                .get("endReason").asText());

        // A retirement ends them too.
        members.retire(game.session(), game.sue().id());
        assertEquals(GameLiveEvents.RETIRED, upload(game, sueLive).endReason());
        assertEquals(GameLiveEvents.RETIRED, upload(game, sueRelay).endReason());

        // Stopping the game ends what is left; nothing ticks while it is stopped.
        long last = start(game, live(GameState.MANUAL, game.ben(), true, false, false, 5, null));
        advance(1);
        noLease();
        execution.change(game.session(), GameExecutionService.STOPPED, operator, null);
        assertEquals(GameExecutionService.GAME_STOPPED, upload(game, last).endReason());
        assertEquals(List.of(stopped, annaLive, annaRelay, sueLive, sueRelay, last), publisher.events.stream()
                .filter(event -> event.data().path("type").asText().equals(GameLiveEvents.ENDED))
                .map(event -> event.data().get("liveUploadId").asLong()).toList());
        assertTrue(state.liveUploads(game.session()).stream().noneMatch(GameState.LiveUpload::active));
    }

    // Relays ------------------------------------------------------------------------------------------------

    @Test
    void aRelayUsesItsJokerWithItsFirstDeliveringTickAndEndsWithoutOne() throws Exception {
        Game game = game();
        GameMember sue = game.sue();
        lease(game.session(), EPOCH);
        jokers.change(game.session(), new GameJokerService.Change(sue.id(), GameJokerRepository.HUNTER_POSITIONS, 1,
                null), "Operator");
        long relay = start(game, relay(sue, 10, true, false));
        // Starting it uses nothing yet.
        assertEquals(0, used(game, sue));
        startRunner();
        advance(1);
        // No hunter has a position: nothing is relayed, no joker is used.
        assertEquals(List.of("started", "waitingForLocation"), types(relay));
        assertEquals(0, used(game, sue));

        Instant fix = clock.instant().minusSeconds(30);
        trackerPosition(game.hunter().trackerId(), 46.2, 7.2, fix);
        // A break does not affect relays to the member.
        onBreak(game.session(), sue, clock.instant().plusSeconds(600));
        advance(10);
        long toSue = mirror(game, game.hunter(), null, sue);
        assertArrayEquals(new double[] {46.2, 7.2}, latest(toSue));
        // The relayed position keeps the hunter's own fix time.
        assertEquals(fix, fixTime(toSue));
        assertEquals(1, used(game, sue));
        JsonNode sample = events(GameEvent.RELAY, GameLiveEvents.SAMPLE).getFirst();
        assertFalse(sample.get("joker").get("overAllocated").asBoolean());
        assertEquals(1, sample.get("hunters").asInt());
        assertEquals("1 hunter(s) without a position", sample.get("detail").asText());
        assertEquals(clock.instant(), upload(game, relay).lastSampleAt());

        // Later ticks use nothing more.
        trackerPosition(game.hunter().trackerId(), 46.3, 7.3, clock.instant());
        advance(10);
        assertArrayEquals(new double[] {46.3, 7.3}, latest(toSue));
        assertEquals(1, used(game, sue));
        assertEquals(List.of("started", "waitingForLocation", "sample"), types(relay));

        // Stopped and started again: no joker is left, so a new activation needs a use beyond the allocation.
        tool(game, new GameOperation.LiveUploadEnded(relay, "stopped"));
        assertStatus(409, () -> start(game, relay(sue, 10, true, false)));
        long again = start(game, relay(sue, 10, true, true));
        advance(10);
        assertEquals(2, used(game, sue));
        assertTrue(events(GameEvent.RELAY, GameLiveEvents.SAMPLE).getLast().get("joker").get("overAllocated")
                .asBoolean());
        tool(game, new GameOperation.LiveUploadEnded(again, "stopped"));

        // A joker taken before the first delivering tick ends the relay (noJoker).
        jokers.change(game.session(), new GameJokerService.Change(sue.id(), GameJokerRepository.HUNTER_POSITIONS, 2,
                null), "Operator");
        long gone = start(game, relay(sue, 10, true, false));
        jokers.change(game.session(), new GameJokerService.Change(sue.id(), GameJokerRepository.HUNTER_POSITIONS, -1,
                null), "Operator");
        advance(10);
        GameState.LiveUpload ended = upload(game, gone);
        assertEquals(GameLiveEvents.NO_JOKER, ended.endReason());
        assertEquals(2, used(game, sue));
        assertEquals(List.of(), jokers.reconcile(game.session()));
    }

    private int used(Game game, GameMember member) throws Exception {
        return jokers.own(member).inventories().stream()
                .filter(item -> item.type().equals(GameJokerRepository.HUNTER_POSITIONS)).findFirst()
                .map(GameJokerService.Inventory::used).orElse(0);
    }

    private Instant fixTime(long device) throws Exception {
        long position = number("SELECT positionid FROM tc_devices WHERE id = ?", device);
        return transactions.read(transaction -> transaction.first("SELECT fixtime FROM tc_positions WHERE id = ?",
                rows -> rows.getTimestamp("fixtime").toInstant(), position).orElseThrow());
    }

    // Speedhunt live pings ----------------------------------------------------------------------------------

    @Test
    void aLivePingDeliversItsPingWithItsFirstSampleAndCompletesTheSpeedhuntWhenItEnds() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        lease(game.session(), EPOCH);
        jokers.change(game.session(), new GameJokerService.Change(null, GameJokerRepository.SPEEDHUNT, 1, null),
                "Operator");
        long toHunters = mirror(game, anna, GameMember.HUNTER);
        long revision = memberRevision(anna.id());
        long first = start(game, new GameOperation.LiveUploadStarted(GameState.SPEEDHUNT_PING, anna.id(), null, true,
                false, false, 10, T0.plusSeconds(120).toString(), null, null, null, null, null, "Operator Olga"));
        assertEquals(1, upload(game, first).pingNumber());
        assertEquals(revision + 1, memberRevision(anna.id()));
        startRunner();
        advance(1);
        // No position yet: the ping waits, nothing is counted, no joker is used.
        assertTrue(state.runs(game.session()).isEmpty());
        assertTrue(state.speedhuntRunning(game.session()));
        assertEquals(List.of("started", "waitingForLocation"), types(first));

        trackerPosition(anna.trackerId(), 46.0, 7.0, clock.instant());
        advance(10);
        var run = state.runs(game.session()).getFirst();
        assertEquals("Operator Olga", run.run().startedBy());
        assertEquals(1, run.pings().size());
        assertEquals(GameState.LIVE, run.pings().getFirst().mode());
        assertEquals(run.pings().getFirst().id(), upload(game, first).pingId());
        assertEquals(1, jokers.pool(game.session()).inventories().getFirst().used());
        assertArrayEquals(new double[] {46.0, 7.0}, latest(toHunters));
        assertEquals(revision + 2, memberRevision(anna.id()));
        JsonNode delivered = events(GameEvent.LIVE, GameLiveEvents.SAMPLE).getFirst();
        assertEquals(1, delivered.get("ping").get("number").asInt());
        assertEquals(2, delivered.get("ping").get("limit").asInt());
        assertEquals(run.run().id(), delivered.get("ping").get("runId").asLong());
        assertTrue(delivered.get("resumed").asBoolean());
        // Later samples are samples: still one ping, no revision change hunters observe.
        advance(30);
        assertEquals(1, state.runs(game.session()).getFirst().pings().size());
        assertEquals(revision + 2, memberRevision(anna.id()));
        assertEquals(4, positions(toHunters));

        // Stopped by the operator: ping 1 of 2 does not complete the speedhunt.
        tool(game, new GameOperation.LiveUploadEnded(first, "stopped"));
        assertTrue(state.runs(game.session()).getFirst().run().active());
        JsonNode stop = events(GameEvent.LIVE, GameLiveEvents.ENDED).getFirst();
        assertTrue(stop.get("pingDelivered").asBoolean());
        assertFalse(stop.has("runCompleted"));

        // Ping 2 is the next one; a wrong number is refused.
        assertStatus(409, () -> start(game, new GameOperation.LiveUploadStarted(GameState.SPEEDHUNT_PING, anna.id(),
                null, true, false, false, 10, null, null, null, null, null, 3, null)));
        long second = start(game, live(GameState.SPEEDHUNT_PING, anna, true, false, false, 10,
                clock.instant().plusSeconds(60)));
        assertEquals(2, upload(game, second).pingNumber());
        advance(1);
        assertEquals(2, state.runs(game.session()).getFirst().pings().size());
        // The last ping runs until its deadline; then the speedhunt is completed in the same transaction.
        assertTrue(state.runs(game.session()).getFirst().run().active());
        advanceTo(upload(game, second).deadline());
        var completed = state.runs(game.session()).getFirst().run();
        assertEquals(GameState.COMPLETED, completed.outcome());
        assertEquals("ping limit", completed.reason());
        JsonNode end = events(GameEvent.LIVE, GameLiveEvents.ENDED).getLast();
        assertEquals(GameLiveEvents.DEADLINE, end.get("endReason").asText());
        assertEquals(completed.id(), end.get("runCompleted").asLong());
        assertFalse(state.speedhuntRunning(game.session()));
        // A new speedhunt needs a pool joker, held, available or allowed beyond the allocation.
        assertStatus(409, () -> start(game, new GameOperation.LiveUploadStarted(GameState.SPEEDHUNT_PING, anna.id(),
                null, true, false, false, 10, null, null, null, null, null, null, null)));
        assertEquals(List.of(), jokers.reconcile(game.session()));
    }

    @Test
    void aLivePingThatReachedNobodyReturnsTheHuntersJokerAndTheEndOfItsSpeedhuntEndsIt() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        lease(game.session(), EPOCH);
        jokers.change(game.session(), new GameJokerService.Change(null, GameJokerRepository.SPEEDHUNT, 1, null),
                "Operator");
        // A hunter's request holds the pool joker; its live ping starts with the request's outcome.
        GameRequest request = requests.submit(game.hunter(), new GameRequestService.Submission(GameRequest.LIVE_PING,
                anna.id(), memberRevision(anna.id()), null));
        requests.claim(game.session(), request.id(), EPOCH);
        long upload = effects.apply(game.session(), EPOCH, new GameEffectsService.Delivery(id(), List.of(
                new GameOperation.LiveUploadStarted(GameState.SPEEDHUNT_PING, anna.id(), null, true, false, false, 10,
                        T0.plusSeconds(30).toString(), null, request.id(), null, null, null, null)), null, null, null,
                null, List.of(new GameRequestService.Completion(request.id(), GameRequest.SUCCEEDED, null, null)),
                null)).operations().getFirst().id();
        assertEquals(request.id(), upload(game, upload).requestId());
        startRunner();
        // Anna never sends a position: at the deadline the joker held for her speedhunt returns.
        advanceTo(T0.plusSeconds(30));
        JsonNode end = events(GameEvent.LIVE, GameLiveEvents.ENDED).getFirst();
        assertTrue(end.get("pingFailed").asBoolean());
        assertFalse(end.get("pingDelivered").asBoolean());
        assertEquals(0, jokers.pool(game.session()).inventories().getFirst().used());
        assertEquals(1, jokers.pool(game.session()).inventories().getFirst().available());
        assertTrue(state.runs(game.session()).isEmpty());

        // A live ping that started a speedhunt ends with it (runEnded), without completing it.
        trackerPosition(anna.trackerId(), 46.0, 7.0, clock.instant());
        long next = start(game, live(GameState.SPEEDHUNT_PING, anna, true, false, false, 10, null));
        advance(1);
        var run = state.runs(game.session()).getFirst().run();
        tool(game, new GameOperation.RunEnded(anna.id(), GameState.TERMINATED, "manual"));
        assertEquals(GameLiveEvents.RUN_ENDED, upload(game, next).endReason());
        assertEquals(GameState.TERMINATED, state.runs(game.session()).getFirst().run().outcome());
        assertEquals(run.id(), state.runs(game.session()).getFirst().run().id());
        assertEquals(List.of(), jokers.reconcile(game.session()));
    }

    @Test
    void aLivePingCarriesTheTargetsOwnSpeedhuntFakeToItsHunterMirrorsOnly() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        lease(game.session(), EPOCH);
        jokers.change(game.session(), new GameJokerService.Change(null, GameJokerRepository.SPEEDHUNT, 1, null),
                "Operator");
        for (GameMember owner : List.of(anna, game.ben())) {
            jokers.change(game.session(), new GameJokerService.Change(owner.id(), GameJokerRepository.FAKE_PING, 1,
                    null), "Operator");
            var request = requests.submit(owner, new GameRequestService.Submission(GameRequest.FAKE_PING, null,
                    null, json("{\"pingNumber\": 1, \"latitude\": 46.5, \"longitude\": 7.0}")));
            requests.claim(game.session(), request.id(), EPOCH);
            effects.apply(game.session(), EPOCH, new GameEffectsService.Delivery(id(), List.of(), null,
                    null, null, null, List.of(new GameRequestService.Completion(request.id(), GameRequest.SUCCEEDED,
                            null, null)), null));
        }
        trackerPosition(anna.trackerId(), 47.1, 8.5, T0);
        long toHunters = mirror(game, anna, GameMember.HUNTER);
        long toPlayers = mirror(game, anna, GameMember.PLAYER);
        publisher.clear();
        start(game, live(GameState.SPEEDHUNT_PING, anna, true, true, false, 10, null));
        startRunner();
        // The first sample binds both armed fakes; Anna's own reaches her hunter mirror with a 5-20 m offset.
        advance(1);
        var ping = state.runs(game.session()).getFirst().pings().getFirst();
        assertEquals(GameState.JOKER_FAKE, ping.source());
        assertOffset(latest(toHunters)[0], 46.5);
        assertArrayEquals(new double[] {47.1, 8.5}, latest(toPlayers));
        JsonNode sample = events(GameEvent.LIVE, GameLiveEvents.SAMPLE).getFirst();
        assertTrue(sample.get("ping").get("jokerFake").asBoolean());
        for (int i = 0; i < 3; i++) {
            advance(10);
            assertOffset(latest(toHunters)[0], 46.5);
            assertArrayEquals(new double[] {47.1, 8.5}, latest(toPlayers));
        }
        assertEquals(List.of(), jokers.reconcile(game.session()));
    }

    @Test
    void aTickTheRulesRefuseEndsTheUpload() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        lease(game.session(), EPOCH);
        jokers.change(game.session(), new GameJokerService.Change(null, GameJokerRepository.SPEEDHUNT, 2, null),
                "Operator");
        long upload = start(game, live(GameState.SPEEDHUNT_PING, anna, true, false, false, 10, null));
        // Meanwhile ping 1 was delivered as a static ping: the live ping's ping 1 is no longer the next one.
        tool(game, new GameOperation.PingDelivered(anna.id(), 1, GameState.STATIC, GameState.REAL, true, false,
                false, null, null, "Operator", true));
        trackerPosition(anna.trackerId(), 46.0, 7.0, T0);
        startRunner();
        advance(1);
        GameState.LiveUpload ended = upload(game, upload);
        assertEquals(GameLiveEvents.REJECTED, ended.endReason());
        JsonNode end = events(GameEvent.LIVE, GameLiveEvents.ENDED).getFirst();
        assertTrue(end.get("detail").asText().contains("not the next ping"));
        assertEquals(1, state.runs(game.session()).getFirst().pings().size());
    }

}
