package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.traccar.schedule.TaskGameExecution;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The server-run timetable (plan 1.5, B2) driven by {@link TaskGameExecution} on virtual time: due slots claimed
 * with their entries waiting in parallel, missed and interrupted slots, the toggle and the game state switched off
 * and on, execution without a lease, entries never skipped for being late, and the cases of the Tool's timetable
 * execution tests ({@code test_execution.py}, {@code test_background_execution.py}, {@code test_services.py}).
 * Runs on H2 ({@link GameTimetableRunnerH2Test}) and MySQL ({@link GameTimetableRunnerMySqlTest}).
 */
public abstract class GameTimetableRunnerTest extends GameTestBase {

    private GameTimetableService timetables;
    private GameExecutionService execution;
    private GameTimetableRunner runner;
    private TaskGameExecution task;
    private long operator;

    @Override
    protected Set<GameBindingHook> hooks() {
        return Set.of(new GamePlanInvalidation(publisher), new GameAccessRule(transactions));
    }

    @Override
    protected void createServices() throws Exception {
        timetables = new GameTimetableService(transactions, mapper, publisher);
        execution = new GameExecutionService(transactions, new GameControllerService(source, clock, 30, 5, null,
                publisher), publisher);
        operator = user();
    }

    private void startRunner() {
        runner = new GameTimetableRunner(transactions, publisher, engine());
        task = new TaskGameExecution(transactions, scheduler, List.of(runner));
        task.schedule(null);
    }

    @AfterEach
    void reset() throws Exception {
        scheduler.shutdown();
        execute("UPDATE tc_game_session_state SET state = 'stopped', runningkey = NULL WHERE runningkey = 1");
        clock.set(T0);
        publisher.clear();
    }

    // Fixture -----------------------------------------------------------------------------------------------

    /** A session with participants whose mirrors reach hunters, players and supporters. */
    private record Game(long session, List<GameMember> members, Map<Long, Map<String, Long>> mirrors) {
        GameMember member(int index) {
            return members.get(index);
        }

        long mirror(GameMember member, String audience) {
            return mirrors.get(member.id()).get(audience);
        }
    }

    private Game game(int players, String settings) throws Exception {
        long session = session(settings);
        List<GameMember> list = new ArrayList<>();
        Map<Long, Map<String, Long>> mirrors = new java.util.HashMap<>();
        for (int i = 0; i < players; i++) {
            list.add(bound(session, GameMember.PLAYER));
        }
        GameMember supporter = bound(session, GameMember.SUPPORTER);
        list.add(supporter);
        bound(session, GameMember.HUNTER);
        for (GameMember member : list) {
            Map<String, Long> devices = new java.util.HashMap<>();
            devices.put("hunters", resources.addMirror(session, device(), member.id(), GameMember.HUNTER, null)
                    .deviceId());
            devices.put("players", resources.addMirror(session, device(), member.id(), GameMember.PLAYER, null)
                    .deviceId());
            devices.put("supporters", resources.addMirror(session, device(), member.id(), GameMember.SUPPORTER, null)
                    .deviceId());
            mirrors.put(member.id(), devices);
        }
        return new Game(session, list, mirrors);
    }

    private Game game(int players) throws Exception {
        return game(players, "{}");
    }

    private List<Long> rows(Game game, Row... rows) throws Exception {
        return timetable(timetables, game.session(), game.members(), rows);
    }

    private void enable(Game game, boolean enabled) throws Exception {
        timetables.setExecution(game.session(), enabled, operator);
    }

    private String status(long slot, GameMember member) throws Exception {
        return text("SELECT status FROM tc_game_timetable_entries WHERE slotid = ? AND memberid = ?", slot,
                member.id());
    }

    private String reason(long slot, GameMember member) throws Exception {
        return text("SELECT reason FROM tc_game_timetable_entries WHERE slotid = ? AND memberid = ?", slot,
                member.id());
    }

    /** Stores content no changeset accepts, as a database written by another version could hold. */
    private void invalidate(long slot, GameMember member) throws Exception {
        execute("UPDATE tc_game_timetable_entries SET content = 'x' WHERE slotid = ? AND memberid = ?", slot,
                member.id());
    }

    private String slotState(long slot) throws Exception {
        return text("SELECT state FROM tc_game_timetable_slots WHERE id = ?", slot);
    }

