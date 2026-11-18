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
 * Break timers run by the server (plan phase 3), driven by {@link TaskGameExecution} on virtual time, whatever the
 * controller lease does: a break ending at its deadline (also one whose deadline passed while the game was stopped,
 * or before a new break of its member), a planned start waiting for any speedhunt or another break and then lasting
 * its full duration, a late start after the game was stopped, missed planned breaks, a plan approved after its
 * start, and the events. It holds the cases of the Tool's break timers ({@code test_breaks.py} in
 * {@code tests/ui/live_session} and {@code test_planned_breaks.py}). Runs on H2 ({@link GameBreakRunnerH2Test}) and
 * MySQL ({@link GameBreakRunnerMySqlTest}).
 */
public abstract class GameBreakRunnerTest extends GameTestBase {

    private static final long EPOCH = 41;
    private static final String SETTINGS = "{\"break_duration_minutes\": 10, \"pings_per_speedhunt\": 3}";

    private GameStateService state;
    private GameJokerService jokers;
    private GameRequestService requests;
    private GameEffectsService effects;
    private GameExecutionService execution;
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

    /** A new runner on the execution task, as at server start. */
    private void startRunner() {
        new TaskGameExecution(transactions, scheduler, List.of(new GameBreakRunner(transactions, publisher)))
                .schedule(null);
    }

    @AfterEach
    void reset() throws Exception {
        scheduler.shutdown();
        execute("UPDATE tc_game_session_state SET state = 'stopped', runningkey = NULL WHERE runningkey = 1");
        publisher.clear();
    }

    // Fixture -----------------------------------------------------------------------------------------------

    /** Anna and Ben (players) with break jokers, Sue (supporter) and a hunter, in a running game. */
    private record Game(long session, GameMember anna, GameMember ben, GameMember sue, GameMember hunter) {
    }

    private Game game() throws Exception {
        long session = session(SETTINGS);
        GameMember anna = bound(session, GameMember.PLAYER);
        GameMember ben = bound(session, GameMember.PLAYER);
        GameMember sue = bound(session, GameMember.SUPPORTER);
        GameMember hunter = bound(session, GameMember.HUNTER);
        for (GameMember member : List.of(anna, ben, sue)) {
            jokers.change(session, new GameJokerService.Change(member.id(), GameJokerRepository.BREAK, 3, null),
                    "Operator");
        }
        lease(session, EPOCH);
        return new Game(session, anna, ben, sue, hunter);
    }

    private GameEffectsService.Outcome tool(Game game, List<GameRequestService.Completion> completions,
                                            GameOperation... operations) throws Exception {
        return effects.apply(game.session(), EPOCH, new GameEffectsService.Delivery("delivery-" + UUID.randomUUID(),
                List.of(operations), null, null, null, null, completions, null));
    }

    private GameEffectsService.Outcome tool(Game game, GameOperation... operations) throws Exception {
        return tool(game, null, operations);
    }

    /** A break request that starts now, completed by the Tool with its break: the break's ID. */
    private long breakNow(Game game, GameMember member) throws Exception {
        GameRequest request = requests.submit(member, new GameRequestService.Submission(GameRequest.BREAK, null,
                null, null));
        requests.claim(game.session(), request.id(), EPOCH);
        return tool(game, List.of(new GameRequestService.Completion(request.id(), GameRequest.SUCCEEDED, "Done",
                null)), new GameOperation.BreakStarted(request.id(), null)).operations().getFirst().id();
    }

    /** An operator's planned break (the Tool's intent): the break's ID. */
    private long plan(Game game, GameMember member, Instant start) throws Exception {
        return jokers.planBreak(game.session(), member.id(), start.toString(), false, "Operator").plannedBreak()
                .breakId();
    }

    /** A static speedhunt ping on {@code target}: a speedhunt runs until it ends. */
    private void speedhunt(Game game, GameMember target) throws Exception {
        tool(game, new GameOperation.PingDelivered(target.id(), 1, GameState.STATIC, GameState.REAL, true, true, true,
                null, null, "Operator", true));
    }

    private void speedhuntEnds(Game game, GameMember target) throws Exception {
        tool(game, new GameOperation.RunEnded(target.id(), GameState.TERMINATED, null));
    }

    private GameState.Break pause(Game game, long breakId) throws Exception {
        return state.breaks(game.session()).stream().filter(item -> item.id() == breakId).findFirst().orElseThrow();
    }

