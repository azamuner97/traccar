package org.traccar.game;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 3c of game data model 2: requests, jokers, the speedhunt pool, reservations, the ledger, fake ping plans,
 * planned breaks, the binding hook and mirror-only hunter access. Ports the model-1 rule tests. Runs on H2
 * ({@link GameJokerH2Test}) and MySQL ({@link GameJokerMySqlTest}).
 */
public abstract class GameJokerTest extends GameTestBase {

    private static final long EPOCH = 11;
    private static final String SETTINGS = "{\"break_duration_minutes\": 10,"
            + " \"fixed_speedhunt_live_duration_minutes\": 2}";

    private GameRequestService requests;
    private GameJokerService jokers;
    private GameStateService state;
    private GameTimetableService timetables;

    @Override
    protected Set<GameBindingHook> hooks() {
        return Set.of(new GamePlanInvalidation(publisher), new GameAccessRule(transactions));
    }

    @Override
    protected void createServices() {
        requests = new GameRequestService(transactions, mapper, publisher);
        jokers = new GameJokerService(transactions, publisher);
        state = new GameStateService(transactions, mapper, publisher);
        timetables = new GameTimetableService(transactions, mapper, publisher);
    }

    @AfterEach
    void resetClock() {
        clock.set(T0);
    }

    // Helpers -----------------------------------------------------------------------------------------------

    private long running(String settings) throws Exception {
        long session = session(settings);
        lease(session, EPOCH);
        return session;
    }

    private void grant(long session, GameMember member, String type, int quantity) throws Exception {
        jokers.change(session, new GameJokerService.Change(member == null ? null : member.id(), type, quantity,
                null), "Operator");
    }

    private GameJokerService.Inventory inventory(GameMember member, String type) throws Exception {
        return jokers.own(member).inventories().stream().filter(item -> item.type().equals(type)).findFirst()
                .orElseThrow();
    }

    private GameJokerService.Inventory pool(long session) throws Exception {
        return jokers.pool(session).inventories().getFirst();
    }

    private GameRequest submit(GameMember member, String type, String parameters) throws Exception {
        return requests.submit(member, new GameRequestService.Submission(type, null, null,
                parameters == null ? null : json(parameters)));
    }

    private GameRequest hunt(GameMember hunter, String type, GameMember target) throws Exception {
        return hunt(hunter, type, target, null);
    }

    private GameRequest hunt(GameMember hunter, String type, GameMember target, String parameters)
            throws Exception {
        Long revision = members.get(target.gameSessionId(), target.id()).stateRevision();
        return requests.submit(hunter, new GameRequestService.Submission(type, target.id(), revision,
                parameters == null ? null : json(parameters)));
    }

    /** Records request outcomes and operations in one transaction, as an effects delivery does. */
    private void deliver(long session, List<GameRequestService.Completion> completions,
                         GameOperation... operations) throws Exception {
        transactions.write(transaction -> {
            transaction.lockSession(session);
            List<GameOperation> list = List.of(operations);
            transaction.lockMemberStates(GameOperations.members(transaction, session, list));
            GameOperations applied = new GameOperations(transaction, session, EPOCH, "Tool", mapper);
            GameRequestService.complete(transaction, session, completions, applied);
            for (int i = 0; i < list.size(); i++) {
                applied.apply(i, list.get(i));
            }
            transaction.bumpSession();
            GameStateService.publish(transaction, publisher, session, applied);
            return applied;
        });
    }

    private void succeed(long session, long id, GameOperation... operations) throws Exception {
        requests.claim(session, id, EPOCH);
        deliver(session, List.of(new GameRequestService.Completion(id, GameRequest.SUCCEEDED, "Done", "detail")),
                operations);
    }

    private GameStateService.Applied apply(long session, GameOperation... operations) throws Exception {
        return state.apply(session, EPOCH, List.of(operations));
    }

    private static GameOperation.PingDelivered ping(GameMember target, int number, String source) {
        return new GameOperation.PingDelivered(target.id(), number, GameState.STATIC, source, true, true, true, null,
                null, "Operator", true);
    }

    private void assertReconciled(long session) throws Exception {
        assertEquals(List.of(), jokers.reconcile(session));
    }

    private String message(org.junit.jupiter.api.function.Executable executable) {
        return assertThrows(WebApplicationException.class, executable).getMessage();
    }

    // Inventories and reservations --------------------------------------------------------------------------

    @Test
    void jokersAreGrantedReservedConsumedAndReleased() throws Exception {
        long session = running(SETTINGS);
        GameMember anna = bound(session, "player");
        GameMember ben = bound(session, "supporter");
        GameMember hunter = bound(session, "hunter");
        publisher.clear();

        grant(session, anna, GameJokerRepository.HUNTER_POSITIONS, 2);
        assertEquals(Set.of(anna.id()), publisher.topic(GamePublisher.JOKERS).getLast().members());
        assertStatus(409, () -> grant(session, anna, GameJokerRepository.HUNTER_POSITIONS, -3));
        grant(session, anna, GameJokerRepository.HUNTER_POSITIONS, -1);
        assertEquals(1, inventory(anna, GameJokerRepository.HUNTER_POSITIONS).granted());
        assertStatus(400, () -> grant(session, anna, GameJokerRepository.SPEEDHUNT, 1));
        assertStatus(400, () -> grant(session, null, GameJokerRepository.BREAK, 1));
        assertStatus(400, () -> grant(session, hunter, GameJokerRepository.HUNTER_POSITIONS, 1));
        assertStatus(400, () -> grant(session, anna, "teleport", 1));
        assertStatus(400, () -> grant(session, anna, GameJokerRepository.HUNTER_POSITIONS, 0));
        assertStatus(400, () -> grant(session(), anna, GameJokerRepository.HUNTER_POSITIONS, 1));

        var request = submit(anna, GameRequest.HUNTER_POSITIONS, null);
        assertEquals(GameRequest.PENDING, request.status());
        assertTrue(request.approvalRequired());
        var held = inventory(anna, GameJokerRepository.HUNTER_POSITIONS);
        assertEquals(List.of(1, 0, 1, 0), List.of(held.granted(), held.used(), held.reserved(), held.available()));
        assertStatus(409, () -> submit(anna, GameRequest.HUNTER_POSITIONS, null));
        assertEquals("No joker of this type is available", message(() -> submit(ben, GameRequest.HUNTER_POSITIONS,
                null)));
        // A revocation never takes a reserved joker.
        assertStatus(409, () -> grant(session, anna, GameJokerRepository.HUNTER_POSITIONS, -1));

        succeed(session, request.id());
        var used = inventory(anna, GameJokerRepository.HUNTER_POSITIONS);
        assertEquals(List.of(1, 1, 0, 0), List.of(used.granted(), used.used(), used.reserved(), used.available()));

        grant(session, anna, GameJokerRepository.HUNTER_POSITIONS, 1);
        long rejected = submit(anna, GameRequest.HUNTER_POSITIONS, null).id();
        requests.decide(session, rejected, "reject", "No", null);
        long cancelled = submit(anna, GameRequest.HUNTER_POSITIONS, null).id();
        assertStatus(403, () -> requests.cancel(ben, cancelled));
        assertEquals(GameRequest.CANCELLED, requests.cancel(anna, cancelled).status());
        assertStatus(409, () -> requests.cancel(anna, cancelled));
        assertEquals(1, inventory(anna, GameJokerRepository.HUNTER_POSITIONS).available());

        assertEquals(List.of("release", "reserve", "release", "reserve", "grant", "use", "reserve", "revoke",
                "grant"), jokers.ledger(session, 0).stream().map(GameJokerService.LedgerEntry::kind).toList());
        assertReconciled(session);
        // Joker invalidations reach only the owner; nobody else learns when a joker changed.
        assertTrue(publisher.topic(GamePublisher.JOKERS).stream().allMatch(item -> item.members().equals(
                Set.of(anna.id()))));
    }