    private List<JsonNode> events(String type) {
        return publisher.events.stream().filter(event -> event.data().path("type").asText().equals(type))
                .map(GameEvent::data).toList();
    }

    private JsonNode ping(GameMember member) {
        return events(GameTimetableEvents.PING).stream()
                .filter(data -> data.get("memberId").asLong() == member.id()).reduce((a, b) -> b).orElseThrow();
    }

    private List<String> types() {
        return publisher.events.stream().map(event -> event.data().path("type").asText()).toList();
    }

    private void advanceTo(Instant time) {
        scheduler.advance(Duration.between(clock.instant(), time));
    }

    // Scheduling --------------------------------------------------------------------------------------------

    @Test
    void aDueSlotIsClaimedAndEachEntryIsSentAsSoonAsItsFixIsFresh() throws Exception {
        Game game = game(3);
        GameMember anna = game.member(0);
        GameMember ben = game.member(1);
        GameMember cleo = game.member(2);
        GameMember dana = game.member(3);
        long slot = rows(game, row(T0.plusSeconds(60), anna, "p", ben, "2H", cleo, "p", dana, "b")).getFirst();
        // Changesets store only valid cells: invalid content (here the old "no ping" x) is skipped when claimed.
        invalidate(slot, cleo);
        trackerPosition(anna.trackerId(), 46.0, 7.0, T0.minusSeconds(100));
        trackerPosition(dana.trackerId(), 46.1, 7.1, T0.plusSeconds(45));
        enable(game, true);
        startGame(game.session());
        startRunner();
        publisher.clear();

        advanceTo(T0.plusSeconds(59));
        assertEquals(GameTimetableRepository.SCHEDULED, slotState(slot));
        advanceTo(T0.plusSeconds(60));
        assertEquals(GameTimetableRepository.CLAIMED, slotState(slot));
        assertEquals(List.of("slotStarted", "ping", "ping"), types());
        assertEquals(GameTimetableEvents.INVALID, ping(cleo).get("reason").asText());
        // Dana's fix is fresh already (within the 20 s window): sent at once, to players and supporters only.
        assertEquals("sent", ping(dana).get("status").asText());
        assertArrayEquals(new double[] {46.1, 7.1}, latest(game.mirror(dana, "players")));
        assertNull(latest(game.mirror(dana, "hunters")));
        // Anna and Ben wait in parallel; Anna's fresh fix arrives first and is sent at once.
        advanceTo(T0.plusSeconds(62));
        waiter.arrived(arriving(anna.trackerId(), 46.5, 7.5, T0.plusSeconds(61)));
        scheduler.runPending();
        assertEquals(GameTimetableRepository.SENT, status(slot, anna));
        assertEquals(GameTimetableRepository.SCHEDULED, status(slot, ben));
        JsonNode annaPing = ping(anna);
        assertEquals(2.0, annaPing.get("delaySeconds").asDouble());
        assertFalse(annaPing.get("position").get("stale").asBoolean());
        assertEquals(List.of("hunters", "players", "supporters"), GameVectors.strings(annaPing.get("delivered")));
        assertEquals(anna.displayName(), annaPing.get("displayName").asText());
        assertEquals(anna.trackerId().longValue(), annaPing.get("trackerId").asLong());
        assertArrayEquals(new double[] {46.5, 7.5}, latest(game.mirror(anna, "hunters")));
        // Ben has no position at all: at the end of the wait his ping fails.
        int statusReads = publisher.topic(GamePublisher.STATUS).size();
        advanceTo(T0.plusSeconds(89));
        assertEquals(GameTimetableRepository.SCHEDULED, status(slot, ben));
        advanceTo(T0.plusSeconds(90));
        assertEquals(GameTimetableRepository.FAILED, status(slot, ben));
        assertEquals(GamePingEngine.NO_POSITION, reason(slot, ben));
        assertEquals(GameTimetableRepository.DONE, slotState(slot));
        JsonNode finished = events(GameTimetableEvents.SLOT_FINISHED).getFirst();
        assertEquals(2, finished.get("counts").get("sent").asInt());
        assertEquals(1, finished.get("counts").get("skipped").asInt());
        assertEquals(1, finished.get("counts").get("failed").asInt());
        // No scheduled slot is left: the toggle switches itself off, and members read timetableRunning again.
        advanceTo(T0.plusSeconds(91));
        assertEquals("completed", events(GameTimetableEvents.EXECUTION).getLast().get("state").asText());
        assertFalse(timetables.get(game.session()).execution().enabled());
        assertTrue(publisher.topic(GamePublisher.STATUS).size() > statusReads);
        assertEquals(List.of("slotStarted", "ping", "ping", "ping", "ping", "slotFinished", "execution"), types());
        // Every event reached the log, in order, for the Tool's catch-up.
        var logged = new GameLogService(transactions).list(game.session(),
                Long.toString(publisher.events.getFirst().id() - 1), 0, GameEvent.TIMETABLE, true);
        assertEquals(publisher.events.stream().map(GameEvent::id).toList(),
                logged.items().stream().map(GameLogService.LogEntry::id).toList());
        assertEquals(publisher.events.getLast().id(), timetables.get(game.session()).execution().lastEventId());
        // Execution never touched the plan revision (one changeset wrote columns and rows).
        assertEquals(1, timetables.get(game.session()).revision());
    }

