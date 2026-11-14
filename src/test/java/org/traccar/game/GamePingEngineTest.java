package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The server's ping engine (plan B3, "Engine (Java)" verification): every rule of the shared policy vectors against
 * a database, coordinate splits and fakes, the freshness wait (sent as soon as a fresh fix arrives, through the
 * position hook or the poll, otherwise the last known position marked stale), {@code notAfter}, the atomicity of
 * positions, outcome, operations and log, and that the wait holds no lock. Runs on H2 ({@link GamePingEngineH2Test})
 * and MySQL ({@link GamePingEngineMySqlTest}).
 */
public abstract class GamePingEngineTest extends GameTestBase {

    private static final double REAL_LAT = 46.9;
    private static final double REAL_LON = 7.4;

    private GamePingEngine engine;
    private GameTimetableService timetables;

    @Override
    protected void createServices() {
        engine = engine();
        timetables = new GameTimetableService(transactions, mapper, publisher);
    }

    @AfterEach
    void reset() {
        clock.set(T0);
        scheduler.shutdown();
    }

    // Fixture -----------------------------------------------------------------------------------------------

    /** A session with a source player and the members and mirrors a case asks for. */
    private record Fixture(long session, GameMember source, Map<String, Long> mirrors, List<GameMember> hunters,
                           List<Long> hunterMirrors, long reservation) {
    }

    private static String role(String audience) {
        return switch (audience) {
            case "hunters" -> GameMember.HUNTER;
            case "players" -> GameMember.PLAYER;
            default -> GameMember.SUPPORTER;
        };
    }

    private Fixture participantFixture(JsonNode state) throws Exception {
        long session = session();
        GameMember source = state.get("tracked").asBoolean() ? bound(session, GameMember.PLAYER)
                : members.bindAccount(session, plan(session, GameMember.PLAYER).id(), user());
        if (state.get("otherPlayers").asBoolean()) {
            bound(session, GameMember.PLAYER);
        }
        if (state.get("otherSupporters").asBoolean()) {
            bound(session, GameMember.SUPPORTER);
        }
        bound(session, GameMember.HUNTER);
        Map<String, Long> mirrors = new java.util.HashMap<>();
        for (String audience : GameVectors.strings(state.get("mirrors"))) {
            mirrors.put(audience, resources.addMirror(session, device(), source.id(), role(audience), null)
                    .deviceId());
        }
        String position = state.get("position").asText();
        if (!position.equals("none") && source.trackerId() != null) {
            trackerPosition(source.trackerId(), REAL_LAT, REAL_LON,
                    T0.minusSeconds(position.equals("stale") ? 60 : 5));
        }
        long reservation = 0;
        if (state.get("jokerFake").asBoolean()) {
            long slot = timetable(timetables, session, List.of(source), row(T0.plusSeconds(600), source, ""))
                    .getFirst();
            reservation = armFake(session, source, entry(timetables, session, slot, source.id()).id(), 1.5, 2.5);
        }
        if (state.get("caught").asBoolean()) {
            caught(session, source);
        }
        if (state.get("onBreak").asBoolean()) {
            onBreak(session, source, T0.plusSeconds(600));
        }
        if (!state.get("active").asBoolean()) {
            source = members.retire(session, source.id());
        }
        return new Fixture(session, source, mirrors, List.of(), List.of(), reservation);
    }

    private static GamePingEngine.Intent intent(GameMember source, List<String> audiences,
                                                GamePingEngine.Fake fake, Instant minimumFixTime, Instant notAfter) {
        return new GamePingEngine.Intent(GamePingEngine.TIMETABLE, source.id(), null, audiences, fake,
                minimumFixTime == null ? null : new GamePingEngine.Freshness(minimumFixTime.toString(), 0),
                notAfter == null ? null : notAfter.toString(), null, null);
    }