    @Test
    void requestsAreAuthorizedFromMembershipAndRefusedWhileNoToolRunsTheGame() throws Exception {
        long session = session(SETTINGS);
        GameMember anna = bound(session, "player");
        GameMember hunter = bound(session, "hunter");
        GameMember spectator = bound(session, "spectator");
        GameMember foreign = bound(session(), "player");
        grant(session, anna, GameJokerRepository.HUNTER_POSITIONS, 1);

        assertEquals(GameRequestService.NOT_RUNNING, message(() -> submit(anna, GameRequest.HUNTER_POSITIONS,
                null)));
        lease(session(), EPOCH);
        assertStatus(409, () -> hunt(hunter, GameRequest.STATIC_PING, anna));
        lease(session, EPOCH);

        assertStatus(400, () -> submit(anna, "jokerTeleport", null));
        assertStatus(403, () -> hunt(anna, GameRequest.STATIC_PING, anna));
        assertStatus(403, () -> submit(hunter, GameRequest.HUNTER_POSITIONS, null));
        assertStatus(403, () -> submit(spectator, GameRequest.HUNTER_POSITIONS, null));
        assertStatus(403, () -> hunt(spectator, GameRequest.STATIC_PING, anna));
        assertStatus(400, () -> requests.submit(hunter, new GameRequestService.Submission(GameRequest.STATIC_PING,
                anna.id(), null, null)));
        assertStatus(400, () -> hunt(hunter, GameRequest.STATIC_PING, hunter));
        assertStatus(400, () -> requests.submit(hunter, new GameRequestService.Submission(GameRequest.STATIC_PING,
                foreign.id(), 1L, null)));
        long revision = members.get(session, anna.id()).stateRevision();
        assertEquals(GameRequestService.STALE, message(() -> requests.submit(hunter,
                new GameRequestService.Submission(GameRequest.TERMINATE, anna.id(), revision - 1, null))));

        // A member without a tracker uses no jokers.
        GameMember unbound = members.bindAccount(session, plan(session, "player").id(), user());
        assertStatus(403, () -> submit(unbound, GameRequest.HUNTER_POSITIONS, null));
        // A retired member's account takes part in nothing any more.
        GameMember retired = bound(session, "supporter");
        members.retire(session, retired.id());
        assertStatus(403, () -> submit(retired, GameRequest.HUNTER_POSITIONS, null));

        apply(session, new GameOperation.Catch(anna.id(), null));
        assertEquals("You are out of the game", message(() -> submit(anna, GameRequest.HUNTER_POSITIONS, null)));
        assertEquals("This participant is out of the game", message(() -> hunt(hunter, GameRequest.TERMINATE, anna)));
    }