    @Test
    void aStartedEntryIsNeverSkippedForBeingLate() throws Exception {
        Game game = game(1);
        GameMember anna = game.member(0);
        long slot = rows(game, row(T0.plusSeconds(60), anna, "p")).getFirst();
        trackerPosition(anna.trackerId(), 46.0, 7.0, T0.minusSeconds(100));
        enable(game, true);
        startRunner();
        // The game starts 25 s after the slot's time: still within its start grace, so the slot starts.
        advanceTo(T0.plusSeconds(85));
        startGame(game.session());
        advanceTo(T0.plusSeconds(86));
        assertEquals(GameTimetableRepository.CLAIMED, slotState(slot));
        // Its ping waits the full 30 s and goes out 56 s after its time, with the last known position.
        advanceTo(T0.plusSeconds(116));
        assertEquals(GameTimetableRepository.SENT, status(slot, anna));
        assertEquals(56.0, ping(anna).get("delaySeconds").asDouble());
        assertTrue(ping(anna).get("position").get("stale").asBoolean());
        assertTrue(publisher.events.stream().anyMatch(event -> event.message().startsWith(
                "Using last known position for " + anna.displayName())));
    }

    @Test
    void slotsDueWhileTheGameIsStoppedAreMissedAndStoppingSkipsWaitingEntries() throws Exception {
        Game game = game(1);
        GameMember anna = game.member(0);
        List<Long> slots = rows(game, row(T0.plusSeconds(60), anna, "p"), row(T0.plusSeconds(300), anna, "p"),
                row(T0.plusSeconds(400), anna, "p"));
        long fake = armFake(game.session(), anna, entry(timetables, game.session(), slots.get(0), anna.id()).id(),
                46.5, 7.5);
        long waitingFake = armFake(game.session(), anna, entry(timetables, game.session(), slots.get(1), anna.id())
                .id(), 46.5, 7.5);
        trackerPosition(anna.trackerId(), 46.0, 7.0, T0.minusSeconds(100));
        enable(game, true);
        startRunner();
        advanceTo(T0.plusSeconds(95));
        assertEquals(GameTimetableRepository.SCHEDULED, slotState(slots.get(0)));
        // Started again after the grace: missed, never replayed; its fake returns.
        startGame(game.session());
        advanceTo(T0.plusSeconds(96));
        assertEquals(GameTimetableRepository.DONE, slotState(slots.get(0)));
        assertEquals(GameTimetableRepository.SKIPPED, status(slots.get(0), anna));
        assertEquals(GamePingEngine.MISSED, reason(slots.get(0), anna));
        assertEquals(1, events(GameTimetableEvents.SLOT_MISSED).getFirst().get("entries").asInt());
        assertEquals(GameJokerRepository.RELEASED, reservationStatus(fake));

        // A slot whose entry waits for a fresh fix when the game stops: the entry is skipped (stopped).
        advanceTo(T0.plusSeconds(305));
        assertEquals(GameTimetableRepository.CLAIMED, slotState(slots.get(1)));
        execute("UPDATE tc_game_controller SET state = 'free', expiresat = NULL");
        var stopped = execution.change(game.session(), GameExecutionService.STOPPED, operator, null);
        assertEquals(GameExecutionService.STOPPED, stopped.state());
        assertEquals(GameTimetableRepository.SKIPPED, status(slots.get(1), anna));
        assertEquals(GameTimetableEvents.STOPPED, reason(slots.get(1), anna));
        assertEquals(GameTimetableRepository.DONE, slotState(slots.get(1)));
        assertEquals(GameJokerRepository.RELEASED, reservationStatus(waitingFake));
        assertEquals(1, events("gameStopped").getFirst().get("skippedTimetableEntries").asInt());
        // The wait notices and ends without writing anything.
        advanceTo(T0.plusSeconds(306));
        assertEquals(0, waiter.pending());
        assertNull(latest(game.mirror(anna, "hunters")));
        // Nothing executes while stopped, and the slot due meanwhile is missed when the game runs again.
        advanceTo(T0.plusSeconds(500));
        assertEquals(GameTimetableRepository.SCHEDULED, slotState(slots.get(2)));
        startGame(game.session());
        advanceTo(T0.plusSeconds(501));
        assertEquals(GamePingEngine.MISSED, reason(slots.get(2), anna));
    }