    private GamePingEngine.Outcome run(long session, GamePingEngine.Intent intent, GamePingEngine.Context context)
            throws Exception {
        return engine.run(session, intent, context, GamePingEngine.Hooks.NONE, null);
    }

    // Policy vectors ----------------------------------------------------------------------------------------

    @Test
    void everyPolicyCaseHoldsAgainstTheDatabase() throws Exception {
        JsonNode document = GameVectors.load("ping-policy.json");
        for (JsonNode item : document.get("cases")) {
            String name = item.get("name").asText();
            JsonNode state = GameVectors.state(document, "stateDefaults", item);
            Fixture fixture = participantFixture(state);
            JsonNode input = item.get("intent");
            List<String> fakeAudiences = GameVectors.strings(input.path("fake").path("audiences"));
            var intent = new GamePingEngine.Intent(input.get("path").asText(), fixture.source().id(), null,
                    GameVectors.strings(input.get("audiences")), fakeAudiences.isEmpty() ? null
                            : new GamePingEngine.Fake(47.0, 8.0, fakeAudiences),
                    null, state.get("pastNotAfter").asBoolean() ? T0.minusSeconds(1).toString() : null, null, null);
            // The freshness window only marks staleness; the wait is the runner's.
            intent = new GamePingEngine.Intent(intent.path(), intent.sourceMemberId(), null, intent.audiences(),
                    intent.fake(), new GamePingEngine.Freshness(T0.minusSeconds(20).toString(), 0), intent.notAfter(),
                    null, null);
            var context = new GamePingEngine.Context(T0, fixture.reservation() == 0 ? null
                    : new GamePingEngine.JokerFake(fixture.reservation(), 1.5, 2.5));
            var outcome = run(fixture.session(), intent, context);

            JsonNode expected = item.get("expected");
            assertEquals(expected.get("status").asText(), outcome.status(), name);
            assertEquals(expected.get("reason").isNull() ? null : expected.get("reason").asText(), outcome.reason(),
                    name);
            List<String> real = GameVectors.strings(expected.get("real"));
            List<String> fake = GameVectors.strings(expected.get("fake"));
            List<String> delivered = TimetableInstruction.AUDIENCES.stream()
                    .filter(audience -> real.contains(audience) || fake.contains(audience)).toList();
            assertEquals(delivered, outcome.delivered(), name);
            assertEquals(GameVectors.strings(expected.get("withheld")), outcome.withheld(), name);
            assertEquals(delivered.size(), outcome.positions().size(), name);
            boolean joker = expected.get("jokerFakeUsed").asBoolean();
            if (fixture.reservation() != 0) {
                assertEquals(joker ? GameJokerRepository.CONSUMED : GameJokerRepository.RELEASED,
                        reservationStatus(fixture.reservation()), name);
                assertEquals(joker ? GameJokerRepository.CONSUMED : GameJokerRepository.RELEASED,
                        outcome.jokerFake().status(), name);
            }
            assertEquals(expected.get("stale").asBoolean(), outcome.position() != null && outcome.position().stale(),
                    name);
            for (var mirror : fixture.mirrors().entrySet()) {
                double[] coordinate = latest(mirror.getValue());
                if (fake.contains(mirror.getKey())) {
                    assertArrayEquals(new double[] {47.0, 8.0}, coordinate, name);
                } else if (real.contains(mirror.getKey())) {
                    assertArrayEquals(joker && mirror.getKey().equals("hunters") ? new double[] {1.5, 2.5}
                            : new double[] {REAL_LAT, REAL_LON}, coordinate, name);
                } else {
                    assertNull(coordinate, name + ": nothing written to " + mirror.getKey());
                }
            }
        }
    }

