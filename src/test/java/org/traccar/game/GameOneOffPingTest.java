package org.traccar.game;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 of the game execution plan: every one-off ping is an intent of an effects delivery, decided and written by
 * the server's ping engine. Covers each action the Tool sends (a manual ping, a speedhunt static ping, an operator
 * fake, a request's ping with its completion, a one-off hunter relay with its joker), the server record a one-off
 * relay leaves, and the lookup that reconciles an uncertain delivery. The cases of the Tool's removed
 * {@code OutgoingPositionPolicy}, {@code ParticipantPingSender}, {@code PositionUploadService},
 * {@code ManualUploadService} and {@code HunterRelayService} tests are ported here. Runs on H2
 * ({@link GameOneOffPingH2Test}) and MySQL ({@link GameOneOffPingMySqlTest}).
 */
public abstract class GameOneOffPingTest extends GameTestBase {

    private static final long EPOCH = 31;
    private static final List<String> ALL = List.of("hunters", "players", "supporters");

    private GameEffectsService effects;
    private GameRequestService requests;
    private GameJokerService jokers;
    private GameStateFixture state;
    private GameSnapshotService snapshots;

    @Override
    protected Set<GameBindingHook> hooks() {
        return Set.of(new GamePlanInvalidation(publisher), new GameAccessRule(transactions));
    }

    @Override
    protected void createServices() {
        effects = effectsService();
        requests = new GameRequestService(transactions, mapper, publisher);
        jokers = new GameJokerService(transactions, publisher);
        state = new GameStateFixture(transactions, mapper, publisher);
        snapshots = new GameSnapshotService(transactions, mapper);
    }

    @AfterEach
    void reset() throws Exception {
        execute("UPDATE tc_game_session_state SET state = 'stopped', runningkey = NULL WHERE runningkey = 1");
        clock.set(T0);
        publisher.clear();
    }

    // Fixture -----------------------------------------------------------------------------------------------

    /** Anna and Ben (players), Sue (supporter) and two hunters; mirrors to every role and per-hunter mirrors. */
    private record Game(long session, GameMember anna, GameMember ben, GameMember sue, GameMember hunter,
                        GameMember hunter2) {
    }