    private GameJokerService.Inventory breakJokers(GameMember member) throws Exception {
        return jokers.own(member).inventories().stream().filter(item -> item.type().equals(GameJokerRepository.BREAK))
                .findFirst().orElseThrow();
    }

    private List<JsonNode> events(long breakId) {
        return publisher.events.stream().filter(event -> event.category().equals(GameEvent.BREAK)
                && event.data().path("breakId").asLong() == breakId).map(GameEvent::data).toList();
    }

    private List<String> types(long breakId) {
        return events(breakId).stream().map(data -> data.get("type").asText()).toList();
    }

    private boolean protects(Game game, GameMember member) throws Exception {
        return transactions.read(transaction -> GameStateRepository.activeBreak(transaction, game.session(),
                member.id()).map(item -> item.protects(transaction.now())).orElse(false));
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

    // Deadlines ---------------------------------------------------------------------------------------------

    @Test
    void aBreakEndsAtItsDeadlineWithoutTheLease() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        long revision = memberRevision(anna.id());
        long breakId = breakNow(game, anna);
        // The Tool's request break is its intent's outcome, not an event.
        assertEquals(List.of(), types(breakId));
        assertEquals(revision + 1, memberRevision(anna.id()));
        assertEquals(T0.plusSeconds(600), pause(game, breakId).deadline());
        noLease();
        startRunner();
        publisher.clear();

        advance(599);
        assertEquals(GameState.ACTIVE, pause(game, breakId).state());
        assertTrue(protects(game, anna));
        advance(1);
        GameState.Break ended = pause(game, breakId);
        assertEquals(GameState.ENDED, ended.state());
        assertEquals(T0.plusSeconds(600), ended.endedAt());
        assertFalse(protects(game, anna));
        // Hunters see the end: the member revision advances and the status is announced.
        assertEquals(revision + 2, memberRevision(anna.id()));
        assertFalse(publisher.topic(GamePublisher.STATUS).isEmpty());
        JsonNode event = events(breakId).getFirst();
        assertEquals(GameBreakEvents.ENDED, event.get("type").asText());
        assertEquals(GameBreakEvents.DEADLINE, event.get("endReason").asText());
        assertEquals(anna.id(), event.get("memberId").asLong());
        assertEquals(anna.trackerId(), event.get("trackerId").asLong());
        assertEquals(anna.displayName(), event.get("displayName").asText());
        assertEquals(T0.toString(), event.get("startedAt").asText());
        assertEquals(T0.plusSeconds(600).toString(), event.get("deadline").asText());
        assertEquals(GameState.BY_PARTICIPANT, event.get("plannedBy").asText());
        assertEquals("The break of " + anna.displayName() + " ended (time is up).",
                publisher.events.getLast().message());
        advance(5);
        assertEquals(1, events(breakId).size(), "ended once");
        assertEquals(1, breakJokers(anna).used());
        assertEquals(List.of(), jokers.reconcile(game.session()));
    }

    @Test
    void deadlinesAreAbsoluteAndABreakPastItsDeadlineEndsBeforeANewOneOfItsMember() throws Exception {
        Game game = game();
        long first = breakNow(game, game.anna());
        long other = breakNow(game, game.ben());
        long planned = plan(game, game.sue(), T0.plusSeconds(3600));
        startRunner();
        // The game is stopped over both deadlines (an administrator's stop without control): stopping moves no
        // deadline and touches no planned break. Nothing executes, yet the deadline ends the protection.
        noLease();
        execution.change(game.session(), GameExecutionService.STOPPED, operator, null);
        assertEquals(T0.plusSeconds(600), pause(game, first).deadline());
        assertEquals(GameState.PLANNED, pause(game, planned).state());
        advance(700);
        assertEquals(GameState.ACTIVE, pause(game, first).state());
        assertFalse(protects(game, game.anna()));
        assertEquals(List.of(), types(first));

        // Anna's next break request (the Tool's) ends the old break first, as its deadline did.
        startGame(game.session());
        long second = breakNow(game, game.anna());
        assertEquals(GameState.ENDED, pause(game, first).state());
        assertEquals(List.of(GameBreakEvents.ENDED), types(first));
        assertEquals(GameState.ACTIVE, pause(game, second).state());
        assertEquals(T0.plusSeconds(1300), pause(game, second).deadline());
        // Ben's ends with the first tick once the game runs again.
        advance(1);
        assertEquals(GameState.ENDED, pause(game, other).state());
        assertEquals(List.of(GameBreakEvents.ENDED), types(other));
        assertEquals(GameState.ACTIVE, pause(game, second).state());
    }