    @Test
    void everyRelayCaseHoldsAgainstTheDatabase() throws Exception {
        JsonNode document = GameVectors.load("ping-policy.json");
        for (JsonNode item : document.get("relays")) {
            String name = item.get("name").asText();
            JsonNode state = GameVectors.state(document, "relayStateDefaults", item);
            long session = session();
            GameMember recipient = bound(session, GameMember.PLAYER);
            List<GameMember> hunters = new ArrayList<>();
            List<Long> devices = new ArrayList<>();
            for (JsonNode hunter : state.get("hunters")) {
                GameMember member = bound(session, GameMember.HUNTER);
                hunters.add(member);
                devices.add(hunter.get("mirror").asBoolean()
                        ? resources.addMirror(session, device(), member.id(), null, recipient.id()).deviceId() : null);
                if (hunter.get("position").asBoolean()) {
                    trackerPosition(member.trackerId(), 46.0 + hunters.size(), 7.0, T0.minusSeconds(30));
                }
            }
            if (state.get("caught").asBoolean()) {
                caught(session, recipient);
            }
            if (state.get("onBreak").asBoolean()) {
                onBreak(session, recipient, T0.plusSeconds(600));
            }
            if (!state.get("active").asBoolean()) {
                members.retire(session, recipient.id());
            }
            var outcome = run(session, new GamePingEngine.Intent(GamePingEngine.RELAY, null, recipient.id(), null,
                    null, null, state.get("pastNotAfter").asBoolean() ? T0.minusSeconds(1).toString() : null, null,
                    null), GamePingEngine.Context.NONE);
            JsonNode expected = item.get("expected");
            assertEquals(expected.get("status").asText(), outcome.status(), name);
            assertEquals(expected.get("reason").isNull() ? null : expected.get("reason").asText(), outcome.reason(),
                    name);
            List<Long> relayed = new ArrayList<>();
            expected.get("relayed").forEach(index -> relayed.add(hunters.get(index.asInt()).id()));
            assertEquals(relayed, outcome.hunters(), name);
            for (int i = 0; i < hunters.size(); i++) {
                if (devices.get(i) == null) {
                    continue;
                }
                boolean sent = relayed.contains(hunters.get(i).id());
                assertEquals(sent, latest(devices.get(i)) != null, name);
            }
            // A relayed position keeps the hunter's own fix time, so its age stays visible.
            for (var written : outcome.positions()) {
                assertEquals(T0.minusSeconds(30).toString(), written.fixTime(), name);
            }
        }
    }

    // Writing -----------------------------------------------------------------------------------------------

    private record Game(long session, GameMember anna, GameMember ben, GameMember hunter, long annaToHunters,
                        long annaToPlayers, long annaToSupporters, long benToHunters) {
    }

    private Game game() throws Exception {
        long session = session();
        GameMember anna = bound(session, GameMember.PLAYER);
        GameMember ben = bound(session, GameMember.PLAYER);
        bound(session, GameMember.SUPPORTER);
        GameMember hunter = bound(session, GameMember.HUNTER);
        return new Game(session, anna, ben, hunter,
                resources.addMirror(session, device(), anna.id(), GameMember.HUNTER, null).deviceId(),
                resources.addMirror(session, device(), anna.id(), GameMember.PLAYER, null).deviceId(),
                resources.addMirror(session, device(), anna.id(), GameMember.SUPPORTER, null).deviceId(),
                resources.addMirror(session, device(), ben.id(), GameMember.HUNTER, null).deviceId());
    }

    private String attributes(long device) throws Exception {
        return text("SELECT p.attributes FROM tc_positions p JOIN tc_devices d ON d.positionid = p.id"
                + " WHERE d.id = ?", device);
    }