    @Test
    void requestsMoveThroughDecisionClaimExpiryAndChangeOfControl() throws Exception {
        long session = running("{\"request_approvals\": {\"speedhunt_terminate\": false},"
                + " \"fixed_speedhunt_live_duration_minutes\": 2}");
        GameMember anna = bound(session, "player");
        GameMember ben = bound(session, "supporter");
        GameMember hunter = bound(session, "hunter");
        GameMember other = bound(session, "hunter");
        GameMember spectator = bound(session, "spectator");
        grant(session, null, GameJokerRepository.SPEEDHUNT, 5);
        grant(session, anna, GameJokerRepository.HUNTER_POSITIONS, 1);

        var terminate = hunt(hunter, GameRequest.TERMINATE, anna);
        assertFalse(terminate.approvalRequired());
        assertEquals("Player 1", terminate.targetLabel());
        assertNull(terminate.message());
        assertStatus(409, () -> hunt(other, GameRequest.STATIC_PING, anna));
        var ping = hunt(other, GameRequest.STATIC_PING, ben);
        assertTrue(ping.approvalRequired());
        assertEquals(1, pool(session).reserved());
        var joker = submit(anna, GameRequest.HUNTER_POSITIONS, null);

        // Hunters and spectators share speedhunt requests; players see only their own.
        assertEquals(List.of(ping.id(), terminate.id()), requests.list(hunter, 0).stream().map(GameRequest::id)
                .toList());
        assertEquals(List.of(ping.id(), terminate.id()), requests.list(spectator, 0).stream()
                .map(GameRequest::id).toList());
        assertEquals(List.of(joker.id()), requests.list(anna, 0).stream().map(GameRequest::id).toList());
        assertTrue(requests.list(ben, 0).isEmpty());
        assertNull(requests.list(anna, 0).getFirst().detail());

        assertEquals(Boolean.TRUE, requests.decide(session, terminate.id(), "await", "Waiting", null)
                .approvalRequired());
        assertEquals(GameRequest.APPROVED, requests.decide(session, terminate.id(), "approve", null, null).status());
        assertStatus(409, () -> requests.decide(session, terminate.id(), "await", null, null));
        assertStatus(409, () -> requests.decide(session, terminate.id(), "approve", null, null));
        assertStatus(400, () -> requests.decide(session, terminate.id(), "maybe", null, null));
        assertEquals(GameRequest.EXECUTING, requests.claim(session, terminate.id(), EPOCH).status());
        assertStatus(409, () -> requests.claim(session, terminate.id(), EPOCH));
        assertStatus(409, () -> deliver(session, List.of(new GameRequestService.Completion(terminate.id(),
                GameRequest.REJECTED, null, null))));
        deliver(session, List.of(new GameRequestService.Completion(terminate.id(), GameRequest.SUCCEEDED, null,
                null)));
        deliver(session, List.of(new GameRequestService.Completion(terminate.id(), GameRequest.SUCCEEDED, null,
                null)));
        assertStatus(404, () -> requests.claim(session, -1, EPOCH));

        // Requests awaiting approval or execution expire after two minutes; executing ones fail on a change of
        // control. Their jokers return either way.
        requests.claim(session, ping.id(), EPOCH);
        clock.advance(Duration.ofSeconds(GameRequestService.EXPIRY_SECONDS + 1));
        assertTrue(requests.expire().contains(session));
        assertEquals(GameRequest.EXPIRED, requests.operatorList(session, false, 0).stream()
                .filter(item -> item.id() == joker.id()).findFirst().orElseThrow().status());
        assertEquals(1, inventory(anna, GameJokerRepository.HUNTER_POSITIONS).available());
        assertEquals(List.of(ping.id()), requests.operatorList(session, true, 0).stream().map(GameRequest::id)
                .toList());
        assertTrue(requests.invalidateOpen("Control changed").contains(session));
        var failed = requests.operatorList(session, false, 0).stream().filter(item -> item.id() == ping.id())
                .findFirst().orElseThrow();
        assertEquals(GameRequest.FAILED, failed.status());
        assertEquals("Control changed", failed.message());
        assertEquals(0, pool(session).reserved());
        assertTrue(requests.operatorList(session, true, 0).isEmpty());
        assertReconciled(session);

        // A request claimed after its deadline expires instead.
        clock.set(T0);
        long late = hunt(hunter, GameRequest.STOP_LIVE_PING, anna).id();
        clock.advance(Duration.ofSeconds(GameRequestService.EXPIRY_SECONDS));
        assertEquals(GameRequest.EXPIRED, requests.claim(session, late, EPOCH).status());
    }

    // The hunters' speedhunt pool ---------------------------------------------------------------------------

    @Test
    void speedhuntJokersComeFromTheHuntersPoolAndAreUsedWhenARunStarts() throws Exception {
        long session = running(SETTINGS);
        GameMember anna = bound(session, "player");
        GameMember ben = bound(session, "player");
        GameMember hunter = bound(session, "hunter");
        publisher.clear();

        assertEquals(GameRequestService.NO_SPEEDHUNT_JOKER,
                message(() -> hunt(hunter, GameRequest.STATIC_PING, anna)));
        grant(session, null, GameJokerRepository.SPEEDHUNT, 1);
        assertEquals(Set.of(hunter.id()), publisher.topic(GamePublisher.JOKERS).getLast().members());
        var request = hunt(hunter, GameRequest.LIVE_PING, anna);
        assertEquals(GameRequestService.NO_SPEEDHUNT_JOKER, message(() -> hunt(hunter, GameRequest.STATIC_PING,
                ben)));
        succeed(session, request.id());
        assertEquals(1, pool(session).reserved());
        assertTrue(jokers.pool(session).reservations().getFirst().armed());

        // The live ping's first upload reached nobody: the armed pool joker returns.
        deliver(session, List.of(), new GameOperation.PingFailed(anna.id(), null));
        assertEquals(List.of(1, 0, 0), List.of(pool(session).granted(), pool(session).used(),
                pool(session).reserved()));

        request = hunt(hunter, GameRequest.STATIC_PING, anna);
        succeed(session, request.id(), ping(anna, 1, GameState.REAL));
        var run = state.runs(session).getFirst().run();
        assertEquals(List.of(1, 1, 0, 0), List.of(pool(session).granted(), pool(session).used(),
                pool(session).reserved(), pool(session).overAllocated()));
        assertNotNull(run.reservationId());
        assertEquals(request.id(), run.requestId());
        // Pings of a running speedhunt need no joker.
        var next = hunt(hunter, GameRequest.STATIC_PING, anna);
        assertEquals(0, pool(session).reserved());
        succeed(session, next.id(), ping(anna, 2, GameState.REAL));
        assertStatus(409, () -> hunt(hunter, GameRequest.LIVE_PING, ben));
        assertReconciled(session);

        long noLive = running("{}");
        GameMember carla = bound(noLive, "player");
        GameMember hugo = bound(noLive, "hunter");
        grant(noLive, null, GameJokerRepository.SPEEDHUNT, 1);
        assertEquals(GameRequestService.LIVE_DURATION_REQUIRED, message(() -> hunt(hugo, GameRequest.LIVE_PING,
                carla)));
    }

    @Test
    void repeatTargetsNeedAcknowledgmentAndApprovalForThatPingType() throws Exception {
        for (String settings : List.of("{\"limit_enforcement\": \"block\"}",
                "{\"limit_enforcement\": \"warn\", \"request_approvals\": {\"speedhunt_static_ping\": false}}")) {
            long session = running(settings);
            GameMember anna = bound(session, "player");
            GameMember hunter = bound(session, "hunter");
            grant(session, null, GameJokerRepository.SPEEDHUNT, 5);
            apply(session, ping(anna, 1, GameState.REAL), new GameOperation.RunEnded(anna.id(),
                    GameState.TERMINATED, null));
            assertEquals(GameRequestService.REPEAT_SPEEDHUNT, message(() -> hunt(hunter, GameRequest.STATIC_PING,
                    anna, "{\"confirmConsecutive\": true}")));
        }
        long session = running("{\"limit_enforcement\": \"warn\"}");
        GameMember anna = bound(session, "player");
        GameMember ben = bound(session, "player");
        GameMember hunter = bound(session, "hunter");
        grant(session, null, GameJokerRepository.SPEEDHUNT, 5);
        apply(session, ping(anna, 1, GameState.REAL), new GameOperation.RunEnded(anna.id(), GameState.TERMINATED,
                null));
        assertTrue(message(() -> hunt(hunter, GameRequest.STATIC_PING, anna)).contains("confirm"));
        assertStatus(400, () -> hunt(hunter, GameRequest.STATIC_PING, anna, "{\"confirmConsecutive\": \"yes\"}"));
        assertTrue(hunt(hunter, GameRequest.STATIC_PING, anna, "{\"confirmConsecutive\": true}")
                .approvalRequired());
        // Another participant is no repeat.
        assertNotNull(hunt(hunter, GameRequest.STATIC_PING, ben));

        long allowed = running("{\"allow_consecutive_speedhunts\": true}");
        GameMember carla = bound(allowed, "player");
        GameMember hugo = bound(allowed, "hunter");
        grant(allowed, null, GameJokerRepository.SPEEDHUNT, 5);
        apply(allowed, ping(carla, 1, GameState.REAL), new GameOperation.RunEnded(carla.id(), GameState.TERMINATED,
                null));
        assertNotNull(hunt(hugo, GameRequest.STATIC_PING, carla));
    }