    private Game game() throws Exception {
        long session = session("{\"pings_per_speedhunt\": 3, \"break_duration_minutes\": 10}");
        lease(session, EPOCH);
        GameMember anna = bound(session, GameMember.PLAYER);
        GameMember ben = bound(session, GameMember.PLAYER);
        GameMember sue = bound(session, GameMember.SUPPORTER);
        // A second supporter, so the supporters audience of Sue has a recipient too.
        bound(session, GameMember.SUPPORTER);
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

    private static String id() {
        return "delivery-" + UUID.randomUUID();
    }

    private static String role(String audience) {
        return switch (audience) {
            case "hunters" -> GameMember.HUNTER;
            case "players" -> GameMember.PLAYER;
            default -> GameMember.SUPPORTER;
        };
    }

    /** The mirror device that shows {@code source} to an audience. */
    private long mirror(Game game, GameMember source, String audience) throws Exception {
        return transactions.read(transaction -> GameResourceRepository.mirrorByRoute(transaction, game.session(),
                source.id(), role(audience), null).orElseThrow().deviceId());
    }

    /** The mirror device that shows a hunter to one recipient. */
    private long relayMirror(Game game, GameMember hunter, GameMember recipient) throws Exception {
        return transactions.read(transaction -> GameResourceRepository.mirrorByRoute(transaction, game.session(),
                hunter.id(), null, recipient.id()).orElseThrow().deviceId());
    }

    private long positions(long device) throws Exception {
        return number("SELECT COUNT(*) FROM tc_positions WHERE deviceid = ?", device);
    }

    private long mirrorPositions(Game game) throws Exception {
        return number("SELECT COUNT(*) FROM tc_positions p JOIN tc_game_mirrors m ON m.deviceid = p.deviceid"
                + " WHERE m.gamesessionid = ?", game.session());
    }

    /** The fix time of a device's latest position. */
    private Instant fixTime(long device) throws Exception {
        return transactions.read(transaction -> GamePositions.latest(transaction, device).orElseThrow().fixTime());
    }

    private long reservationOf(Game game, long request) throws Exception {
        return transactions.read(transaction -> GameJokerRepository.ofRequest(transaction, game.session(), request)
                .getFirst().id());
    }

    private GameEffectsService.Outcome deliver(Game game, GamePingEngine.Intent... intents) throws Exception {
        return deliver(game, id(), intents);
    }

    private GameEffectsService.Outcome deliver(Game game, String id, GamePingEngine.Intent... intents)
            throws Exception {
        return effects.apply(game.session(), EPOCH, new GameEffectsService.Delivery(id, List.of(), List.of(intents),
                null, null, null, null, null));
    }

    private static GamePingEngine.Intent ping(String path, GameMember source, List<String> audiences,
                                              GamePingEngine.Fake fake, GamePingEngine.Effects delivered,
                                              GamePingEngine.Effects notDelivered) {
        return new GamePingEngine.Intent(path, source.id(), null, audiences, fake, null, null, delivered,
                notDelivered);
    }

    private static GamePingEngine.Intent manual(GameMember source, List<String> audiences) {
        return ping(GamePingEngine.MANUAL, source, audiences, null, null, null);
    }

    private static GamePingEngine.Intent relay(GameMember recipient, GamePingEngine.Effects delivered,
                                               GamePingEngine.Effects notDelivered) {
        return new GamePingEngine.Intent(GamePingEngine.RELAY, null, recipient.id(), null, null, null, null,
                delivered, notDelivered);
    }

    private static GamePingEngine.Effects operations(GameOperation... operations) {
        return new GamePingEngine.Effects(List.of(operations), null, null);
    }

    private static GameOperation.PingDelivered delivered(GameMember target, int number, String source,
                                                         List<String> audiences, boolean overAllocate) {
        return new GameOperation.PingDelivered(target.id(), number, GameState.STATIC, source,
                audiences.contains("hunters"), audiences.contains("players"), audiences.contains("supporters"),
                null, null, "Operator", overAllocate);
    }

    /** The intent of a speedhunt static ping, as the Tool sends it. */
    private static GamePingEngine.Intent staticPing(GameMember target, int number, List<String> audiences,
                                                    boolean overAllocate) {
        return ping(GamePingEngine.SPEEDHUNT, target, audiences, null,
                operations(delivered(target, number, GameState.REAL, audiences, overAllocate)),
                operations(new GameOperation.PingFailed(target.id(), "No eligible target recipient")));
    }

    private void grantPool(Game game, int quantity) throws Exception {
        jokers.change(game.session(), new GameJokerService.Change(null, GameJokerRepository.SPEEDHUNT, quantity,
                null), "Operator");
    }

    private GameJokerService.Inventory pool(Game game) throws Exception {
        return jokers.pool(game.session()).inventories().getFirst();
    }

    private GameJokerService.Inventory inventory(GameMember member, String type) throws Exception {
        return jokers.own(member).inventories().stream().filter(item -> item.type().equals(type)).findFirst()
                .orElseThrow();
    }

    private GameJokerService.Use relayJoker(GameMember member, boolean overAllocate) {
        return new GameJokerService.Use(member.id(), GameJokerRepository.HUNTER_POSITIONS, overAllocate,
                "Manual hunter relay");
    }

    private List<GameState.Relay> relays(Game game) throws Exception {
        return snapshots.snapshot(game.session()).state().relays();
    }

    private GameRequest request(Game game, long id) throws Exception {
        return transactions.read(transaction -> GameRequestRepository.find(transaction, game.session(), id)
                .orElseThrow());
    }

    // Manual pings ------------------------------------------------------------------------------------------

    @Test
    void aManualPingReachesExactlyItsAudiencesOnEveryParticipantPath() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        trackerPosition(anna.trackerId(), 46.95, 7.44, T0.minusSeconds(5));
        for (String path : List.of(GamePingEngine.MANUAL, GamePingEngine.SPEEDHUNT)) {
            for (List<String> audiences : List.of(List.of("hunters"), List.of("players"), List.of("supporters"),
                    List.of("hunters", "supporters"), ALL)) {
                long[] before = new long[ALL.size()];
                for (int i = 0; i < ALL.size(); i++) {
                    before[i] = positions(mirror(game, anna, ALL.get(i)));
                }
                var outcome = deliver(game, ping(path, anna, audiences, null, null, null)).pings().getFirst();
                assertEquals(GamePingEngine.SENT, outcome.status(), path + " " + audiences);
                assertNull(outcome.reason());
                assertEquals(audiences, outcome.delivered());
                assertEquals(audiences.size(), outcome.positions().size());
                assertEquals(46.95, outcome.position().latitude());
                assertFalse(outcome.position().stale());
                for (int i = 0; i < ALL.size(); i++) {
                    long device = mirror(game, anna, ALL.get(i));
                    assertEquals(before[i] + (audiences.contains(ALL.get(i)) ? 1 : 0), positions(device));
                }
            }
        }
        // Resolution order: hunters, then players, then supporters, whatever the intent's order.
        var ordered = deliver(game, manual(anna, List.of("supporters", "hunters", "players"))).pings().getFirst();
        assertEquals(ALL, ordered.delivered());
        assertEquals(List.of(mirror(game, anna, "hunters"), mirror(game, anna, "players"),
                mirror(game, anna, "supporters")), ordered.positions().stream()
                .map(GamePositions.Written::deviceId).toList());
        assertArrayEquals(new double[] {46.95, 7.44}, latest(mirror(game, anna, "supporters")));
        // Every destination of a ping is written by one transaction and published once, after it.
        assertEquals(Set.copyOf(ordered.positions().stream().map(GamePositions.Written::deviceId).toList()),
                Set.copyOf(publisher.devices.subList(publisher.devices.size() - 3, publisher.devices.size())));
    }