    @Test
    void fakeAndRealPositionsShareOnePayloadAndTheTransactionTime() throws Exception {
        Game game = game();
        trackerPosition(game.anna().trackerId(), REAL_LAT, REAL_LON, T0.minusSeconds(40));
        clock.set(T0.plusMillis(1500));
        publisher.clear();
        var outcome = run(game.session(), intent(game.anna(), List.of("players", "supporters"),
                new GamePingEngine.Fake(47.0, 8.0, List.of("hunters")), T0.minusSeconds(20), null),
                new GamePingEngine.Context(T0, null));
        assertEquals(GamePingEngine.SENT, outcome.status());
        assertEquals(List.of("hunters", "players", "supporters"), outcome.delivered());
        assertEquals(new GamePingEngine.Fake(47.0, 8.0, List.of("hunters")), outcome.fake());
        assertTrue(outcome.position().stale());
        assertEquals(1.5, outcome.delaySeconds());
        assertEquals(attributes(game.annaToHunters()), attributes(game.annaToPlayers()));
        assertTrue(attributes(game.annaToHunters()).contains("\"event\":\"location\""));
        // Every position carries the transaction's time in whole seconds, never the stale fix time.
        for (var written : outcome.positions()) {
            assertEquals(T0.plusSeconds(1).toString(), written.fixTime());
        }
        assertEquals(List.of(game.annaToHunters(), game.annaToPlayers(), game.annaToSupporters()),
                publisher.devices);
        assertEquals(1, publisher.positions.stream().filter(position -> position.getDeviceId()
                == game.annaToHunters()).count());
    }

    @Test
    void positionsOutcomeOperationsAndLogCommitTogether() throws Exception {
        Game game = game();
        trackerPosition(game.anna().trackerId(), REAL_LAT, REAL_LON, T0);
        long slot = timetable(timetables, game.session(), List.of(game.anna()), row(T0.plusSeconds(600),
                game.anna(), "")).getFirst();
        long fake = armFake(game.session(), game.anna(), entry(timetables, game.session(), slot, game.anna().id())
                .id(), 1.5, 2.5);
        long positions = number("SELECT COUNT(*) FROM tc_positions");
        long logs = number("SELECT COUNT(*) FROM tc_game_session_logs");
        long revision = revision(game.session());
        publisher.clear();
        var context = new GamePingEngine.Context(T0, new GamePingEngine.JokerFake(fake, 1.5, 2.5));
        var intent = intent(game.anna(), TimetableInstruction.AUDIENCES, null, null, null);
        var effects = new GamePingEngine.Effects(List.of(new GameOperation.Catch(game.ben().id(), "Hunter")), null,
                null);
        var withEffects = new GamePingEngine.Intent(intent.path(), intent.sourceMemberId(), null, intent.audiences(),
                null, null, null, effects, null);
        // A failure while recording the outcome rolls back the positions, the joker fake and the operations.
        assertThrows(IllegalStateException.class, () -> engine.run(game.session(), withEffects, context,
                new GamePingEngine.Hooks() {
                    @Override
                    public void completed(GameTransaction transaction, GamePingEngine.Outcome outcome)
                            throws Exception {
                        GameLogService.event(transaction, publisher, game.session(), GameEvent.TIMETABLE, "ping",
                                JsonNodeFactory.instance.objectNode().put("type", "ping"));
                        throw new IllegalStateException("outcome not recorded");
                    }
                }, null));
        assertEquals(positions, number("SELECT COUNT(*) FROM tc_positions"));
        assertEquals(logs, number("SELECT COUNT(*) FROM tc_game_session_logs"));
        assertEquals(GameJokerRepository.RESERVED, reservationStatus(fake));
        assertEquals(0, number("SELECT COUNT(*) FROM tc_game_catches WHERE memberid = ?", game.ben().id()));
        assertEquals(revision, revision(game.session()));
        assertTrue(publisher.positions.isEmpty() && publisher.events.isEmpty() && publisher.invalidations.isEmpty());

        // Committed: all of it, published after commit.
        var outcome = engine.run(game.session(), withEffects, context, new GamePingEngine.Hooks() {
            @Override
            public void completed(GameTransaction transaction, GamePingEngine.Outcome outcome) throws Exception {
                GameLogService.event(transaction, publisher, game.session(), GameEvent.TIMETABLE, "ping",
                        JsonNodeFactory.instance.objectNode().put("type", "ping"));
            }
        }, null);
        assertEquals(3, outcome.positions().size());
        assertEquals("catch", outcome.operations().getFirst().operation());
        assertEquals(GameJokerRepository.CONSUMED, reservationStatus(fake));
        assertArrayEquals(new double[] {1.5, 2.5}, latest(game.annaToHunters()));
        assertArrayEquals(new double[] {REAL_LAT, REAL_LON}, latest(game.annaToPlayers()));
        assertEquals(1, number("SELECT COUNT(*) FROM tc_game_catches WHERE memberid = ?", game.ben().id()));
        assertEquals(logs + 1, number("SELECT COUNT(*) FROM tc_game_session_logs"));
        assertEquals(1, publisher.events.size());
        assertFalse(publisher.topic(GamePublisher.JOKERS).isEmpty());
        assertFalse(publisher.topic(GamePublisher.STATUS).isEmpty());
    }