    // Breaks ------------------------------------------------------------------------------------------------

    @Test
    void breaksNeedAConfiguredDurationAndNeverRevealASpeedhuntTarget() throws Exception {
        long unset = running("{}");
        GameMember dora = bound(unset, "player");
        assertStatus(409, () -> grant(unset, dora, GameJokerRepository.BREAK, 1));

        long session = running(SETTINGS);
        GameMember anna = bound(session, "player");
        GameMember ben = bound(session, "supporter");
        GameMember carla = bound(session, "player");
        grant(session, anna, GameJokerRepository.BREAK, 2);
        grant(session, ben, GameJokerRepository.BREAK, 2);
        grant(session, carla, GameJokerRepository.BREAK, 1);
        execute("UPDATE tc_game_session_settings SET settings = '{}' WHERE gamesessionid = ?", session);
        assertEquals("Breaks are not set up for this game", message(() -> submit(anna, GameRequest.BREAK, null)));
        execute("UPDATE tc_game_session_settings SET settings = ? WHERE gamesessionid = ?",
                "{\"game_settings\": " + SETTINGS + "}", session);

        // While any speedhunt runs, the target and everybody else get the same answer.
        long upload = apply(session, new GameOperation.LiveUploadStarted(GameState.SPEEDHUNT_PING, anna.id(), null,
                true, true, true, 30, null, null, null, null, null)).results().getFirst().id();
        assertEquals(GameRequestService.BREAK_UNAVAILABLE, message(() -> submit(anna, GameRequest.BREAK, null)));
        assertEquals(GameRequestService.BREAK_UNAVAILABLE, message(() -> submit(ben, GameRequest.BREAK, null)));
        apply(session, new GameOperation.LiveUploadEnded(upload, null), ping(anna, 1, GameState.REAL));
        assertEquals(GameRequestService.BREAK_UNAVAILABLE, message(() -> submit(anna, GameRequest.BREAK, null)));
        assertEquals(GameRequestService.BREAK_UNAVAILABLE, message(() -> submit(ben, GameRequest.BREAK, null)));
        // A planned break may be requested meanwhile: it waits at its start while a speedhunt runs.
        var planned = submit(ben, GameRequest.BREAK, "{\"startsAt\": \"" + clock.instant().plusSeconds(3600)
                + "\"}");
        assertEquals(GameRequestService.BREAK_OVERLAP, message(() -> submit(ben, GameRequest.BREAK,
                "{\"startsAt\": \"" + clock.instant().plusSeconds(3700) + "\"}")));
        apply(session, new GameOperation.RunEnded(anna.id(), GameState.TERMINATED, null));

        // A break that starts now is used with its request's outcome and published with it.
        var now = submit(carla, GameRequest.BREAK, null);
        long revision = memberRevision(carla.id());
        succeed(session, now.id(), new GameOperation.BreakStarted(null, now.id(), null));
        assertEquals(revision + 1, memberRevision(carla.id()));
        var active = state.snapshot(session).breaks().stream().filter(GameState.Break::active).findFirst()
                .orElseThrow();
        assertEquals(carla.id(), active.memberId());
        assertEquals(clock.instant().plusSeconds(600), active.deadline());
        assertEquals(1, inventory(carla, GameJokerRepository.BREAK).used());
        grant(session, carla, GameJokerRepository.BREAK, 1);
        assertEquals("You are already on a break", message(() -> submit(carla, GameRequest.BREAK, null)));
        assertStatus(409, () -> deliver(session, List.of(), new GameOperation.BreakStarted(null, now.id(), null)));
        GameMember hunter = bound(session, "hunter");
        // Anna's speedhunt used one pool joker beyond the allocation.
        grant(session, null, GameJokerRepository.SPEEDHUNT, 2);
        assertEquals("This participant is on a break", message(() -> hunt(hunter, GameRequest.STATIC_PING, carla)));
        // The deadline ends the protection by itself.
        clock.advance(Duration.ofSeconds(601));
        assertNotNull(hunt(hunter, GameRequest.STATIC_PING, carla));
        assertEquals(GameRequest.PENDING, planned.status());
        assertReconciled(session);
    }