    @Test
    void theToggleGatesExecutionAndSwitchingOffSkipsWaitingEntries() throws Exception {
        Game game = game(2);
        GameMember anna = game.member(0);
        GameMember ben = game.member(1);
        List<Long> slots = rows(game, row(T0.plusSeconds(60), anna, "p", ben, "p"), row(T0.plusSeconds(120), anna,
                "p"));
        trackerPosition(ben.trackerId(), 46.2, 7.2, T0.plusSeconds(50));
        startGame(game.session());
        startRunner();
        advanceTo(T0.plusSeconds(65));
        assertEquals(GameTimetableRepository.SCHEDULED, slotState(slots.get(0)));
        // Switched on within the start grace: the slot starts late; Ben's fresh fix goes out at once.
        enable(game, true);
        advanceTo(T0.plusSeconds(70));
        assertEquals(GameTimetableRepository.SENT, status(slots.get(0), ben));
        assertEquals(GameTimetableRepository.SCHEDULED, status(slots.get(0), anna));
        // Switched off while Anna waits: her entry is skipped (stopped); nothing is sent for her later.
        enable(game, false);
        assertEquals(GameTimetableRepository.SKIPPED, status(slots.get(0), anna));
        assertEquals(GameTimetableEvents.STOPPED, reason(slots.get(0), anna));
        assertEquals(List.of("on", "off"), events(GameTimetableEvents.EXECUTION).stream()
                .map(data -> data.get("state").asText()).toList());
        waiter.arrived(arriving(anna.trackerId(), 45.0, 7.0, T0.plusSeconds(71)));
        advanceTo(T0.plusSeconds(130));
        assertNull(latest(game.mirror(anna, "hunters")));
        assertEquals(GameTimetableRepository.SCHEDULED, slotState(slots.get(1)));
        // On again: the second slot is within its grace and runs; then the timetable completes.
        trackerPosition(anna.trackerId(), 46.3, 7.3, T0.plusSeconds(125));
        enable(game, true);
        advanceTo(T0.plusSeconds(132));
        assertEquals(GameTimetableRepository.SENT, status(slots.get(1), anna));
        assertEquals("completed", events(GameTimetableEvents.EXECUTION).getLast().get("state").asText());
    }