    @Test
    void aDeclinedIntentWritesNothing() throws Exception {
        Game game = game();
        trackerPosition(game.anna().trackerId(), REAL_LAT, REAL_LON, T0);
        long revision = revision(game.session());
        assertNull(engine.run(game.session(), intent(game.anna(), List.of("hunters"), null, null, null),
                GamePingEngine.Context.NONE, new GamePingEngine.Hooks() {
                    @Override
                    public boolean proceed(GameTransaction transaction) {
                        return false;
                    }
                }, null));
        assertNull(latest(game.annaToHunters()));
        assertEquals(revision, revision(game.session()));
    }

    // Freshness wait ----------------------------------------------------------------------------------------

    private CompletableFuture<GamePingEngine.Outcome> waiting(Game game, GameMember source, int waitSeconds,
                                                              Instant notAfter) {
        return engine.execute(game.session(), new GamePingEngine.Intent(GamePingEngine.TIMETABLE, source.id(), null,
                List.of("hunters"), null, new GamePingEngine.Freshness(T0.minusSeconds(20).toString(), waitSeconds),
                notAfter == null ? null : notAfter.toString(), null, null),
                new GamePingEngine.Context(T0, null), GamePingEngine.Hooks.NONE);
    }

    @Test
    void aFreshFixArrivingDuringTheWaitIsSentAtOnce() throws Exception {
        Game game = game();
        trackerPosition(game.anna().trackerId(), REAL_LAT, REAL_LON, T0.minusSeconds(120));
        var pending = waiting(game, game.anna(), 30, null);
        scheduler.runPending();
        assertFalse(pending.isDone());
        assertEquals(1, waiter.pending());
        scheduler.advance(Duration.ofSeconds(4));
        assertFalse(pending.isDone());
        // Another device's fix and an older fix of the tracker do not end the wait.
        waiter.arrived(arriving(game.ben().trackerId(), 1, 1, T0.plusSeconds(4)));
        waiter.arrived(arriving(game.anna().trackerId(), 1, 1, T0.minusSeconds(21)));
        scheduler.runPending();
        assertFalse(pending.isDone());
        // The fresh fix is used directly: with position batching it may not be stored yet.
        waiter.arrived(arriving(game.anna().trackerId(), 46.5, 7.5, T0.plusSeconds(3)));
        scheduler.runPending();
        var outcome = pending.getNow(null);
        assertNotNull(outcome);
        assertEquals(GamePingEngine.SENT, outcome.status());
        assertFalse(outcome.position().stale());
        assertEquals(T0.plusSeconds(3).toString(), outcome.position().fixTime());
        assertEquals(4.0, outcome.delaySeconds());
        assertArrayEquals(new double[] {46.5, 7.5}, latest(game.annaToHunters()));
        // The wait is over: no timer, poll or hook is left behind.
        assertEquals(0, waiter.pending());
        assertEquals(0, scheduler.pending());
    }