    @Test
    void plannedBreaksAreArmedStartedOrReleasedAndNeverOverlap() throws Exception {
        long session = running(SETTINGS);
        GameMember anna = bound(session, "player");
        GameMember ben = bound(session, "player");
        GameMember hunter = bound(session, "hunter");
        grant(session, anna, GameJokerRepository.BREAK, 3);
        Instant start = clock.instant().plusSeconds(3600);
        long revision = memberRevision(anna.id());
        publisher.clear();

        var request = submit(anna, GameRequest.BREAK, "{\"startsAt\": \"" + start + "\"}");
        assertEquals(start.toString(), request.parameters().get("startsAt").asText());
        succeed(session, request.id());
        var own = jokers.own(anna).reservations();
        assertEquals(1, own.size());
        assertTrue(own.getFirst().armed());
        assertEquals(GameJokerService.USE_PLANNED_BREAK, own.getFirst().use());
        assertEquals(start, own.getFirst().plannedBreak().startsAt());
        assertEquals(GameState.BY_PARTICIPANT, own.getFirst().plannedBreak().plannedBy());
        // Hunters do not see planned breaks: no revision change, no status announcement.
        assertEquals(revision, memberRevision(anna.id()));
        assertTrue(publisher.topic(GamePublisher.STATUS).isEmpty());

        assertEquals(GameRequestService.BREAK_OVERLAP, message(() -> submit(anna, GameRequest.BREAK,
                "{\"startsAt\": \"" + start.plusSeconds(300) + "\"}")));
        assertStatus(409, () -> jokers.planBreak(session, anna.id(), start.minusSeconds(300).toString(), false,
                "Operator"));
        // Another owner's reservation answers like a missing one.
        long reservation = own.getFirst().id();
        assertStatus(404, () -> jokers.cancel(ben, reservation));
        assertStatus(404, () -> jokers.cancel(hunter, reservation));
        assertEquals(GameJokerRepository.RELEASED, jokers.cancel(anna, reservation).status());
        assertStatus(409, () -> jokers.cancel(anna, reservation));
        assertEquals(GameState.CANCELLED, state.breaks(session).getFirst().state());

        // An operator plans one beyond the allocation only with a confirmation.
        assertStatus(409, () -> jokers.planBreak(session, ben.id(), null, false, "Operator"));
        var operatorPlan = jokers.planBreak(session, ben.id(), start.toString(), true, "Operator");
        assertEquals(GameState.BY_OPERATOR, operatorPlan.plannedBreak().plannedBy());
        assertTrue(operatorPlan.overAllocated());
        assertStatus(400, () -> jokers.planBreak(session, hunter.id(), null, true, "Operator"));
        // A plan whose start passed starts at once, with its full duration.
        clock.set(start.plusSeconds(5));
        long breakId = operatorPlan.plannedBreak().breakId();
        apply(session, new GameOperation.BreakStarted(breakId, null, null));
        var started = state.breaks(session).stream().filter(item -> item.id() == breakId).findFirst()
                .orElseThrow();
        assertEquals(start.plusSeconds(605), started.deadline());
        assertEquals(1, inventory(ben, GameJokerRepository.BREAK).overAllocated());
        assertStatus(409, () -> jokers.cancel(ben, operatorPlan.id()));

        // A caught participant's planned break is released.
        var second = submit(anna, GameRequest.BREAK, "{\"startsAt\": \"" + start.plusSeconds(7200) + "\"}");
        succeed(session, second.id());
        apply(session, new GameOperation.Catch(anna.id(), null));
        assertEquals(GameState.CANCELLED, state.breaks(session).getLast().state());
        assertEquals(3, inventory(anna, GameJokerRepository.BREAK).available());
        assertReconciled(session);
    }

    // Timetable fakes ---------------------------------------------------------------------------------------

    @Test
    void timetableFakesAreArmedUsedReleasedAndStayPrivate() throws Exception {
        long session = running(SETTINGS);
        GameMember anna = bound(session, "player");
        GameMember ben = bound(session, "player");
        GameMember hunter = bound(session, "hunter");
        Instant now = clock.instant();
        timetables.publish(session, 0, json("[\"Time\",\"" + anna.displayName() + "\",\"" + ben.displayName()
                + "\"]"), null, List.of(
                        new GameTimetableService.EntryInput(now.plusSeconds(600).toString(), anna.displayName(), ""),
                        new GameTimetableService.EntryInput(now.plusSeconds(600).toString(), ben.displayName(), ""),
                        new GameTimetableService.EntryInput(now.plusSeconds(700).toString(), anna.displayName(), "b"),
                        new GameTimetableService.EntryInput(now.plusSeconds(30).toString(), anna.displayName(), "2H"),
                        new GameTimetableService.EntryInput(now.plusSeconds(900).toString(), anna.displayName(),
                                "2P2S2H")));
        var entries = timetables.get(session).entries();
        long annaPing = entry(entries, anna, now.plusSeconds(600));
        long benPing = entry(entries, ben, now.plusSeconds(600));
        long breakCell = entry(entries, anna, now.plusSeconds(700));
        long tooClose = entry(entries, anna, now.plusSeconds(30));
        long later = entry(entries, anna, now.plusSeconds(900));
        grant(session, anna, GameJokerRepository.FAKE_PING, 3);
        grant(session, ben, GameJokerRepository.FAKE_PING, 1);
        publisher.clear();

        assertStatus(400, () -> submit(anna, GameRequest.FAKE_PING, fake(benPing)));
        assertStatus(400, () -> submit(anna, GameRequest.FAKE_PING, "{\"entryId\": " + annaPing
                + ", \"pingNumber\": 1, \"latitude\": 47, \"longitude\": 8}"));
        assertStatus(400, () -> submit(anna, GameRequest.FAKE_PING, "{\"entryId\": " + annaPing
                + ", \"latitude\": 91, \"longitude\": 8}"));
        assertTrue(message(() -> submit(anna, GameRequest.FAKE_PING, fake(breakCell))).contains("does not reach"));
        assertTrue(message(() -> submit(anna, GameRequest.FAKE_PING, fake(tooClose))).contains("too close"));
        var request = submit(anna, GameRequest.FAKE_PING, fake(annaPing));
        assertEquals(now.plusSeconds(600).toString(), request.parameters().get("scheduledAt").asText());
        assertEquals(List.of(true, false), jokers.own(anna).timetableEntries().stream()
                .map(GameJokerService.FakeEntry::taken).toList());
        succeed(session, request.id());
        var armed = jokers.own(anna).reservations().getFirst();
        assertTrue(armed.armed());
        assertEquals(GameJokerService.USE_TIMETABLE, armed.use());
        assertEquals(47.1, armed.fake().latitude());
        assertEquals(now.plusSeconds(600), armed.fake().scheduledAt());
        assertEquals("This ping already has a fake", message(() -> submit(anna, GameRequest.FAKE_PING,
                fake(annaPing))));

        // The ping reached the hunters with the fake: the joker is used. A second report is skipped.
        deliver(session, List.of(), new GameOperation.FakeUsed(armed.id()));
        assertEquals(1, inventory(anna, GameJokerRepository.FAKE_PING).used());
        deliver(session, List.of(), new GameOperation.FakeReleased(armed.id(), "late"));
        assertEquals(1, inventory(anna, GameJokerRepository.FAKE_PING).used());
        assertEquals(0, inventory(anna, GameJokerRepository.FAKE_PING).reserved());

        // A republished timetable releases fakes on pings that disappeared or no longer reach hunters.
        long second = submit(anna, GameRequest.FAKE_PING, fake(later)).id();
        succeed(session, second);
        publisher.clear();
        timetables.publish(session, 1, json("[\"Time\",\"" + anna.displayName() + "\"]"), null, List.of(
                new GameTimetableService.EntryInput(now.plusSeconds(900).toString(), anna.displayName(), "b")));
        assertEquals(0, inventory(anna, GameJokerRepository.FAKE_PING).reserved());
        assertEquals(Set.of(anna.id()), publisher.topic(GamePublisher.JOKERS).getFirst().members());

        // Hunters never see joker requests or plans, and fakes leave no trace in their views.
        assertTrue(requests.list(hunter, 0).isEmpty());
        assertTrue(publisher.invalidations.stream().noneMatch(item -> GamePublisher.JOKERS.equals(item.topic())
                && item.members() != null && item.members().contains(hunter.id())));
        assertStatus(403, () -> jokers.own(hunter));
        assertReconciled(session);
    }

