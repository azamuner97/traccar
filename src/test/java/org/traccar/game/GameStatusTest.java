package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 4 of game data model 2: the computed member status (role projections), the speedhunt history query and the
 * operator snapshot. Ports model 1's privacy cases: role projections, identical answers for a speedhunt's target
 * and a non-target (also when fakes bind), and no member state revisions or ping sources for players and
 * supporters. Runs on H2 ({@link GameStatusH2Test}) and MySQL ({@link GameStatusMySqlTest}).
 */
public abstract class GameStatusTest extends GameTestBase {

    private static final long EPOCH = 21;
    private static final String SETTINGS = "{\"pings_per_speedhunt\": 3, \"break_duration_minutes\": 10,"
            + " \"fixed_speedhunt_live_duration_minutes\": 2, \"request_approvals\": {\"speedhunt_live_ping\": false}}";
    /** Fields players and supporters must never receive. */
    private static final Set<String> PRIVATE_FIELDS = Set.of("revision", "stateRevision", "source", "boundRunId",
            "targetMemberId", "trackerId", "liveShares", "lastSpeedhuntMemberId", "participants", "limits",
            "outcome", "reason", "fakePlanId", "requestId");

    private GameStatusService status;
    private GameSnapshotService snapshots;
    private GameRequestService requests;
    private GameJokerService jokers;
    private GameStateService state;
    private GameEffectsService effects;
    private int deliveries;

    @Override
    protected Set<GameBindingHook> hooks() {
        return Set.of(new GamePlanInvalidation(publisher), new GameAccessRule(transactions));
    }

    @Override
    protected void createServices() {
        status = new GameStatusService(transactions);
        snapshots = new GameSnapshotService(transactions, mapper);
        requests = new GameRequestService(transactions, mapper, publisher);
        jokers = new GameJokerService(transactions, publisher);
        state = new GameStateService(transactions, mapper, publisher);
        effects = new GameEffectsService(transactions, mapper, publisher);
    }

    @AfterEach
    void resetClock() {
        clock.set(T0);
    }

    // Helpers -----------------------------------------------------------------------------------------------

    /** A running game: two players, two supporters, a hunter and the spectator, all bound. */
    private record Game(long session, GameMember anna, GameMember ben, GameMember carl, GameMember dora,
                        GameMember hunter, GameMember spectator) {
    }

    private Game game() throws Exception {
        long session = session(SETTINGS);
        lease(session, EPOCH);
        return new Game(session, bound(session, "player"), bound(session, "player"), bound(session, "supporter"),
                bound(session, "supporter"), bound(session, "hunter"), bound(session, "spectator"));
    }

    private void grant(long session, GameMember member, String type, int quantity) throws Exception {
        jokers.change(session, new GameJokerService.Change(member == null ? null : member.id(), type, quantity,
                null), "Operator");
    }

    private GameStateService.Applied apply(long session, GameOperation... operations) throws Exception {
        return state.apply(session, EPOCH, List.of(operations));
    }

    private GameEffectsService.Outcome deliver(long session, List<GameRequestService.Completion> completions,
                                               GameOperation... operations) throws Exception {
        return effects.apply(session, EPOCH, new GameEffectsService.Delivery("delivery-" + ++deliveries, null,
                List.of(operations), null, null, null, completions, null));
    }

    private void succeed(long session, long requestId, GameOperation... operations) throws Exception {
        requests.claim(session, requestId, EPOCH);
        deliver(session, List.of(new GameRequestService.Completion(requestId, GameRequest.SUCCEEDED, "Done",
                "detail")), operations);
    }

    /** Plans and arms a fake of {@code member} on a ping of the next (or a running) speedhunt. */
    private GameRequest fake(long session, GameMember member, int pingNumber, double latitude, String runToken)
            throws Exception {
        String token = runToken == null ? "" : ", \"runToken\": \"" + runToken + "\"";
        GameRequest request = requests.submit(member, new GameRequestService.Submission(GameRequest.FAKE_PING, null,
                null, json("{\"pingNumber\": " + pingNumber + ", \"latitude\": " + latitude + ", \"longitude\": 7"
                        + token + "}")));
        succeed(session, request.id());
        return request;
    }