    @Test
    void anInterruptedSlotRunsAgainWithinItsGraceAndIsMissedAfterIt() throws Exception {
        Game game = game(2);
        GameMember anna = game.member(0);
        GameMember ben = game.member(1);
        List<Long> slots = rows(game, row(T0.plusSeconds(60), anna, "p", ben, "p"), row(T0.plusSeconds(200), anna,
                "p", ben, "p"));
        trackerPosition(anna.trackerId(), 46.0, 7.0, T0.plusSeconds(55));
        trackerPosition(ben.trackerId(), 46.1, 7.1, T0.plusSeconds(55));
        enable(game, true);
        startGame(game.session());
        // A restart left the slot claimed with entries that were never delivered (outcomes commit with positions).
        clock.set(T0.plusSeconds(61));
        execute("UPDATE tc_game_timetable_slots SET state = 'claimed', claimedat = ? WHERE id = ?",
                T0.plusSeconds(60), slots.get(0));
        startRunner();
        advanceTo(T0.plusSeconds(62));
        assertEquals(GameTimetableRepository.SENT, status(slots.get(0), anna));
        assertEquals(GameTimetableRepository.SENT, status(slots.get(0), ben));
        assertEquals(GameTimetableRepository.DONE, slotState(slots.get(0)));
        // Interrupted beyond its grace: the open entries are skipped (missed), never sent late.
        scheduler.shutdown();
        clock.set(T0.plusSeconds(240));
        execute("UPDATE tc_game_timetable_slots SET state = 'claimed', claimedat = ? WHERE id = ?",
                T0.plusSeconds(200), slots.get(1));
        startRunner();
        publisher.clear();
        advanceTo(T0.plusSeconds(242));
        assertEquals(GameTimetableRepository.SKIPPED, status(slots.get(1), anna));
        assertEquals(GamePingEngine.MISSED, reason(slots.get(1), anna));
        assertEquals(GameTimetableRepository.DONE, slotState(slots.get(1)));
        assertEquals(List.of("ping", "ping", "slotFinished", "execution"), types());
    }

    @Test
    void executionGoesOnWithoutTheLeaseAndOnlyForTheRunningSession() throws Exception {
        Game game = game(1);
        Game other = game(1);
        GameMember anna = game.member(0);
        long slot = rows(game, row(T0.plusSeconds(60), anna, "p")).getFirst();
        long otherSlot = rows(other, row(T0.plusSeconds(60), other.member(0), "p")).getFirst();
        trackerPosition(anna.trackerId(), 46.0, 7.0, T0.plusSeconds(50));
        enable(game, true);
        enable(other, true);
        // The Tool held control and lost it: its lease expired. The game still runs, so the timetable runs.
        lease(game.session(), 51);
        execute("UPDATE tc_game_controller SET expiresat = ?", T0.minusSeconds(1));
        startRunner();
        advanceTo(T0.plusSeconds(61));
        assertEquals(GameTimetableRepository.SENT, status(slot, anna));
        // Another session's timetable never runs while its game is stopped.
        assertEquals(GameTimetableRepository.SCHEDULED, slotState(otherSlot));
        // With no running game the task asks no runner at all.
        stopGame(game.session());
        int[] asked = {0};
        var counting = new TaskGameExecution(transactions, scheduler, List.of(new GameExecutionRunner<Object>() {
            @Override
            public List<Object> due(long sessionId, Instant now) {
                asked[0]++;
                return List.of();
            }

            @Override
            public void run(long sessionId, Object item) {
            }
        }));
        counting.run();
        assertEquals(0, asked[0]);
        startGame(other.session());
        counting.run();
        assertEquals(1, asked[0]);
    }

    // The Tool's execution cases -----------------------------------------------------------------------------

    @Test
    void eachParticipantReportsWhatBecameOfTheirPing() throws Exception {
        // test_execution.py: test_each_participant_reports_what_became_of_their_ping.
        Game game = game(6);
        GameMember anna = game.member(0);
        GameMember ben = game.member(1);
        GameMember cleo = game.member(2);
        GameMember dana = game.member(3);
        GameMember eli = game.member(4);
        GameMember finn = game.member(5);
        GameMember gus = game.member(6);
        long slot = rows(game, row(T0.plusSeconds(60), anna, "p", ben, "2H", cleo, "p", dana, "p", eli, "p", finn,
                "p", gus, "47.0,8.0|2H")).getFirst();
        invalidate(slot, finn);
        for (GameMember member : List.of(anna, ben, dana, eli, finn, gus)) {
            trackerPosition(member.trackerId(), 46.9, 7.4, T0.plusSeconds(50));
        }
        onBreak(game.session(), ben, T0.plusSeconds(3600));
        caught(game.session(), dana);
        execute("UPDATE tc_game_mirrors SET state = 'retiring' WHERE deviceid = ?", game.mirror(eli, "hunters"));
        enable(game, true);
        startGame(game.session());
        startRunner();
        advanceTo(T0.plusSeconds(91));
        assertEquals(Map.of(
                anna.id(), "sent",
                ben.id(), "skipped/break",
                cleo.id(), "failed/noPosition",
                dana.id(), "skipped/caught",
                eli.id(), "failed/noMirror",
                finn.id(), "skipped/invalid",
                gus.id(), "sent"), Map.of(
                anna.id(), outcome(slot, anna), ben.id(), outcome(slot, ben), cleo.id(), outcome(slot, cleo),
                dana.id(), outcome(slot, dana), eli.id(), outcome(slot, eli), finn.id(), outcome(slot, finn),
                gus.id(), outcome(slot, gus)));
        // Gus's cell: the operator's coordinate for the hunters, the real position for the others.
        assertArrayEquals(new double[] {47.0, 8.0}, latest(game.mirror(gus, "hunters")));
        assertArrayEquals(new double[] {46.9, 7.4}, latest(game.mirror(gus, "players")));
        JsonNode coordinate = ping(gus).get("coordinate");
        assertEquals(47.0, coordinate.get("latitude").asDouble());
        assertEquals(List.of("hunters"), GameVectors.strings(coordinate.get("audiences")));
        // Dana is caught: nothing was even waited for.
        assertNull(latest(game.mirror(dana, "players")));
    }