    @Test
    void aRestartKeepsRunningBreaksAndEndsExpiredOnes() throws Exception {
        Game game = game();
        long early = breakNow(game, game.anna());
        clock.set(T0.plusSeconds(300));
        long later = breakNow(game, game.ben());
        // The server was down; a new runner starts long after Anna's deadline.
        clock.set(T0.plusSeconds(660));
        startRunner();
        advance(1);
        assertEquals(GameState.ENDED, pause(game, early).state());
        assertEquals(GameState.ACTIVE, pause(game, later).state());
        assertEquals(T0.plusSeconds(900), pause(game, later).deadline(), "the running break keeps its deadline");
        advanceTo(T0.plusSeconds(900));
        assertEquals(GameState.ENDED, pause(game, later).state());
    }

    // Planned starts ----------------------------------------------------------------------------------------

    @Test
    void aPlannedStartWaitsForAnySpeedhuntAndThenLastsItsFullDuration() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        long breakId = plan(game, anna, T0.plusSeconds(300));
        long revision = memberRevision(anna.id());
        // A speedhunt on another participant runs: waiting for any speedhunt reveals no target.
        speedhunt(game, game.ben());
        noLease();
        startRunner();
        publisher.clear();

        advanceTo(T0.plusSeconds(299));
        assertEquals(GameState.PLANNED, pause(game, breakId).state());
        advance(1);
        assertEquals(GameState.WAITING, pause(game, breakId).state());
        // The owner sees that it waits; hunters see nothing.
        assertEquals(revision, memberRevision(anna.id()));
        assertTrue(publisher.topic(GamePublisher.JOKERS).stream()
                .anyMatch(item -> item.members() != null && item.members().contains(anna.id())));
        assertTrue(publisher.topic(GamePublisher.STATUS).isEmpty());
        JsonNode waiting = events(breakId).getFirst();
        assertEquals(GameBreakEvents.WAITING, waiting.get("type").asText());
        assertEquals(GameBreakEvents.SPEEDHUNT, waiting.get("reason").asText());
        assertEquals(T0.plusSeconds(300).toString(), waiting.get("plannedStart").asText());
        assertEquals(GameState.BY_OPERATOR, waiting.get("plannedBy").asText());
        assertTrue(waiting.get("reservationId").asLong() > 0);
        assertEquals("The planned break of " + anna.displayName() + " for " + T0.plusSeconds(300)
                + " waits: a speedhunt is running.", publisher.events.getLast().message());

        // It waits as long as the speedhunt runs, announced once.
        advance(1200);
        assertEquals(List.of(GameBreakEvents.WAITING), types(breakId));
        assertEquals(1, breakJokers(anna).reserved());
        speedhuntEnds(game, game.ben());
        advance(1);
        GameState.Break started = pause(game, breakId);
        assertEquals(GameState.ACTIVE, started.state());
        Instant at = T0.plusSeconds(1501);
        assertEquals(at, started.startedAt());
        assertEquals(at.plusSeconds(600), started.deadline(), "its full duration");
        assertTrue(protects(game, anna));
        assertEquals(revision + 1, memberRevision(anna.id()));
        assertEquals(1, breakJokers(anna).used());
        assertEquals(0, breakJokers(anna).reserved());
        JsonNode event = events(breakId).getLast();
        assertEquals(GameBreakEvents.STARTED, event.get("type").asText());
        assertEquals(at.toString(), event.get("startedAt").asText());
        assertEquals(at.plusSeconds(600).toString(), event.get("deadline").asText());
        assertFalse(event.get("late").asBoolean());
        assertTrue(event.get("waited").asBoolean());
        assertFalse(event.get("overAllocated").asBoolean());
        assertEquals(anna.displayName() + " is on a planned break until " + at.plusSeconds(600) + " (10 min): hunters"
                + " receive none of their positions and no speedhunt can start on them.",
                publisher.events.getLast().message());
        // No speedhunt starts on her now.
        assertStatus(409, () -> tool(game, new GameOperation.PingDelivered(anna.id(), 1, GameState.STATIC,
                GameState.REAL, true, true, true, null, null, "Operator", true)));
        assertEquals(1, number("SELECT COUNT(*) FROM tc_game_joker_ledger WHERE gamesessionid = ? AND kind = ?"
                + " AND actor = 'Server'", game.session(), GameJokerRepository.USE));