    @Test
    void aFakeIsNotArmedWhenItsPingChangedMeanwhileAndABreakReleasesFakesInsideIt() throws Exception {
        long session = running(SETTINGS);
        GameMember anna = bound(session, "player");
        Instant now = clock.instant();
        String columns = "[\"Time\",\"" + anna.displayName() + "\"]";
        timetables.publish(session, 0, json(columns), null, List.of(
                new GameTimetableService.EntryInput(now.plusSeconds(300).toString(), anna.displayName(), ""),
                new GameTimetableService.EntryInput(now.plusSeconds(3600).toString(), anna.displayName(), "")));
        var entries = timetables.get(session).entries();
        grant(session, anna, GameJokerRepository.FAKE_PING, 2);
        grant(session, anna, GameJokerRepository.BREAK, 1);

        long request = submit(anna, GameRequest.FAKE_PING, fake(entries.get(1).id())).id();
        timetables.publish(session, 1, json(columns), null, List.of(
                new GameTimetableService.EntryInput(now.plusSeconds(300).toString(), anna.displayName(), ""),
                new GameTimetableService.EntryInput(now.plusSeconds(3600).toString(), anna.displayName(), "x")));
        requests.claim(session, request, EPOCH);
        assertStatus(400, () -> deliver(session, List.of(new GameRequestService.Completion(request,
                GameRequest.SUCCEEDED, null, null))));
        deliver(session, List.of(new GameRequestService.Completion(request, GameRequest.FAILED, "Changed", null)));
        assertEquals(0, inventory(anna, GameJokerRepository.FAKE_PING).reserved());

        long armed = submit(anna, GameRequest.FAKE_PING, fake(entries.get(0).id())).id();
        succeed(session, armed);
        var breakRequest = submit(anna, GameRequest.BREAK, null);
        succeed(session, breakRequest.id(), new GameOperation.BreakStarted(null, breakRequest.id(), null));
        assertEquals(0, inventory(anna, GameJokerRepository.FAKE_PING).reserved());
        assertEquals(0, inventory(anna, GameJokerRepository.FAKE_PING).used());
        assertTrue(message(() -> submit(anna, GameRequest.FAKE_PING, "{\"pingNumber\": 9, \"latitude\": 1,"
                + " \"longitude\": 2}")).contains("4 pings"));
        assertReconciled(session);
    }

    private static long entry(List<GameTimetableService.Entry> entries, GameMember member, Instant time) {
        return entries.stream().filter(item -> item.memberId() == member.id() && item.scheduledAt().equals(time))
                .findFirst().orElseThrow().id();
    }

    private static String fake(long entryId) {
        return "{\"entryId\": " + entryId + ", \"latitude\": 47.1, \"longitude\": 8.5}";
    }

    private static String nextSpeedhunt(int number, double latitude) {
        return "{\"pingNumber\": " + number + ", \"latitude\": " + latitude + ", \"longitude\": 8.5}";
    }

    // Speedhunt fakes ---------------------------------------------------------------------------------------

    @Test
    void speedhuntFakesBindToTheNextRunForEveryOwnerAndAreNeverRefunded() throws Exception {
        long session = running(SETTINGS);
        GameMember anna = bound(session, "player");
        GameMember ben = bound(session, "supporter");
        GameMember carla = bound(session, "player");
        grant(session, anna, GameJokerRepository.FAKE_PING, 2);
        grant(session, ben, GameJokerRepository.FAKE_PING, 2);
        grant(session, carla, GameJokerRepository.FAKE_PING, 1);

        succeed(session, submit(anna, GameRequest.FAKE_PING, nextSpeedhunt(2, 47.3)).id());
        succeed(session, submit(ben, GameRequest.FAKE_PING, nextSpeedhunt(1, 47.4)).id());
        assertEquals("You already have a fake for the next speedhunt", message(() -> submit(anna,
                GameRequest.FAKE_PING, nextSpeedhunt(3, 47.0))));
        assertStatus(409, () -> submit(anna, GameRequest.FAKE_PING, nextSpeedhunt(5, 47.0)));
        assertEquals(GameJokerService.USE_SPEEDHUNT, jokers.own(anna).reservations().getFirst().use());
        long open = submit(carla, GameRequest.FAKE_PING, nextSpeedhunt(1, 47.5)).id();
        publisher.clear();

        // The first ping on Anna binds every armed speedhunt fake to her run; Carla's is still a request.
        var first = apply(session, ping(anna, 1, GameState.REAL));
        assertEquals(List.of(GameOperations.BOUND, GameOperations.BOUND), first.fakes().stream()
                .map(GameOperations.FakeOutcome::status).toList());
        assertEquals(GameState.REAL, first.results().getFirst().source());
        for (GameMember owner : List.of(anna, ben)) {
            var inventory = inventory(owner, GameJokerRepository.FAKE_PING);
            assertEquals(List.of(1, 0), List.of(inventory.used(), inventory.reserved()), owner.label());
            var bound = jokers.own(owner).reservations().getFirst();
            assertTrue(bound.fake().bound());
            assertNull(bound.fake().boundRunId());
            assertStatus(409, () -> jokers.cancel(owner, bound.id()));
        }
        var jokerAnnouncement = publisher.topic(GamePublisher.JOKERS).getFirst().members();
        assertTrue(jokerAnnouncement.containsAll(Set.of(anna.id(), ben.id())));
        assertFalse(jokerAnnouncement.contains(carla.id()));
        long run = state.runs(session).getFirst().run().id();
        assertEquals(List.of(run, run), jokers.overview(session).boundFakes().stream()
                .map(item -> item.fake().boundRunId()).toList());

        // Only the target's own fake applies, at its ping; the source stays operator data.
        var second = apply(session, ping(anna, 2, GameState.REAL));
        assertEquals(GameState.JOKER_FAKE, second.results().getFirst().source());
        assertEquals(GameState.JOKER_FAKE, state.runs(session).getFirst().pings().get(1).source());
        assertNotNull(state.runs(session).getFirst().pings().get(1).fakePlanId());
        // An operator fake on the same ping wins.
        apply(session, new GameOperation.RunEnded(anna.id(), GameState.TERMINATED, null));
        succeed(session, open);
        succeed(session, submit(anna, GameRequest.FAKE_PING, nextSpeedhunt(1, 47.6)).id());
        var operator = apply(session, ping(anna, 1, GameState.OPERATOR_FAKE));
        assertEquals(GameState.OPERATOR_FAKE, operator.results().getFirst().source());
        assertEquals(2, inventory(anna, GameJokerRepository.FAKE_PING).used());
        assertEquals(1, inventory(carla, GameJokerRepository.FAKE_PING).used());
        // A run that ends before the selected ping refunds nothing.
        apply(session, new GameOperation.RunEnded(anna.id(), GameState.COMPLETED, null));
        assertEquals(2, inventory(anna, GameJokerRepository.FAKE_PING).used());
        assertReconciled(session);
    }