    private static GameOperation.PingDelivered ping(GameMember target, int number) {
        return new GameOperation.PingDelivered(target.id(), number, GameState.STATIC, GameState.REAL, true, true,
                true, null, null, "Operator", true);
    }

    private GameStatus.Status statusOf(GameMember member) throws Exception {
        return status.status(member.userId());
    }

    /** The JSON a member receives, without the caller's own identity. */
    private JsonNode observable(GameStatus.Status value) {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(value);
        node.remove(List.of("memberId", "number", "displayName"));
        return node;
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

    private void assertNoPrivateFields(Object value) {
        Set<String> leaked = new HashSet<>(fieldNames(mapper.valueToTree(value)));
        leaked.retainAll(PRIVATE_FIELDS);
        assertEquals(Set.of(), leaked);
    }

    private long breakFor(long session, GameMember member) throws Exception {
        long planned = apply(session, new GameOperation.BreakPlanned(member.id(), null, true)).results().getFirst()
                .id();
        apply(session, new GameOperation.BreakStarted(planned, null, null));
        return planned;
    }

    // Players and supporters --------------------------------------------------------------------------------

    @Test
    void playersSeeTheRosterAnonymousProgressAndOnlyTheirOwnPlans() throws Exception {
        Game game = game();
        long session = game.session();
        grant(session, null, GameJokerRepository.SPEEDHUNT, 3);
        grant(session, game.anna(), GameJokerRepository.FAKE_PING, 2);
        grant(session, game.ben(), GameJokerRepository.FAKE_PING, 1);
        fake(session, game.anna(), 2, 1.5, null);
        long plannedBreak = apply(session, new GameOperation.BreakPlanned(game.ben().id(),
                T0.plus(Duration.ofHours(2)).toString(), true)).results().getFirst().id();
        apply(session, new GameOperation.Catch(game.dora().id(), "Operator"));
        GameMember planned = plan(session, "player");

        GameStatus.Status anna = statusOf(game.anna());
        assertEquals(session, anna.gameSessionId());
        assertEquals(game.anna().id(), anna.memberId());
        assertEquals("player", anna.role());
        assertTrue(anna.controllerActive());
        assertEquals(T0.toString(), anna.serverTime());
        GameStatus.PlayerView view = (GameStatus.PlayerView) anna.status();
        assertFalse(view.speedhunt().active());
        assertEquals(GameStatus.Break.NONE, view.pause());
        assertNull(view.runningFake());
        // Every active player and supporter with a tracker, players first; a planned member is not in the game yet.
        assertEquals(List.of(game.anna().id(), game.ben().id(), game.carl().id(), game.dora().id()),
                view.participantOverview().stream().map(GameStatus.RosterEntry::memberId).toList());
        assertFalse(view.participantOverview().stream().anyMatch(item -> item.memberId() == planned.id()));
        assertEquals(List.of(false, false, false, true),
                view.participantOverview().stream().map(GameStatus.RosterEntry::caught).toList());
        assertEquals(game.carl().displayName(), view.participantOverview().get(2).name());
        assertEquals(1, view.fakes().size());
        GameStatus.OwnFake own = view.fakes().getFirst();
        assertEquals(GameJokerService.USE_SPEEDHUNT, own.use());
        assertEquals(2, own.pingNumber());
        assertEquals(1.5, own.latitude());
        assertEquals(List.of(), view.plannedBreaks());
        assertNoPrivateFields(anna);

        GameStatus.PlayerView ben = (GameStatus.PlayerView) statusOf(game.ben()).status();
        assertEquals(List.of(), ben.fakes());
        Long reservation = state.breaks(session).stream().filter(item -> item.id() == plannedBreak).findFirst()
                .orElseThrow().reservationId();
        assertEquals(List.of(new GameStatus.PlannedBreak(plannedBreak, reservation,
                T0.plus(Duration.ofHours(2)).toString(), GameState.PLANNED, GameState.BY_OPERATOR)),
                ben.plannedBreaks());
        // Hunters never see a planned break.
        assertFalse(((GameStatus.HunterView) statusOf(game.hunter()).status()).participants().get(1).pause()
                .active());
        assertNoPrivateFields(statusOf(game.ben()));
        assertNoPrivateFields(statusOf(game.carl()));

        // An active break is the owner's; nobody else's projection shows it.
        long breakId = breakFor(session, game.carl());
        GameStatus.PlayerView carl = (GameStatus.PlayerView) statusOf(game.carl()).status();
        assertTrue(carl.pause().active());
        assertEquals(T0.toString(), carl.pause().startedAt());
        assertEquals(T0.plus(Duration.ofMinutes(10)).toString(), carl.pause().endsAt());
        assertEquals(GameStatus.Break.NONE, ((GameStatus.PlayerView) statusOf(game.anna()).status()).pause());
        assertTrue(breakId > 0);
    }

    @Test
    void aTargetAndANonTargetGetIdenticalStatusAlsoWhenTheirFakesBind() throws Exception {
        Game game = game();
        long session = game.session();
        grant(session, null, GameJokerRepository.SPEEDHUNT, 3);
        grant(session, game.anna(), GameJokerRepository.FAKE_PING, 2);
        grant(session, game.ben(), GameJokerRepository.FAKE_PING, 2);
        fake(session, game.anna(), 2, 1.5, null);
        fake(session, game.ben(), 2, 2.5, null);
        publisher.clear();

        // The run on Anna starts: both armed speedhunt fakes bind and are used, whoever the target is; the owners
        // (and the hunters, for their pool) are told in one invalidation.
        apply(session, ping(game.anna(), 1));
        assertEquals(Set.of(game.anna().id(), game.ben().id(), game.hunter().id()),
                publisher.topic(GamePublisher.JOKERS).getLast().members());
        assertNull(publisher.topic(GamePublisher.STATUS).getLast().members());
        GameStatus.Status anna = statusOf(game.anna());
        GameStatus.Status ben = statusOf(game.ben());
        assertEquals(observable(anna), observable(ben));
        assertEquals(anna.hash(), ben.hash());
        GameStatus.PlayerView view = (GameStatus.PlayerView) anna.status();
        assertEquals(new GameStatus.SpeedhuntProgress(true, 1, 3, List.of(new GameStatus.PingTime(1,
                T0.toString()))), view.speedhunt());
        assertEquals(List.of(), view.fakes());
        GameStatus.RunningFake running = view.runningFake();
        assertEquals(2, running.firstPing());
        assertEquals(3, running.lastPing());
        assertTrue(running.runToken().matches("[0-9a-f]{32}"));

        // Both plan a fake on the running speedhunt's last ping; only the target's own fake applies at ping 2...
        fake(session, game.anna(), 3, 3.5, running.runToken());
        fake(session, game.ben(), 3, 4.5, running.runToken());
        GameStatus.OwnFake planned = ((GameStatus.PlayerView) statusOf(game.anna()).status()).fakes().getFirst();
        assertEquals(GameJokerService.USE_RUNNING_SPEEDHUNT, planned.use());
        assertEquals(running.runToken(), planned.runToken());
        // ...Anna's speedhunt fake applies at ping 2, and both running fakes are used at ping 3.
        clock.advance(Duration.ofMinutes(1));
        apply(session, ping(game.anna(), 2));
        publisher.clear();
        apply(session, ping(game.anna(), 3));
        assertEquals(Set.of(game.anna().id(), game.ben().id()),
                publisher.topic(GamePublisher.JOKERS).getLast().members());
        anna = statusOf(game.anna());
        ben = statusOf(game.ben());
        assertEquals(observable(anna), observable(ben));
        assertEquals(anna.hash(), ben.hash());
        assertNoPrivateFields(anna);
        assertNull(((GameStatus.PlayerView) anna.status()).runningFake());

        // The history is anonymous and the same for every member; the operators see the joker fake.
        GameStatus.History history = status.speedhuntHistory(game.anna().userId());
        assertEquals(history, status.speedhuntHistory(game.ben().userId()));
        assertEquals(history, status.speedhuntHistory(game.hunter().userId()));
        assertEquals(List.of(1, 2, 3), history.runs().getFirst().pings().stream()
                .map(GameStatus.HistoryPing::number).toList());
        assertNoPrivateFields(history);
        var pings = snapshots.snapshot(session).state().runs().getFirst().pings();
        assertEquals(List.of(GameState.REAL, GameState.JOKER_FAKE, GameState.JOKER_FAKE),
                pings.stream().map(GameState.Ping::source).toList());
        assertEquals(List.of(), jokers.reconcile(session));
    }

    @Test
    void theHashFollowsOnlyWhatTheMemberObserves() throws Exception {
        Game game = game();
        long session = game.session();
        String anna = statusOf(game.anna()).hash();
        String ben = statusOf(game.ben()).hash();
        String hunter = statusOf(game.hunter()).hash();
        assertEquals(anna, statusOf(game.anna()).hash());
        assertTrue(anna.matches("[0-9a-f]{32}"));
        clock.advance(Duration.ofMinutes(5));
        assertEquals(anna, statusOf(game.anna()).hash());

        // A private change reaches only its owner's projection.
        grant(session, game.anna(), GameJokerRepository.FAKE_PING, 1);
        assertEquals(anna, statusOf(game.anna()).hash());
        fake(session, game.anna(), 1, 1.0, null);
        assertNotEquals(anna, statusOf(game.anna()).hash());
        assertEquals(ben, statusOf(game.ben()).hash());
        assertEquals(hunter, statusOf(game.hunter()).hash());

        // A catch changes the roster everybody sees.
        apply(session, new GameOperation.Catch(game.carl().id(), "Operator"));
        assertNotEquals(ben, statusOf(game.ben()).hash());
        assertNotEquals(hunter, statusOf(game.hunter()).hash());
    }

    // Hunters and spectators --------------------------------------------------------------------------------

    @Test
    void huntersSeeEveryParticipantUnderRosterLabelsWithRevisionsAndLiveShares() throws Exception {
        Game game = game();
        long session = game.session();
        grant(session, null, GameJokerRepository.SPEEDHUNT, 3);
        breakFor(session, game.carl());
        apply(session, ping(game.anna(), 1));
        Instant liveUntil = T0.plus(Duration.ofMinutes(2));
        apply(session, new GameOperation.LiveUploadStarted(GameState.SPEEDHUNT_PING, game.ben().id(), null, true,
                true, true, 10, liveUntil.toString(), null, null, null, null));
        apply(session, new GameOperation.LiveUploadStarted(GameState.MANUAL, game.dora().id(), null, true, false,
                false, 10, T0.plus(Duration.ofMinutes(30)).toString(), "  Left the zone  ", null, null, null));
        // Carl's geofence live tracking reaches hunters, but Carl is on a break: no live share.
        apply(session, new GameOperation.LiveUploadStarted(GameState.GEOFENCE_AUTO, game.carl().id(), null, true,
                false, false, 10, null, null, null, null, null));

        GameStatus.Status hunterStatus = statusOf(game.hunter());
        GameStatus.HunterView hunter = (GameStatus.HunterView) hunterStatus.status();
        assertEquals("hunter", hunterStatus.role());
        assertEquals(List.of("Player 1", "Player 2", "Supporter 1", "Supporter 2"),
                hunter.participants().stream().map(GameStatus.Participant::name).toList());
        GameStatus.Participant anna = hunter.participants().get(0);
        assertEquals(game.anna().id(), anna.memberId());
        assertNull(anna.trackerId());
        assertTrue(anna.speedhunt().running());
        assertFalse(anna.speedhunt().startsRun());
        assertEquals(1, anna.speedhunt().pings());
        assertEquals(memberRevision(game.anna().id()), anna.speedhunt().revision());
        GameStatus.Participant ben = hunter.participants().get(1);
        assertTrue(ben.speedhunt().live());
        assertEquals(liveUntil.toString(), ben.speedhunt().liveUntil());
        assertTrue(ben.speedhunt().startsRun());
        assertTrue(hunter.participants().get(2).pause().active());
        assertEquals(game.anna().id(), hunter.lastSpeedhuntMemberId());
        assertEquals(new GameStatus.Limits(3, 2.0, false, "warn", new GameStatus.RequestApprovals(true, false)),
                hunter.limits());
        assertEquals(List.of(new GameStatus.LiveShare(game.ben().id(), "Player 2", liveUntil.toString(),
                "speedhunt", null), new GameStatus.LiveShare(game.dora().id(), "Supporter 2",
                T0.plus(Duration.ofMinutes(30)).toString(), "manual", "Left the zone")), hunter.liveShares());
        // The current speedhunt is the latest one while it runs.
        assertEquals(new GameStatus.SpeedhuntProgress(true, 1, 3, List.of(new GameStatus.PingTime(1,
                T0.toString()))), hunter.speedhunt());

        GameStatus.SpectatorView spectator = (GameStatus.SpectatorView) statusOf(game.spectator()).status();
        assertEquals(List.of(game.anna().displayName(), game.ben().displayName(), game.carl().displayName(),
                game.dora().displayName()), spectator.participants().stream().map(GameStatus.Participant::name)
                .toList());
        assertEquals(game.anna().trackerId(), spectator.participants().getFirst().trackerId());
        assertEquals(hunter.limits(), spectator.limits());
        assertFalse(fieldNames(mapper.valueToTree(spectator)).contains("liveShares"));

        // Without a controller, progress and live shares are inactive; the roster stays.
        execute("DELETE FROM tc_game_controller WHERE id = 1");
        hunterStatus = statusOf(game.hunter());
        hunter = (GameStatus.HunterView) hunterStatus.status();
        assertFalse(hunterStatus.controllerActive());
        assertEquals(GameStatus.SpeedhuntProgress.INACTIVE, hunter.speedhunt());
        assertEquals(List.of(), hunter.liveShares());
        assertTrue(hunter.participants().getFirst().speedhunt().running());
        GameStatus.PlayerView player = (GameStatus.PlayerView) statusOf(game.ben()).status();
        assertFalse(statusOf(game.ben()).controllerActive());
        assertEquals(GameStatus.SpeedhuntProgress.INACTIVE, player.speedhunt());
        assertNull(player.runningFake());
    }

    @Test
    void anonymousProgressAndHistoryFollowTheExecutingSession() throws Exception {
        Game game = game();
        long session = game.session();
        long other = session();
        grant(session, null, GameJokerRepository.SPEEDHUNT, 3);
        assertEquals(GameStatus.SpeedhuntProgress.INACTIVE, status.speedhunt(session));
        apply(session, ping(game.anna(), 1));
        clock.advance(Duration.ofSeconds(30));
        apply(session, ping(game.anna(), 2));
        GameStatus.SpeedhuntProgress progress = new GameStatus.SpeedhuntProgress(true, 2, 3, null);
        assertEquals(progress, status.speedhunt(session));
        assertEquals(progress, status.speedhunt(null));
        assertEquals(GameStatus.SpeedhuntProgress.INACTIVE, status.speedhunt(other));

        apply(session, new GameOperation.RunEnded(game.anna().id(), GameState.TERMINATED, "Operator ended it"));
        clock.advance(Duration.ofMinutes(1));
        apply(session, ping(game.ben(), 1));
        GameStatus.History history = status.speedhuntHistory(game.spectator().userId());
        assertEquals(session, history.gameSessionId());
        assertEquals(List.of("inProgress", "finished"), history.runs().stream().map(GameStatus.HistoryRun::status)
                .toList());
        GameStatus.HistoryRun first = history.runs().get(1);
        assertEquals(T0.toString(), first.startedAt());
        assertEquals(List.of(T0.toString(), T0.plusSeconds(30).toString()),
                first.pings().stream().map(GameStatus.HistoryPing::sentAt).toList());
        assertEquals(GameState.STATIC, first.pings().getFirst().mode());
        assertNoPrivateFields(history);

        execute("DELETE FROM tc_game_controller WHERE id = 1");
        assertEquals(GameStatus.SpeedhuntProgress.INACTIVE, status.speedhunt(null));
        assertStatus(403, () -> status.status(user()));
        assertStatus(403, () -> status.speedhuntHistory(user()));
    }

    // Operator snapshot -------------------------------------------------------------------------------------

    @Test
    void theSnapshotHoldsEverythingTheToolLoads() throws Exception {
        Game game = game();
        long session = game.session();
        grant(session, null, GameJokerRepository.SPEEDHUNT, 3);
        grant(session, game.ben(), GameJokerRepository.FAKE_PING, 1);
        fake(session, game.ben(), 2, 2.5, null);
        resources.addMirror(session, device(), game.anna().id(), GameMember.HUNTER, null);
        resources.addGroup(session, group(), "player", null);
        apply(session, ping(game.anna(), 1), new GameOperation.Catch(game.dora().id(), "Operator"));
        GameRequest open = requests.submit(game.hunter(), new GameRequestService.Submission(GameRequest.STATIC_PING,
                game.carl().id(), memberRevision(game.carl().id()), null));
        new GameDocumentService(transactions, mapper, cipher()).putCheckpoint(session, "monitoring", 0, 1,
                json("{\"cursor\": 5}"));
        new GameNotificationService(transactions, publisher).initialize(session);

        GameSnapshotService.Snapshot snapshot = snapshots.snapshot(session);
        assertEquals(session, snapshot.gameSessionId());
        assertEquals(revision(session), snapshot.revision());
        assertEquals(snapshot.revision(), snapshot.session().revision());
        assertEquals(EPOCH, snapshot.executingEpoch());
        assertEquals(T0, snapshot.readAt());
        assertEquals(json(SETTINGS), snapshot.session().settings().settings().path("game_settings"));
        assertEquals(6, snapshot.members().size());
        assertEquals(1, snapshot.resources().mirrors().size());
        assertEquals(1, snapshot.resources().groups().size());
        assertEquals(List.of(game.dora().id()), snapshot.state().catches().stream()
                .map(GameState.Catch::memberId).toList());
        assertEquals(game.anna().id(), snapshot.state().runs().getFirst().run().targetMemberId());
        assertEquals(memberRevision(game.anna().id()), snapshot.state().memberRevisions().get(game.anna().id()));
        assertEquals(1, snapshot.state().statistics().size());
        assertEquals(List.of(open.id()), snapshot.requests().stream().map(GameRequest::id).toList());
        // Ben's speedhunt fake bound to Anna's run: operator data names the run.
        assertEquals(List.of(), snapshot.fakePlans());
        assertEquals(snapshot.state().runs().getFirst().run().id(),
                snapshot.jokers().boundFakes().getFirst().fake().boundRunId());
        assertEquals(1, snapshot.jokers().reservations().size());
        assertEquals(0, snapshot.timetable().revision());
        assertEquals(0, snapshot.geofencePlans().revision());
        assertEquals(List.of("monitoring"), snapshot.checkpoints().stream()
                .map(GameDocumentService.Checkpoint::component).toList());
        assertEquals(1, snapshot.notifications().revision());
        assertEquals(0, snapshot.proximity().revision());

        assertStatus(404, () -> snapshots.snapshot(session + 1000));
    }

    @Test
    void aSnapshotSeesNothingThatCommitsWhileItReads() throws Exception {
        Game game = game();
        long session = game.session();
        long before = revision(session);
        var result = transactions.snapshot(transaction -> {
            long revision = GameSessionService.read(transaction, mapper, session).revision();
            int memberCount = GameMemberRepository.list(transaction, session).size();
            // A writer commits meanwhile: a new member (a table read already) and a catch (one read later).
            CompletableFuture.runAsync(() -> {
                try {
                    plan(session, "player");
                    state.apply(session, EPOCH, List.of(new GameOperation.Catch(game.anna().id(), "Operator")));
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            }).get(30, TimeUnit.SECONDS);
            return new long[] {revision, memberCount, GameSessionService.read(transaction, mapper, session).revision(),
                    GameMemberRepository.list(transaction, session).size(),
                    GameStateRepository.catches(transaction, session, false).size()};
        });
        assertEquals(before, result[0]);
        assertEquals(result[0], result[2]);
        assertEquals(result[1], result[3]);
        assertEquals(0, result[4]);
        assertEquals(before + 2, revision(session));
        assertEquals(1, snapshots.snapshot(session).state().catches().size());
    }

}