        advanceTo(at.plusSeconds(600));
        assertEquals(GameState.ENDED, pause(game, breakId).state());
        assertEquals(List.of(GameBreakEvents.WAITING, GameBreakEvents.STARTED, GameBreakEvents.ENDED),
                types(breakId));
        assertEquals(List.of(), jokers.reconcile(game.session()));
    }

    @Test
    void aPlannedStartWaitsWhileItsMemberIsStillOnABreak() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        long first = plan(game, anna, T0.plusSeconds(60));
        long second = plan(game, anna, T0.plusSeconds(700));
        speedhunt(game, game.ben());
        startRunner();
        // The first waits for the speedhunt beyond the second's start: both wait.
        advanceTo(T0.plusSeconds(700));
        assertEquals(GameState.WAITING, pause(game, first).state());
        assertEquals(GameState.WAITING, pause(game, second).state());
        speedhuntEnds(game, game.ben());
        advance(1);
        // The earlier plan starts first; the later one now waits for it to end (a new reason, announced again).
        assertEquals(GameState.ACTIVE, pause(game, first).state());
        assertEquals(GameState.WAITING, pause(game, second).state());
        List<JsonNode> waits = events(second);
        assertEquals(List.of(GameBreakEvents.SPEEDHUNT, GameBreakEvents.ON_BREAK),
                waits.stream().map(data -> data.get("reason").asText()).toList());
        Instant end = pause(game, first).deadline();
        assertEquals(T0.plusSeconds(1301), end);
        advanceTo(end);
        assertEquals(GameState.ENDED, pause(game, first).state());
        GameState.Break next = pause(game, second);
        assertEquals(GameState.ACTIVE, next.state(), "it starts in the tick that ended the other");
        assertEquals(end.plusSeconds(600), next.deadline(), "and lasts its full duration");
        assertEquals(List.of(GameBreakEvents.WAITING, GameBreakEvents.WAITING, GameBreakEvents.STARTED),
                types(second));
    }

    @Test
    void aStartThatPassedWhileTheGameWasStoppedStartsLateAndEndsAtItsPlannedEnd() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        long breakId = plan(game, anna, T0.plusSeconds(300));
        startRunner();
        advanceTo(T0.plusSeconds(200));
        stopGame(game.session());
        advanceTo(T0.plusSeconds(500));
        assertEquals(GameState.PLANNED, pause(game, breakId).state(), "nothing executes while the game is stopped");
        startGame(game.session());
        advance(1);
        GameState.Break started = pause(game, breakId);
        assertEquals(GameState.ACTIVE, started.state());
        assertEquals(T0.plusSeconds(501), started.startedAt());
        assertEquals(T0.plusSeconds(900), started.deadline(), "the planned end");
        JsonNode event = events(breakId).getFirst();
        assertTrue(event.get("late").asBoolean());
        assertFalse(event.get("waited").asBoolean());

        // A plan seen while the game ran that waits, and runs again only after a stop, is late too.
        long waiting = plan(game, game.ben(), T0.plusSeconds(1000));
        speedhunt(game, game.sue());
        advanceTo(T0.plusSeconds(1010));
        assertEquals(GameState.WAITING, pause(game, waiting).state());
        stopGame(game.session());
        speedhuntEnds(game, game.sue());
        advanceTo(T0.plusSeconds(1300));
        startGame(game.session());
        advance(1);
        assertEquals(GameState.ACTIVE, pause(game, waiting).state());
        assertEquals(T0.plusSeconds(1600), pause(game, waiting).deadline());
        assertTrue(events(waiting).getLast().get("late").asBoolean());
        assertTrue(events(waiting).getLast().get("waited").asBoolean());
    }

    @Test
    void aStartSeenWithinTheGraceAfterTheGameRunsAgainKeepsItsFullDuration() throws Exception {
        Game game = game();
        long breakId = plan(game, game.anna(), T0.plusSeconds(300));
        startRunner();
        stopGame(game.session());
        advanceTo(T0.plusSeconds(320));
        startGame(game.session());
        advance(1);
        assertEquals(T0.plusSeconds(921), pause(game, breakId).deadline());
        assertFalse(events(breakId).getFirst().get("late").asBoolean());
    }

    @Test
    void aPlannedBreakIsMissedOnceItsPlannedEndPassedAndItsJokerReturns() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        long breakId = plan(game, anna, T0.plusSeconds(300));
        long revision = memberRevision(anna.id());
        startRunner();
        stopGame(game.session());
        advanceTo(T0.plusSeconds(900));
        startGame(game.session());
        advance(1);
        GameState.Break missed = pause(game, breakId);
        assertEquals(GameState.MISSED, missed.state());
        assertEquals(0, breakJokers(anna).reserved());
        assertEquals(0, breakJokers(anna).used());
        assertEquals(3, breakJokers(anna).available());
        assertEquals(revision, memberRevision(anna.id()), "hunters never saw it");
        assertFalse(protects(game, anna));
        JsonNode event = events(breakId).getFirst();
        assertEquals(GameBreakEvents.MISSED, event.get("type").asText());
        assertEquals(T0.plusSeconds(900).toString(), event.get("plannedEnd").asText());
        assertEquals(GameBreakEvents.MISSED_TEXT, event.get("reason").asText());
        assertEquals(1, number("SELECT COUNT(*) FROM tc_game_joker_ledger WHERE gamesessionid = ? AND kind = ?"
                + " AND detail = 'The planned break was missed'", game.session(), GameJokerRepository.RELEASE));

        // A late plan that waits until its planned end is missed as well.
        long late = plan(game, game.ben(), T0.plusSeconds(1000));
        stopGame(game.session());
        advanceTo(T0.plusSeconds(1100));
        startGame(game.session());
        speedhunt(game, game.sue());
        advance(1);
        assertEquals(GameState.WAITING, pause(game, late).state());
        advanceTo(T0.plusSeconds(1600));
        assertEquals(GameState.MISSED, pause(game, late).state());
        assertEquals(List.of(GameBreakEvents.WAITING, GameBreakEvents.MISSED), types(late));
        assertEquals(List.of(), jokers.reconcile(game.session()));
    }

    @Test
    void aPlanApprovedAfterItsStartStartsAtOnceWithItsFullDuration() throws Exception {
        Game game = game();
        GameMember anna = game.anna();
        Instant start = T0.plusSeconds(60);
        GameRequest request = requests.submit(anna, new GameRequestService.Submission(GameRequest.BREAK, null, null,
                json("{\"startsAt\": \"" + start + "\"}")));
        startRunner();
        // The operator approves it five minutes after its start.
        advanceTo(T0.plusSeconds(360));
        requests.claim(game.session(), request.id(), EPOCH);
        tool(game, List.of(new GameRequestService.Completion(request.id(), GameRequest.SUCCEEDED, "Planned",
                null)));
        GameState.Break planned = state.breaks(game.session()).getFirst();
        assertEquals(T0.plusSeconds(360), planned.plannedStart());
        advance(1);
        GameState.Break started = pause(game, planned.id());
        assertEquals(GameState.ACTIVE, started.state());
        assertEquals(T0.plusSeconds(961), started.deadline());
    }

    @Test
    void aWaitGoesOnSilentlyAfterARestartAndNothingRunsForAnotherSession() throws Exception {
        Game game = game();
        long breakId = plan(game, game.anna(), T0.plusSeconds(60));
        speedhunt(game, game.ben());
        startRunner();
        advanceTo(T0.plusSeconds(61));
        assertEquals(List.of(GameBreakEvents.WAITING), types(breakId));
        // A restart: a new runner sees the waiting break and announces nothing new.
        scheduler.shutdown();
        startRunner();
        advance(30);
        assertEquals(List.of(GameBreakEvents.WAITING), types(breakId));

        // Another session's game runs: this session's breaks wait for their own game.
        Game other = game();
        assertNotEquals(game.session(), other.session());
        speedhuntEnds(game, game.ben());
        advance(5);
        assertEquals(GameState.WAITING, pause(game, breakId).state());
        // Its game runs again 10 minutes after the start: late, and its planned end has passed.
        startGame(game.session());
        advance(1);
        assertEquals(GameState.ACTIVE, pause(game, breakId).state());
        assertEquals(T0.plusSeconds(660), pause(game, breakId).deadline());
    }

    @Test
    void aCaughtMembersPlanIsReleasedByTheCatchAndNeverStarts() throws Exception {
        Game game = game();
        long breakId = plan(game, game.anna(), T0.plusSeconds(60));
        startRunner();
        tool(game, new GameOperation.Catch(game.anna().id(), "Hunter"));
        assertEquals(GameState.CANCELLED, pause(game, breakId).state());
        advanceTo(T0.plusSeconds(120));
        assertEquals(GameState.CANCELLED, pause(game, breakId).state());
        assertEquals(List.of(), types(breakId));
    }

}