    @Test
    void anOperatorCoordinateAndRealPositionsUseTheManualPathInOneDelivery() throws Exception {
        Game game = game();
        trackerPosition(game.ben().trackerId(), 47.2, 8.2, T0.minusSeconds(8));
        // Anna has no position at all: the operator's coordinate needs none. Sue has none either and is real.
        var outcome = deliver(game,
                ping(GamePingEngine.MANUAL, game.anna(), List.of(), new GamePingEngine.Fake(46.5, 7.5,
                        List.of("hunters", "players")), null, null),
                manual(game.ben(), ALL),
                manual(game.sue(), List.of("hunters", "players")));
        assertEquals(3, outcome.pings().size());
        var anna = outcome.pings().getFirst();
        assertEquals(GamePingEngine.SENT, anna.status());
        assertEquals(List.of("hunters", "players"), anna.delivered());
        assertEquals(List.of("hunters", "players"), anna.fake().audiences());
        assertNull(anna.position());
        assertArrayEquals(new double[] {46.5, 7.5}, latest(mirror(game, game.anna(), "hunters")));
        assertArrayEquals(new double[] {46.5, 7.5}, latest(mirror(game, game.anna(), "players")));
        assertNull(latest(mirror(game, game.anna(), "supporters")));
        var ben = outcome.pings().get(1);
        assertEquals(ALL, ben.delivered());
        assertArrayEquals(new double[] {47.2, 8.2}, latest(mirror(game, game.ben(), "supporters")));
        assertEquals(T0.minusSeconds(8).toString(), ben.position().fixTime());
        // A missing position is reported for that participant only; nothing is written for them.
        var sue = outcome.pings().get(2);
        assertEquals(GamePingEngine.FAILED, sue.status());
        assertEquals(GamePingEngine.NO_POSITION, sue.reason());
        assertTrue(sue.delivered().isEmpty());
        assertNull(latest(mirror(game, game.sue(), "hunters")));
        // A coordinate for some audiences and the real position for the others.
        var split = deliver(game, ping(GamePingEngine.MANUAL, game.ben(), List.of("players", "supporters"),
                new GamePingEngine.Fake(46.0, 7.0, List.of("hunters")), null, null)).pings().getFirst();
        assertEquals(ALL, split.delivered());
        assertArrayEquals(new double[] {46.0, 7.0}, latest(mirror(game, game.ben(), "hunters")));
        assertArrayEquals(new double[] {47.2, 8.2}, latest(mirror(game, game.ben(), "players")));
        // An intent without any audience is refused before anything happens.
        assertStatus(400, () -> deliver(game, manual(game.ben(), List.of())));
    }

    @Test
    void breaksCatchesAndMissingMirrorsAreDecidedByTheServerForEveryPing() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        GameMember ben = game.ben();
        GameMember sue = game.sue();
        for (GameMember member : List.of(anna, ben, sue)) {
            trackerPosition(member.trackerId(), 47.0, 8.0, T0.minusSeconds(5));
        }
        // A break withholds the hunters on every participant path, like a timetable b cell.
        onBreak(game.session(), anna, T0.plusSeconds(600));
        for (String path : List.of(GamePingEngine.MANUAL, GamePingEngine.SPEEDHUNT)) {
            var partly = deliver(game, ping(path, anna, List.of("hunters", "players"), null, null, null)).pings()
                    .getFirst();
            assertEquals(GamePingEngine.SKIPPED, partly.status());
            assertEquals(GamePingEngine.BREAK, partly.reason());
            assertEquals(List.of("players"), partly.delivered());
            assertEquals(List.of("hunters"), partly.withheld());
            var nothing = deliver(game, ping(path, anna, List.of("hunters"), null, null, operations(
                    new GameOperation.PingFailed(anna.id(), "break")))).pings().getFirst();
            assertEquals(GamePingEngine.SKIPPED, nothing.status());
            assertEquals(GamePingEngine.BREAK, nothing.reason());
            assertTrue(nothing.delivered().isEmpty());
            assertEquals("pingFailed", nothing.operations().getFirst().operation());
        }
        assertNull(latest(mirror(game, anna, "hunters")));
        assertEquals(2, positions(mirror(game, anna, "players")));
        // A speedhunt ping never counts on a participant on a break: the delivery is refused as a whole.
        long before = mirrorPositions(game);
        assertStatus(409, () -> deliver(game, staticPing(anna, 1, List.of("hunters", "players"), true)));
        assertEquals(before, mirrorPositions(game));
        assertTrue(state.runs(game.session()).isEmpty());

        // A caught participant publishes nothing on any path and receives no hunter relay.
        trackerPosition(game.hunter().trackerId(), 47.5, 8.5, T0.minusSeconds(5));
        caught(game.session(), ben);
        for (String path : List.of(GamePingEngine.MANUAL, GamePingEngine.SPEEDHUNT)) {
            var outcome = deliver(game, ping(path, ben, ALL, null, null, null)).pings().getFirst();
            assertEquals(GamePingEngine.SKIPPED, outcome.status());
            assertEquals(GamePingEngine.CAUGHT, outcome.reason());
            assertTrue(outcome.delivered().isEmpty() && outcome.positions().isEmpty());
        }
        var relayed = deliver(game, relay(ben, null, null)).pings().getFirst();
        assertEquals(GamePingEngine.SKIPPED, relayed.status());
        assertEquals(GamePingEngine.CAUGHT, relayed.reason());
        assertNull(latest(relayMirror(game, game.hunter(), ben)));
        assertEquals(before, mirrorPositions(game));