    @Test
    void thePollFindsAFreshFixTheHookMissed() throws Exception {
        Game game = game();
        var pending = waiting(game, game.anna(), 30, null);
        scheduler.runPending();
        scheduler.advance(Duration.ofSeconds(2));
        trackerPosition(game.anna().trackerId(), 46.6, 7.6, T0.plusSeconds(2));
        scheduler.advance(Duration.ofMillis(GamePingEngine.POLL_MILLIS));
        var outcome = pending.getNow(null);
        assertNotNull(outcome);
        assertFalse(outcome.position().stale());
        assertArrayEquals(new double[] {46.6, 7.6}, latest(game.annaToHunters()));
        assertEquals(0, scheduler.pending());
    }

    @Test
    void afterTheWaitTheLastKnownPositionIsUsedAndMarkedStale() throws Exception {
        Game game = game();
        trackerPosition(game.anna().trackerId(), REAL_LAT, REAL_LON, T0.minusSeconds(120));
        var pending = waiting(game, game.anna(), 30, null);
        scheduler.runPending();
        scheduler.advance(Duration.ofSeconds(29));
        assertFalse(pending.isDone());
        scheduler.advance(Duration.ofSeconds(1));
        var outcome = pending.getNow(null);
        assertEquals(GamePingEngine.SENT, outcome.status());
        assertTrue(outcome.position().stale());
        assertEquals(T0.minusSeconds(120).toString(), outcome.position().fixTime());
        assertEquals(30.0, outcome.delaySeconds());
        // Without any position at all the wait ends the same way, and the ping fails.
        var none = waiting(game, game.ben(), 5, null);
        scheduler.runPending();
        scheduler.advance(Duration.ofSeconds(5));
        assertEquals(GamePingEngine.NO_POSITION, none.getNow(null).reason());
        assertEquals(0, waiter.pending());
    }

    @Test
    void waitsRunInParallelAndHoldNoLock() throws Exception {
        Game game = game();
        var anna = waiting(game, game.anna(), 30, null);
        var ben = waiting(game, game.ben(), 30, null);
        scheduler.runPending();
        assertEquals(2, waiter.pending());
        // While both wait, no transaction is open: another writer of the session is not blocked.
        long revision = transactions.write(transaction -> {
            transaction.lockSession(game.session());
            return transaction.bumpSession();
        });
        assertEquals(revision, revision(game.session()));
        scheduler.advance(Duration.ofSeconds(10));
        waiter.arrived(arriving(game.ben().trackerId(), 45.0, 7.0, T0.plusSeconds(10)));
        scheduler.runPending();
        assertTrue(ben.isDone());
        assertFalse(anna.isDone(), "one slow tracker never delays the others");
        scheduler.advance(Duration.ofSeconds(5));
        waiter.arrived(arriving(game.anna().trackerId(), 45.5, 7.0, T0.plusSeconds(15)));
        scheduler.runPending();
        assertEquals(15.0, anna.getNow(null).delaySeconds());
        assertEquals(10.0, ben.getNow(null).delaySeconds());
    }

    @Test
    void noWaitWhenNothingNeedsAFreshFix() throws Exception {
        Game game = game();
        trackerPosition(game.anna().trackerId(), REAL_LAT, REAL_LON, T0.minusSeconds(5));
        var fresh = waiting(game, game.anna(), 30, null);
        scheduler.runPending();
        assertTrue(fresh.isDone());
        caught(game.session(), game.ben());
        var caught = waiting(game, game.ben(), 30, null);
        scheduler.runPending();
        assertEquals(GamePingEngine.CAUGHT, caught.getNow(null).reason());
        // A hunters-only ping during a break reaches nobody: it does not even wait for the position.
        execute("DELETE FROM tc_game_catches WHERE memberid = ?", game.ben().id());
        onBreak(game.session(), game.ben(), T0.plusSeconds(600));
        var withheld = waiting(game, game.ben(), 30, null);
        scheduler.runPending();
        assertEquals(GamePingEngine.BREAK, withheld.getNow(null).reason());
        assertEquals(0, waiter.pending());
    }