    @Test
    void fakesOnARunningSpeedhuntAreUsedWithTheirPingForEveryOwner() throws Exception {
        long session = running(SETTINGS);
        GameMember anna = bound(session, "player");
        GameMember ben = bound(session, "player");
        GameMember carla = bound(session, "supporter");
        grant(session, anna, GameJokerRepository.FAKE_PING, 2);
        grant(session, ben, GameJokerRepository.FAKE_PING, 2);
        grant(session, carla, GameJokerRepository.FAKE_PING, 1);
        apply(session, ping(anna, 1, GameState.REAL));
        var windows = transactions.read(transaction -> GameRequestService.runningWindows(transaction, session));
        assertEquals(1, windows.size());
        assertEquals(2, windows.getFirst().firstPing());
        assertEquals(4, windows.getFirst().lastPing());
        String token = windows.getFirst().runToken();
        assertTrue(token.matches("[a-f0-9]{32}"));

        succeed(session, submit(anna, GameRequest.FAKE_PING, running(token, 3, 47.3)).id());
        succeed(session, submit(ben, GameRequest.FAKE_PING, running(token, 3, 47.4)).id());
        assertEquals("You already have a fake for this speedhunt", message(() -> submit(ben, GameRequest.FAKE_PING,
                running(token, 4, 47.4))));
        assertTrue(message(() -> submit(ben, GameRequest.FAKE_PING, running(token, 1, 47.4)))
                .contains("no longer available"));
        assertTrue(message(() -> submit(ben, GameRequest.FAKE_PING, running("a".repeat(32), 3, 47.4)))
                .contains("no longer available"));
        assertEquals(GameJokerService.USE_RUNNING_SPEEDHUNT, jokers.own(ben).reservations().getFirst().use());

        apply(session, ping(anna, 2, GameState.REAL));
        long late = submit(carla, GameRequest.FAKE_PING, running(token, 4, 47.3)).id();
        var third = apply(session, ping(anna, 3, GameState.REAL));
        assertEquals(GameState.JOKER_FAKE, third.results().getFirst().source());
        assertEquals(List.of(GameJokerRepository.CONSUMED, GameJokerRepository.CONSUMED), third.fakes().stream()
                .map(GameOperations.FakeOutcome::status).toList());
        assertEquals(1, inventory(ben, GameJokerRepository.FAKE_PING).used());
        // A fake request whose ping passed meanwhile is not armed, and its joker returns.
        apply(session, ping(anna, 4, GameState.REAL));
        assertEquals(1, inventory(carla, GameJokerRepository.FAKE_PING).reserved());
        requests.claim(session, late, EPOCH);
        assertStatus(400, () -> deliver(session, List.of(new GameRequestService.Completion(late,
                GameRequest.SUCCEEDED, null, null))));
        deliver(session, List.of(new GameRequestService.Completion(late, GameRequest.FAILED, null, null)));
        assertEquals(0, inventory(carla, GameJokerRepository.FAKE_PING).reserved());
        // The run's end returns armed fakes on pings it did not reach, not fakes for the next speedhunt.
        succeed(session, submit(ben, GameRequest.FAKE_PING, nextSpeedhunt(2, 1)).id());
        assertEquals(1, inventory(ben, GameJokerRepository.FAKE_PING).reserved());
        apply(session, new GameOperation.RunEnded(anna.id(), GameState.COMPLETED, null));
        assertEquals(1, inventory(ben, GameJokerRepository.FAKE_PING).reserved());
        assertReconciled(session);
    }

    private static String running(String token, int number, double latitude) {
        return "{\"runToken\": \"" + token + "\", \"pingNumber\": " + number + ", \"latitude\": " + latitude
                + ", \"longitude\": 8.5}";
    }

    // Catches and bindings ----------------------------------------------------------------------------------

    @Test
    void aCatchEndsOpenRequestsArmedPlansAndTheBreak() throws Exception {
        long session = running(SETTINGS);
        GameMember anna = bound(session, "player");
        GameMember hunter = bound(session, "hunter");
        grant(session, anna, GameJokerRepository.HUNTER_POSITIONS, 1);
        grant(session, anna, GameJokerRepository.FAKE_PING, 2);
        grant(session, anna, GameJokerRepository.BREAK, 2);
        grant(session, null, GameJokerRepository.SPEEDHUNT, 1);

        var breakRequest = submit(anna, GameRequest.BREAK, null);
        succeed(session, breakRequest.id(), new GameOperation.BreakStarted(null, breakRequest.id(), null));
        succeed(session, submit(anna, GameRequest.FAKE_PING, nextSpeedhunt(1, 47)).id());
        succeed(session, submit(anna, GameRequest.BREAK, "{\"startsAt\": \""
                + clock.instant().plusSeconds(7200) + "\"}").id());
        var open = submit(anna, GameRequest.HUNTER_POSITIONS, null);
        var hunterRequest = hunt(hunter, GameRequest.TERMINATE, anna);

        apply(session, new GameOperation.Catch(anna.id(), "Hunter"));
        var cancelled = requests.operatorList(session, false, 0).stream().filter(item -> item.id() == open.id())
                .findFirst().orElseThrow();
        assertEquals(GameRequest.CANCELLED, cancelled.status());
        assertEquals("You are out of the game", cancelled.message());
        assertEquals(GameRequest.PENDING, requests.operatorList(session, false, 0).stream()
                .filter(item -> item.id() == hunterRequest.id()).findFirst().orElseThrow().status());
        assertEquals(List.of(GameState.ENDED, GameState.CANCELLED), state.breaks(session).stream()
                .map(GameState.Break::state).toList());
        assertTrue(jokers.own(anna).reservations().isEmpty());
        assertEquals(1, inventory(anna, GameJokerRepository.BREAK).used());
        assertEquals(0, inventory(anna, GameJokerRepository.FAKE_PING).used());
        assertEquals(1, inventory(anna, GameJokerRepository.HUNTER_POSITIONS).available());
        assertReconciled(session);
    }