    private String outcome(long slot, GameMember member) throws Exception {
        String reason = reason(slot, member);
        return status(slot, member) + (reason == null ? "" : "/" + reason);
    }

    @Test
    void onlyWhatIsSentIsLoggedDuringABreak() throws Exception {
        // test_execution.py: test_logs_name_only_what_is_sent_during_a_break.
        Game game = game(2);
        GameMember anna = game.member(0);
        List<Long> slots = rows(game, row(T0.plusSeconds(60), anna, "p"), row(T0.plusSeconds(120), anna, "2H"),
                row(T0.plusSeconds(180), anna, "47.0,8.0"), row(T0.plusSeconds(240), anna, "47.0,8.0|2P"));
        trackerPosition(anna.trackerId(), 46.9, 7.4, T0.plusSeconds(500));
        onBreak(game.session(), anna, T0.plusSeconds(3600));
        enable(game, true);
        startGame(game.session());
        startRunner();
        List<String> delivered = new ArrayList<>();
        List<String> withheld = new ArrayList<>();
        for (long slot : slots) {
            advanceTo(timetables.get(game.session()).slots().stream().filter(item -> item.id() == slot)
                    .findFirst().orElseThrow().scheduledAt().plusSeconds(1));
            assertEquals(GameTimetableRepository.SKIPPED, status(slot, anna));
            JsonNode data = ping(anna);
            delivered.add(String.join(",", GameVectors.strings(data.get("delivered"))));
            withheld.add(String.join(",", GameVectors.strings(data.get("withheld"))));
        }
        assertEquals(List.of("players,supporters", "", "players,supporters", "players,supporters"), delivered);
        assertEquals(List.of("hunters", "hunters", "hunters", "hunters"), withheld);
        // The 47.0,8.0|2P cell: the coordinate to the players, the real position to the supporters.
        assertArrayEquals(new double[] {47.0, 8.0}, latest(game.mirror(anna, "players")));
        assertArrayEquals(new double[] {46.9, 7.4}, latest(game.mirror(anna, "supporters")));
        assertNull(latest(game.mirror(anna, "hunters")));
        assertTrue(publisher.events.stream().anyMatch(event -> event.message().contains(
                "hunters withheld during the break")));
    }