    @Test
    void notAfterEndsTheWaitAndSkipsAStuckIntent() throws Exception {
        Game game = game();
        trackerPosition(game.anna().trackerId(), REAL_LAT, REAL_LON, T0.minusSeconds(120));
        // The wait never outlasts the safety deadline; the ping then goes out with the last known position.
        var capped = waiting(game, game.anna(), 30, T0.plusSeconds(10));
        scheduler.runPending();
        scheduler.advance(Duration.ofSeconds(10));
        assertEquals(GamePingEngine.SENT, capped.getNow(null).status());
        // An engine that reaches the transaction only after the deadline writes nothing.
        var stuck = engine.execute(game.session(), intent(game.anna(), List.of("hunters"), null, null,
                T0.plusSeconds(15)), GamePingEngine.Context.NONE, GamePingEngine.Hooks.NONE);
        clock.set(T0.plusSeconds(16));
        long positions = number("SELECT COUNT(*) FROM tc_positions");
        scheduler.runPending();
        assertEquals(GamePingEngine.MISSED, stuck.getNow(null).reason());
        assertEquals(positions, number("SELECT COUNT(*) FROM tc_positions"));
    }

    @Test
    void anUnwantedIntentStopsWaiting() throws Exception {
        Game game = game();
        boolean[] wanted = {true};
        var pending = engine.execute(game.session(), new GamePingEngine.Intent(GamePingEngine.TIMETABLE,
                game.anna().id(), null, List.of("hunters"), null, new GamePingEngine.Freshness(
                        T0.minusSeconds(20).toString(), 30), null, null, null), GamePingEngine.Context.NONE,
                new GamePingEngine.Hooks() {
                    @Override
                    public boolean proceed(GameTransaction transaction) {
                        return wanted[0];
                    }

                    @Override
                    public boolean wanted() {
                        return wanted[0];
                    }
                });
        scheduler.runPending();
        scheduler.advance(Duration.ofSeconds(1));
        wanted[0] = false;
        scheduler.advance(Duration.ofMillis(GamePingEngine.POLL_MILLIS));
        assertTrue(pending.isDone());
        assertNull(pending.getNow(null));
        assertEquals(0, waiter.pending());
        assertEquals(0, scheduler.pending());
    }

    @Test
    void invalidIntentsAreRefusedBeforeAnyTransaction() {
        for (var intent : List.of(
                new GamePingEngine.Intent("nowhere", 1L, null, List.of("hunters"), null, null, null, null, null),
                new GamePingEngine.Intent(GamePingEngine.MANUAL, null, null, List.of("hunters"), null, null, null,
                        null, null),
                new GamePingEngine.Intent(GamePingEngine.RELAY, 1L, 2L, null, null, null, null, null, null),
                new GamePingEngine.Intent(GamePingEngine.MANUAL, 1L, null, List.of("hunters", "hunters"), null, null,
                        null, null, null),
                new GamePingEngine.Intent(GamePingEngine.MANUAL, 1L, null, List.of("hunters"),
                        new GamePingEngine.Fake(47.0, 8.0, List.of("hunters")), null, null, null, null),
                new GamePingEngine.Intent(GamePingEngine.MANUAL, 1L, null, List.of("hunters"),
                        new GamePingEngine.Fake(95.0, 8.0, List.of("players")), null, null, null, null),
                new GamePingEngine.Intent(GamePingEngine.MANUAL, 1L, null, List.of("everyone"), null, null, null,
                        null, null),
                new GamePingEngine.Intent(GamePingEngine.TIMETABLE, 1L, null, List.of("hunters"), null,
                        new GamePingEngine.Freshness(T0.toString(), 61), null, null, null))) {
            assertStatus(400, () -> GamePingEngine.validate(intent, true));
        }
    }

}