    @Test
    void aBindingChangeInvalidatesTheMembersRequestsAndArmedPlans() throws Exception {
        long session = running(SETTINGS);
        GameMember anna = bound(session, "player");
        GameMember ben = bound(session, "player");
        GameMember hunter = bound(session, "hunter");
        grant(session, anna, GameJokerRepository.HUNTER_POSITIONS, 1);
        grant(session, anna, GameJokerRepository.FAKE_PING, 1);
        grant(session, anna, GameJokerRepository.BREAK, 1);
        grant(session, null, GameJokerRepository.SPEEDHUNT, 1);
        succeed(session, submit(anna, GameRequest.FAKE_PING, nextSpeedhunt(1, 47)).id());
        succeed(session, submit(anna, GameRequest.BREAK, "{\"startsAt\": \""
                + clock.instant().plusSeconds(7200) + "\"}").id());
        var open = submit(anna, GameRequest.HUNTER_POSITIONS, null);
        var hunting = hunt(hunter, GameRequest.STATIC_PING, ben);
        requests.claim(session, hunting.id(), EPOCH);
        publisher.clear();

        members.bindTracker(session, anna.id(), device());
        assertEquals(GameRequest.EXPIRED, requests.operatorList(session, false, 0).stream()
                .filter(item -> item.id() == open.id()).findFirst().orElseThrow().status());
        assertTrue(jokers.own(anna).reservations().isEmpty());
        assertEquals(GameState.CANCELLED, state.breaks(session).getFirst().state());
        assertEquals(Set.of(anna.id()), publisher.topic(GamePublisher.JOKERS).getFirst().members());

        // A hunter's new account fails the hunter's executing request; the pool joker returns.
        members.bindAccount(session, hunter.id(), user());
        var failed = requests.operatorList(session, false, 0).stream().filter(item -> item.id() == hunting.id())
                .findFirst().orElseThrow();
        assertEquals(GameRequest.FAILED, failed.status());
        assertEquals(GamePlanInvalidation.MESSAGE, failed.message());
        assertEquals(0, pool(session).reserved());
        assertReconciled(session);
    }

    // Mirror-only hunter access -----------------------------------------------------------------------------

    @Test
    void breakAndFakeJokersNeedMirrorOnlyHunterAccess() throws Exception {
        long session = running(SETTINGS);
        GameMember anna = bound(session, "player");
        GameMember hunter = bound(session, "hunter");
        GameMember spectator = bound(session, "spectator");
        int outer = group();
        int inner = group();
        execute("UPDATE tc_groups SET groupid = ? WHERE id = ?", outer, inner);

        execute("INSERT INTO tc_user_device (userid, deviceid) VALUES (?, ?)", hunter.userId(), anna.trackerId());
        execute("INSERT INTO tc_user_device (userid, deviceid) VALUES (?, ?)", spectator.userId(), anna.trackerId());
        assertStatus(409, () -> grant(session, anna, GameJokerRepository.BREAK, 1));
        assertStatus(409, () -> grant(session, anna, GameJokerRepository.FAKE_PING, 1));
        grant(session, anna, GameJokerRepository.HUNTER_POSITIONS, 1);
        assertStatus(409, () -> jokers.planBreak(session, anna.id(), null, true, "Operator"));
        execute("DELETE FROM tc_user_device WHERE userid = ?", hunter.userId());
        grant(session, anna, GameJokerRepository.BREAK, 1);

        var rule = new GameAccessRule(transactions);
        // Group inheritance counts at any depth; access a change does not add never blocks it.
        execute("UPDATE tc_devices SET groupid = ? WHERE id = ?", inner, anna.trackerId());
        assertStatus(422, () -> rule.check(new GameAccessRule.Change(List.of(), List.of(new long[] {
            hunter.userId(), outer}), java.util.Map.of(), java.util.Map.of())));
        rule.check(new GameAccessRule.Change(List.of(), List.of(new long[] {spectator.userId(), outer}),
                java.util.Map.of(), java.util.Map.of()));
        int other = group();
        execute("INSERT INTO tc_user_group (userid, groupid) VALUES (?, ?)", hunter.userId(), other);
        assertStatus(422, () -> rule.check(new GameAccessRule.Change(List.of(), List.of(), java.util.Map.of(),
                java.util.Map.of((long) outer, (long) other))));
        assertStatus(422, () -> rule.check(new GameAccessRule.Change(List.of(new long[] {hunter.userId(),
            anna.trackerId()}), List.of(), java.util.Map.of(), java.util.Map.of())));

        // Binding a tracker a hunter sees, or a hunter account that sees a tracker, is refused.
        int seen = device();
        execute("INSERT INTO tc_user_device (userid, deviceid) VALUES (?, ?)", hunter.userId(), seen);
        assertStatus(422, () -> members.bindTracker(session, anna.id(), seen));
        int watcher = user();
        execute("INSERT INTO tc_user_device (userid, deviceid) VALUES (?, ?)", watcher, anna.trackerId());
        assertStatus(422, () -> members.bindAccount(session, hunter.id(), watcher));
        assertEquals(anna.trackerId(), members.get(session, anna.id()).trackerId());
        // A session without break or fake jokers has no such rule.
        long free = running(SETTINGS);
        GameMember carla = bound(free, "player");
        members.bindAccount(free, plan(free, "hunter").id(), watcher);
        execute("INSERT INTO tc_user_device (userid, deviceid) VALUES (?, ?)", watcher, carla.trackerId());
        assertStatus(409, () -> grant(free, carla, GameJokerRepository.FAKE_PING, 1));
    }

}