        // A missing mirror fails the whole ping before any of its destinations is written.
        var mirror = resources.mirrors(game.session()).stream()
                .filter(item -> item.sourceMemberId() == sue.id()
                        && GameMember.PLAYER.equals(item.destinationRole())).findFirst().orElseThrow();
        resources.retire(GameResource.Kind.MIRROR, game.session(), mirror.id());
        var failed = deliver(game, manual(sue, List.of("hunters", "players"))).pings().getFirst();
        assertEquals(GamePingEngine.FAILED, failed.status());
        assertEquals(GamePingEngine.NO_MIRROR, failed.reason());
        assertTrue(failed.positions().isEmpty());
        assertNull(latest(mirror(game, sue, "hunters")));
        assertEquals(GamePingEngine.SENT, deliver(game, manual(sue, List.of("hunters"))).pings().getFirst()
                .status());
    }

    // Speedhunt pings ---------------------------------------------------------------------------------------

    @Test
    void aSpeedhuntStaticPingRecordsItsPingWithItsPositions() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        grantPool(game, 1);
        trackerPosition(anna.trackerId(), 46.95, 7.44, T0.minusSeconds(5));
        long revision = memberRevision(anna.id());
        publisher.clear();
        // Ping 1 starts the speedhunt with a pool joker, in the transaction that writes its positions.
        String first = id();
        var outcome = deliver(game, first, staticPing(anna, 1, List.of("hunters", "players"), false));
        var ping = outcome.pings().getFirst();
        assertEquals(GamePingEngine.SENT, ping.status());
        assertEquals(List.of("hunters", "players"), ping.delivered());
        assertEquals("pingDelivered", ping.operations().getFirst().operation());
        var run = state.runs(game.session()).getFirst();
        assertEquals(run.run().id(), ping.operations().getFirst().runId());
        assertEquals(GameState.REAL, ping.operations().getFirst().source());
        assertEquals(1, run.pings().size());
        assertEquals(GameState.STATIC, run.pings().getFirst().mode());
        assertEquals(1, pool(game).used());
        assertEquals(revision + 1, memberRevision(anna.id()));
        assertArrayEquals(new double[] {46.95, 7.44}, latest(mirror(game, anna, "hunters")));
        assertArrayEquals(new double[] {46.95, 7.44}, latest(mirror(game, anna, "players")));
        assertNull(latest(mirror(game, anna, "supporters")));
        assertEquals(1, publisher.topic(GamePublisher.SPEEDHUNT_HISTORY).size());
        // A lost answer is replayed with the destinations the delivery confirmed then; nothing counts twice.
        long written = mirrorPositions(game);
        var replay = deliver(game, first, staticPing(anna, 1, List.of("hunters", "players"), false));
        assertTrue(replay.replayed());
        assertEquals(outcome.pings(), replay.pings());
        assertEquals(written, mirrorPositions(game));
        assertEquals(1, state.runs(game.session()).getFirst().pings().size());

        // The next ping must be the next number; a refused one writes nothing.
        assertStatus(409, () -> deliver(game, staticPing(anna, 5, List.of("hunters"), false)));
        assertEquals(written, mirrorPositions(game));
        assertEquals(2, deliver(game, staticPing(anna, 2, List.of("hunters"), false)).pings().getFirst()
                .operations().getFirst().number());

        // A ping that reaches nobody applies pingFailed instead; no speedhunt starts and no joker is used.
        var failed = deliver(game, staticPing(game.sue(), 1, List.of("hunters"), false)).pings().getFirst();
        assertEquals(GamePingEngine.FAILED, failed.status());
        assertEquals(GamePingEngine.NO_POSITION, failed.reason());
        assertEquals("pingFailed", failed.operations().getFirst().operation());
        assertEquals(1, state.runs(game.session()).size());
        assertEquals(1, pool(game).used());
        // A new speedhunt needs a pool joker: held, available, or confirmed beyond the allocation.
        trackerPosition(game.sue().trackerId(), 47.3, 8.3, T0.minusSeconds(5));
        assertStatus(409, () -> deliver(game, staticPing(game.sue(), 1, List.of("hunters"), false)));
        assertNull(latest(mirror(game, game.sue(), "hunters")));
        deliver(game, staticPing(game.sue(), 1, List.of("hunters"), true));
        assertEquals(2, pool(game).used());
        assertEquals(List.of(), reconcile(game.session()));
    }

    /** Arms a speedhunt fake of {@code owner} on ping {@code number} of the next speedhunt. */
    private long armSpeedhuntFake(Game game, GameMember owner, int number, double latitude, double longitude)
            throws Exception {
        jokers.change(game.session(), new GameJokerService.Change(owner.id(), GameJokerRepository.FAKE_PING, 1,
                null), "Operator");
        var request = requests.submit(owner, new GameRequestService.Submission(GameRequest.FAKE_PING, null, null,
                json("{\"pingNumber\": " + number + ", \"latitude\": " + latitude + ", \"longitude\": " + longitude
                        + "}")));
        requests.claim(game.session(), request.id(), EPOCH);
        effects.apply(game.session(), EPOCH, new GameEffectsService.Delivery(id(), List.of(), null, null, null,
                null, List.of(new GameRequestService.Completion(request.id(), GameRequest.SUCCEEDED, null, null)),
                null));
        return reservationOf(game, request.id());
    }

    @Test
    void theTargetsOwnSpeedhuntFakeReachesOnlyTheHuntersOfAStaticPing() throws Exception {
        Game game = game();
        GameMember ben = game.ben();
        grantPool(game, 1);
        long reservation = armSpeedhuntFake(game, ben, 1, 46.5, 7.0);
        trackerPosition(ben.trackerId(), 47.1, 8.5, T0.minusSeconds(5));
        var outcome = deliver(game, staticPing(ben, 1, List.of("hunters", "players"), false));
        var ping = outcome.pings().getFirst();
        // Hunters get the planned coordinate, the players the real one; one payload, one fix time.
        assertEquals(GameState.JOKER_FAKE, ping.operations().getFirst().source());
        assertArrayEquals(new double[] {46.5, 7.0}, latest(mirror(game, ben, "hunters")));
        assertArrayEquals(new double[] {47.1, 8.5}, latest(mirror(game, ben, "players")));
        assertEquals(fixTime(mirror(game, ben, "hunters")), fixTime(mirror(game, ben, "players")));
        assertEquals(GameJokerRepository.CONSUMED, reservationStatus(reservation));
        assertTrue(outcome.fakes().stream().anyMatch(item -> item.reservationId() == reservation));
    }

    @Test
    void anOperatorFakeSendsItsCoordinateToTheRunAudiences() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        grantPool(game, 1);
        long reservation = armSpeedhuntFake(game, anna, 1, 46.5, 7.0);
        // No real position is needed: every audience of the run receives the operator's coordinate.
        var fake = new GamePingEngine.Fake(46.2, 7.2, ALL);
        var outcome = deliver(game, ping(GamePingEngine.SPEEDHUNT, anna, List.of(), fake,
                operations(delivered(anna, 1, GameState.OPERATOR_FAKE, ALL, false)),
                operations(new GameOperation.PingFailed(anna.id(), "Fake Ping failed")))).pings().getFirst();
        assertEquals(GamePingEngine.SENT, outcome.status());
        assertEquals(ALL, outcome.delivered());
        assertEquals(ALL, outcome.fake().audiences());
        assertNull(outcome.position());
        for (String audience : ALL) {
            assertArrayEquals(new double[] {46.2, 7.2}, latest(mirror(game, anna, audience)));
        }
        var ping = state.runs(game.session()).getFirst().pings().getFirst();
        assertEquals(GameState.OPERATOR_FAKE, ping.source());
        assertEquals(1, pool(game).used());
        // The target's own joker fake on that ping is used all the same, but the operator's coordinate wins.
        assertEquals(GameJokerRepository.CONSUMED, reservationStatus(reservation));
        // A caught target receives no fake either: pingFailed applies and nothing is written.
        GameMember ben = game.ben();
        caught(game.session(), ben);
        var caught = deliver(game, ping(GamePingEngine.SPEEDHUNT, ben, List.of(), fake,
                operations(delivered(ben, 1, GameState.OPERATOR_FAKE, ALL, true)),
                operations(new GameOperation.PingFailed(ben.id(), "Fake Ping failed")))).pings().getFirst();
        assertEquals(GamePingEngine.CAUGHT, caught.reason());
        assertEquals("pingFailed", caught.operations().getFirst().operation());
        assertNull(latest(mirror(game, ben, "hunters")));
        assertEquals(1, state.runs(game.session()).size());
    }

    // Requests ----------------------------------------------------------------------------------------------

    /** A hunter's static-ping request on {@code target}, claimed by the Tool. */
    private GameRequest claimedPing(Game game, GameMember target) throws Exception {
        var request = requests.submit(game.hunter(), new GameRequestService.Submission(GameRequest.STATIC_PING,
                target.id(), memberRevision(target.id()), null));
        return requests.claim(game.session(), request.id(), EPOCH);
    }

    private static GamePingEngine.Intent requestedPing(GameMember target, long request) {
        return ping(GamePingEngine.SPEEDHUNT, target, List.of("hunters", "players"), null,
                new GamePingEngine.Effects(List.of(delivered(target, 1, GameState.REAL,
                        List.of("hunters", "players"), false)),
                        List.of(new GameRequestService.Completion(request, GameRequest.SUCCEEDED,
                                "Ping 1 of 3 sent.", null)), null),
                new GamePingEngine.Effects(List.of(new GameOperation.PingFailed(target.id(),
                        "No eligible target recipient")),
                        List.of(new GameRequestService.Completion(request, GameRequest.FAILED,
                                "No one could receive this ping.", null)), null));
    }

    @Test
    void aRequestsPingCommitsItsCompletionWithThePing() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        grantPool(game, 2);
        trackerPosition(anna.trackerId(), 46.95, 7.44, T0.minusSeconds(5));
        GameRequest claimed = claimedPing(game, anna);
        assertEquals(1, pool(game).reserved());

        // One transaction: a delivery that fails as a whole leaves the request executing and writes nothing.
        assertStatus(400, () -> deliver(game, requestedPing(anna, claimed.id()), new GamePingEngine.Intent(
                GamePingEngine.MANUAL, -5L, null, List.of("hunters"), null, null, null, null, null)));
        assertEquals(GameRequest.EXECUTING, request(game, claimed.id()).status());
        assertEquals(0, mirrorPositions(game));
        assertTrue(state.runs(game.session()).isEmpty());

        publisher.clear();
        String id = id();
        var outcome = deliver(game, id, requestedPing(anna, claimed.id()));
        var ping = outcome.pings().getFirst();
        assertEquals(GamePingEngine.SENT, ping.status());
        assertEquals(List.of(new GameEffectsService.RequestOutcome(claimed.id(), GameRequest.SUCCEEDED)),
                ping.requests());
        GameRequest done = request(game, claimed.id());
        assertEquals(GameRequest.SUCCEEDED, done.status());
        assertEquals("Ping 1 of 3 sent.", done.message());
        // The pool joker the request held is used by the speedhunt its ping started.
        var run = state.runs(game.session()).getFirst();
        assertEquals(claimed.id(), run.run().requestId());
        assertEquals(1, pool(game).used());
        assertEquals(0, pool(game).reserved());
        assertArrayEquals(new double[] {46.95, 7.44}, latest(mirror(game, anna, "hunters")));
        assertEquals(1, publisher.topic(GamePublisher.REQUESTS).size());
        // The recorded delivery answers a repeated attempt; the request is not completed twice.
        var replay = deliver(game, id, requestedPing(anna, claimed.id()));
        assertTrue(replay.replayed());
        assertEquals(outcome.pings(), replay.pings());
        assertEquals(1, state.runs(game.session()).getFirst().pings().size());

        // A ping that reaches nobody fails its request in the same transaction and returns the hunters' joker.
        GameRequest second = claimedPing(game, game.sue());
        assertEquals(1, pool(game).reserved());
        var failed = deliver(game, requestedPing(game.sue(), second.id())).pings().getFirst();
        assertEquals(GamePingEngine.FAILED, failed.status());
        assertEquals(GamePingEngine.NO_POSITION, failed.reason());
        assertEquals(List.of(new GameEffectsService.RequestOutcome(second.id(), GameRequest.FAILED)),
                failed.requests());
        assertEquals("No one could receive this ping.", request(game, second.id()).message());
        assertEquals(1, state.runs(game.session()).size());
        assertEquals(1, pool(game).used());
        assertEquals(0, pool(game).reserved());
        assertEquals(1, pool(game).available());
        assertEquals(List.of(), reconcile(game.session()));
    }

    // One-off hunter relays ---------------------------------------------------------------------------------

    @Test
    void aOneOffRelayDeliversEveryHunterWithItsFixTimeAndUsesItsJokerOnce() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        jokers.change(game.session(), new GameJokerService.Change(anna.id(), GameJokerRepository.HUNTER_POSITIONS,
                1, null), "Operator");
        // One hunter has a position, the other none: the relay delivers what it finds.
        trackerPosition(game.hunter().trackerId(), 47.0, 8.0, T0.minusSeconds(40));
        long revision = memberRevision(anna.id());
        publisher.clear();
        var joker = new GamePingEngine.Effects(null, null, List.of(relayJoker(anna, false)));
        var outcome = deliver(game, relay(anna, joker, null)).pings().getFirst();
        assertEquals(GamePingEngine.SENT, outcome.status());
        assertEquals(List.of("hunters"), outcome.delivered());
        assertEquals(List.of(game.hunter().id()), outcome.hunters());
        assertEquals("1 hunter(s) without a position", outcome.detail());
        // A relayed position keeps the hunter's own fix time, so its age stays visible.
        long mirror = relayMirror(game, game.hunter(), anna);
        assertArrayEquals(new double[] {47.0, 8.0}, latest(mirror));
        assertEquals(T0.minusSeconds(40), fixTime(mirror));
        assertEquals(T0.minusSeconds(40).toString(), outcome.positions().getFirst().fixTime());
        assertNull(latest(relayMirror(game, game.hunter2(), anna)));
        assertNull(latest(relayMirror(game, game.hunter(), game.ben())));
        // The joker is used in the same transaction; what the server recorded is in the outcome.
        assertFalse(outcome.jokers().getFirst().overAllocated());
        assertEquals(1, inventory(anna, GameJokerRepository.HUNTER_POSITIONS).used());
        assertEquals(Set.of(anna.id()), publisher.topic(GamePublisher.JOKERS).getFirst().members());
        // Private to the recipient: hunters observe nothing.
        assertEquals(revision, memberRevision(anna.id()));
        // The relay leaves its record, so the last delivery is on the server without any live relay row.
        var record = relays(game).getFirst();
        assertEquals(anna.id(), record.recipientMemberId());
        assertEquals(1, record.hunters());
        assertEquals(T0, record.deliveredAt());
        assertEquals(outcome.jokers().getFirst().reservationId(), record.reservationId());
        assertNull(record.requestId());

        // Without a joker left the relay is refused before anything is written; the operator confirms.
        clock.set(T0.plusSeconds(20));
        long written = mirrorPositions(game);
        assertStatus(409, () -> deliver(game, relay(anna, joker, null)));
        assertEquals(written, mirrorPositions(game));
        assertEquals(1, relays(game).size());
        // An older fix is stored but never becomes the mirror's latest position.
        trackerPosition(game.hunter().trackerId(), 47.4, 8.4, T0.minusSeconds(90));
        trackerPosition(game.hunter2().trackerId(), 47.6, 8.6, T0.plusSeconds(15));
        var over = deliver(game, relay(anna, new GamePingEngine.Effects(null, null, List.of(relayJoker(anna,
                true))), null)).pings().getFirst();
        assertTrue(over.jokers().getFirst().overAllocated());
        assertEquals(List.of(game.hunter().id(), game.hunter2().id()), over.hunters());
        assertFalse(over.positions().getFirst().latest());
        assertArrayEquals(new double[] {47.0, 8.0}, latest(mirror));
        assertArrayEquals(new double[] {47.6, 8.6}, latest(relayMirror(game, game.hunter2(), anna)));
        assertEquals(2, relays(game).size());
        assertEquals(2, relays(game).getLast().hunters());
        assertEquals(T0.plusSeconds(20), relays(game).getLast().deliveredAt());

        // A relay without a joker use delivers every snapshot and is recorded without a reservation; a break of
        // the recipient does not affect relays to them.
        onBreak(game.session(), game.ben(), T0.plusSeconds(600));
        var free = deliver(game, relay(game.ben(), null, null)).pings().getFirst();
        assertEquals(GamePingEngine.SENT, free.status());
        assertNull(free.jokers());
        assertEquals(2, free.hunters().size());
        var third = relays(game).getLast();
        assertEquals(game.ben().id(), third.recipientMemberId());
        assertNull(third.reservationId());
        assertEquals(List.of(), reconcile(game.session()));
    }

    @Test
    void aRelayThatFindsNoHunterPositionUsesAndRecordsNothing() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        jokers.change(game.session(), new GameJokerService.Change(anna.id(), GameJokerRepository.HUNTER_POSITIONS,
                1, null), "Operator");
        var joker = new GamePingEngine.Effects(null, null, List.of(relayJoker(anna, false)));
        var outcome = deliver(game, relay(anna, joker, null)).pings().getFirst();
        assertEquals(GamePingEngine.FAILED, outcome.status());
        assertEquals(GamePingEngine.NO_POSITION, outcome.reason());
        assertTrue(outcome.hunters().isEmpty() && outcome.positions().isEmpty());
        assertNull(outcome.jokers());
        assertEquals(0, inventory(anna, GameJokerRepository.HUNTER_POSITIONS).used());
        assertTrue(relays(game).isEmpty());
        // A catch between the operator's click and the delivery prevents it: nothing is sent or used.
        trackerPosition(game.hunter().trackerId(), 47.0, 8.0, T0.minusSeconds(5));
        caught(game.session(), anna);
        var caught = deliver(game, relay(anna, joker, null)).pings().getFirst();
        assertEquals(GamePingEngine.SKIPPED, caught.status());
        assertEquals(GamePingEngine.CAUGHT, caught.reason());
        assertEquals(0, mirrorPositions(game));
        assertEquals(0, inventory(anna, GameJokerRepository.HUNTER_POSITIONS).used());
        assertTrue(relays(game).isEmpty());
        // A relay has a recipient and nothing else.
        assertStatus(400, () -> deliver(game, new GamePingEngine.Intent(GamePingEngine.RELAY, null,
                game.ben().id(), List.of("hunters"), null, null, null, null, null)));
        assertStatus(400, () -> deliver(game, new GamePingEngine.Intent(GamePingEngine.RELAY, game.ben().id(),
                null, null, null, null, null, null, null)));
        assertStatus(400, () -> deliver(game, relay(game.hunter(), null, null)));
    }

    private static GamePingEngine.Intent requestedRelay(GameMember recipient, long request) {
        return relay(recipient,
                new GamePingEngine.Effects(null, List.of(new GameRequestService.Completion(request,
                        GameRequest.SUCCEEDED, "The hunters' positions were sent.", null)), null),
                new GamePingEngine.Effects(null, List.of(new GameRequestService.Completion(request,
                        GameRequest.FAILED, "No hunter position could be sent; the joker was not used.", null)),
                        null));
    }

    @Test
    void aHunterPositionsRequestIsFulfilledByARelayInOneTransaction() throws Exception {
        Game game = game();
        GameMember ben = game.ben();
        GameMember sue = game.sue();
        for (GameMember member : List.of(ben, sue)) {
            jokers.change(game.session(), new GameJokerService.Change(member.id(),
                    GameJokerRepository.HUNTER_POSITIONS, 1, null), "Operator");
        }
        // No hunter has a position yet: the request fails with the relay and its joker returns.
        var early = requests.submit(sue, new GameRequestService.Submission(GameRequest.HUNTER_POSITIONS, null, null,
                null));
        requests.claim(game.session(), early.id(), EPOCH);
        assertEquals(1, inventory(sue, GameJokerRepository.HUNTER_POSITIONS).reserved());
        var failed = deliver(game, requestedRelay(sue, early.id())).pings().getFirst();
        assertEquals(GamePingEngine.FAILED, failed.status());
        assertEquals(List.of(new GameEffectsService.RequestOutcome(early.id(), GameRequest.FAILED)),
                failed.requests());
        assertEquals("No hunter position could be sent; the joker was not used.",
                request(game, early.id()).message());
        assertEquals(0, inventory(sue, GameJokerRepository.HUNTER_POSITIONS).used());
        assertEquals(1, inventory(sue, GameJokerRepository.HUNTER_POSITIONS).available());
        assertTrue(relays(game).isEmpty());

        trackerPosition(game.hunter().trackerId(), 47.0, 8.0, T0.minusSeconds(30));
        trackerPosition(game.hunter2().trackerId(), 47.1, 8.1, T0.minusSeconds(10));
        var request = requests.submit(ben, new GameRequestService.Submission(GameRequest.HUNTER_POSITIONS, null,
                null, null));
        requests.claim(game.session(), request.id(), EPOCH);
        publisher.clear();
        var outcome = deliver(game, requestedRelay(ben, request.id())).pings().getFirst();
        assertEquals(GamePingEngine.SENT, outcome.status());
        assertEquals(2, outcome.hunters().size());
        assertEquals(List.of(new GameEffectsService.RequestOutcome(request.id(), GameRequest.SUCCEEDED)),
                outcome.requests());
        assertEquals(GameRequest.SUCCEEDED, request(game, request.id()).status());
        // The request's own joker is used by its success; no second one.
        assertEquals(1, inventory(ben, GameJokerRepository.HUNTER_POSITIONS).used());
        assertEquals(0, inventory(ben, GameJokerRepository.HUNTER_POSITIONS).reserved());
        assertEquals(T0.minusSeconds(30), fixTime(relayMirror(game, game.hunter(), ben)));
        assertEquals(T0.minusSeconds(10), fixTime(relayMirror(game, game.hunter2(), ben)));
        var record = relays(game).getFirst();
        assertEquals(ben.id(), record.recipientMemberId());
        assertEquals(2, record.hunters());
        assertEquals(request.id(), record.requestId());
        assertEquals(reservationOf(game, request.id()),
                record.reservationId());
        assertEquals(1, publisher.topic(GamePublisher.REQUESTS).size());
        assertEquals(List.of(), reconcile(game.session()));
    }

    // Reconciling an uncertain delivery ---------------------------------------------------------------------

    @Test
    void anUncertainDeliveryIsReconciledByLookupUnderAnyEpoch() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        jokers.change(game.session(), new GameJokerService.Change(anna.id(), GameJokerRepository.HUNTER_POSITIONS,
                1, null), "Operator");
        trackerPosition(anna.trackerId(), 46.95, 7.44, T0.minusSeconds(5));
        trackerPosition(game.hunter().trackerId(), 47.0, 8.0, T0.minusSeconds(40));
        String id = id();
        var outcome = deliver(game, id, manual(anna, List.of("hunters")), relay(anna, new GamePingEngine.Effects(
                null, null, List.of(relayJoker(anna, false))), null));
        // The answer was lost and control changed meanwhile: the new epoch looks the delivery up, never resends.
        lease(game.session(), EPOCH + 1);
        long written = mirrorPositions(game);
        var recorded = effects.lookup(game.session(), id);
        assertTrue(recorded.replayed());
        assertEquals(EPOCH, recorded.epoch());
        assertEquals(outcome.pings(), recorded.pings());
        assertEquals(List.of("hunters"), recorded.pings().getFirst().delivered());
        assertFalse(recorded.pings().get(1).jokers().getFirst().overAllocated());
        assertEquals(written, mirrorPositions(game));
        assertEquals(1, inventory(anna, GameJokerRepository.HUNTER_POSITIONS).used());
        assertEquals(1, relays(game).size());
        // A delivery the server never recorded is unknown: its action is decided again.
        assertStatus(404, () -> effects.lookup(game.session(), id()));
        assertStatus(404, () -> effects.lookup(session(), id));
    }

}