    @Test
    void anArmedFakeReachesOnlyTheHuntersAndIsUsedWithTheDelivery() throws Exception {
        // test_execution.py: test_an_armed_fake_reaches_only_the_hunters_and_is_used_with_the_delivery.
        Game game = game(2);
        GameMember anna = game.member(0);
        GameMember ben = game.member(1);
        List<Long> slots = rows(game, row(T0.plusSeconds(60), anna, "p"), row(T0.plusSeconds(120), anna, "p"),
                row(T0.plusSeconds(180), ben, "p"));
        long used = armFake(game.session(), anna, entry(timetables, game.session(), slots.get(0), anna.id()).id(),
                46.5, 7.5);
        long inBreak = armFake(game.session(), anna, entry(timetables, game.session(), slots.get(1), anna.id()).id(),
                46.5, 7.5);
        long noPosition = armFake(game.session(), ben, entry(timetables, game.session(), slots.get(2), ben.id())
                .id(), 46.5, 7.5);
        trackerPosition(anna.trackerId(), 46.9, 7.4, T0.plusSeconds(55));
        enable(game, true);
        startGame(game.session());
        startRunner();
        advanceTo(T0.plusSeconds(61));
        assertArrayEquals(new double[] {46.5, 7.5}, latest(game.mirror(anna, "hunters")));
        assertArrayEquals(new double[] {46.9, 7.4}, latest(game.mirror(anna, "players")));
        assertEquals(GameJokerRepository.CONSUMED, reservationStatus(used));
        assertEquals("consumed", ping(anna).get("fake").get("status").asText());
        assertTrue(publisher.events.stream().anyMatch(event -> event.message().contains(
                "Hunters received " + anna.displayName() + "'s fake ping joker coordinate")));
        assertEquals(Set.of(anna.id()), publisher.topic(GamePublisher.JOKERS).getLast().members());
        // A break withholds the hunters, so the fake is released; so is one whose ping has no position.
        long pause = onBreak(game.session(), anna, T0.plusSeconds(3600));
        Instant breakStart = clock.instant();
        advanceTo(T0.plusSeconds(150));
        assertEquals(GameJokerRepository.RELEASED, reservationStatus(inBreak));
        assertEquals("released", ping(anna).get("fake").get("status").asText());
        advanceTo(T0.plusSeconds(211));
        assertEquals(GameTimetableRepository.FAILED, status(slots.get(2), ben));
        assertEquals(GameJokerRepository.RELEASED, reservationStatus(noPosition));

        // The operator read derives finished cells' effects from what is stored: the used fake ...
        var usedEffect = entry(timetables, game.session(), slots.get(0), anna.id()).effect();
        assertEquals(new GameTimetableEffects.FakeJoker(used, 46.5, 7.5), usedEffect.fakeJoker());
        assertNull(usedEffect.pause());
        assertEquals(List.of("players", "supporters"), usedEffect.real());
        assertEquals(List.of("hunters"), usedEffect.fake());
        assertEquals(46.5, usedEffect.latitude());
        // ... and reason break, with the break that withheld the hunters; a released fake is no effect.
        var breakEffect = entry(timetables, game.session(), slots.get(1), anna.id()).effect();
        assertNull(breakEffect.fakeJoker());
        assertEquals(new GameTimetableEffects.Break(pause, GameState.ACTIVE, breakStart, T0.plusSeconds(3600),
                false), breakEffect.pause());
        assertEquals(List.of("players", "supporters"), breakEffect.real());
        assertEquals(List.of("hunters"), breakEffect.withheld());
        assertNull(entry(timetables, game.session(), slots.get(2), ben.id()).effect());
    }

    @Test
    void theCaughtStateIsCheckedAgainAfterTheWait() throws Exception {
        // test_services.py: test_row_processor_rechecks_caught_state_after_async_fetch.
        Game game = game(1);
        GameMember anna = game.member(0);
        long slot = rows(game, row(T0.plusSeconds(60), anna, "p")).getFirst();
        enable(game, true);
        startGame(game.session());
        startRunner();
        advanceTo(T0.plusSeconds(61));
        assertEquals(1, waiter.pending());
        caught(game.session(), anna);
        waiter.arrived(arriving(anna.trackerId(), 46.9, 7.4, T0.plusSeconds(61)));
        scheduler.runPending();
        assertEquals("skipped/caught", outcome(slot, anna));
        assertNull(latest(game.mirror(anna, "hunters")));
    }

    @Test
    void theSettingsSetTheFreshnessWindowAndTheWait() throws Exception {
        Game game = game(1, "{\"timetable_freshness_window_seconds\": 5, \"timetable_fresh_fix_wait_seconds\": 10}");
        GameMember anna = game.member(0);
        long slot = rows(game, row(T0.plusSeconds(60), anna, "p")).getFirst();
        // 10 s before the slot is older than a 5 s window: Anna's ping waits, but only 10 s.
        trackerPosition(anna.trackerId(), 46.0, 7.0, T0.plusSeconds(50));
        enable(game, true);
        startGame(game.session());
        startRunner();
        advanceTo(T0.plusSeconds(69));
        assertEquals(GameTimetableRepository.SCHEDULED, status(slot, anna));
        advanceTo(T0.plusSeconds(70));
        assertEquals(GameTimetableRepository.SENT, status(slot, anna));
        assertTrue(ping(anna).get("position").get("stale").asBoolean());
    }

}
