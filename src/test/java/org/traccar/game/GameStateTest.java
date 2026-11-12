package org.traccar.game;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 3b of game data model 2: catches as intervals, speedhunt runs and pings, breaks, live uploads, hunter live
 * shares and member state revisions, applied as typed operations. Runs on H2 ({@link GameStateH2Test}) and MySQL
 * ({@link GameStateMySqlTest}).
 */
public abstract class GameStateTest extends GameTestBase {

    private static final long EPOCH = 7;

    private GameStateService state;
    private GameJokerService jokers;

    @Override
    protected void createServices() {
        state = new GameStateService(transactions, mapper, publisher);
        jokers = new GameJokerService(transactions, publisher);
    }

    private GameStateService.Applied apply(long session, GameOperation... operations) throws Exception {
        return state.apply(session, EPOCH, List.of(operations));
    }

    private long applyId(long session, GameOperation operation) throws Exception {
        return apply(session, operation).results().getFirst().id();
    }

    private static GameOperation.PingDelivered ping(GameMember target, int number) {
        return new GameOperation.PingDelivered(target.id(), number, GameState.STATIC, GameState.REAL, true, true,
                true, null, null, "Operator", true);
    }

    private static GameOperation.PingDelivered livePing(GameMember target, int number, long upload) {
        return new GameOperation.PingDelivered(target.id(), number, GameState.LIVE, GameState.REAL, true, true, true,
                null, upload, "Operator", true);
    }

    private static GameOperation.LiveUploadStarted upload(String purpose, GameMember member, boolean toHunters,
                                                          String reason) {
        boolean relay = GameState.HUNTER_RELAY.equals(purpose);
        return new GameOperation.LiveUploadStarted(purpose, relay ? null : member.id(), relay ? member.id() : null,
                toHunters, true, true, 30, null, reason, null, null, null);
    }

    // Catches -----------------------------------------------------------------------------------------------

    @Test
    void catchesAreIntervalsAndEachAdvancesTheMemberRevision() throws Exception {
        long session = session();
        GameMember anna = bound(session, "player");
        GameMember hunter = bound(session, "hunter");
        GameMember foreign = plan(session(), "player");
        long revision = memberRevision(anna.id());
        publisher.clear();

        long first = applyId(session, new GameOperation.Catch(anna.id(), "Hunter Hugo"));
        assertEquals(revision + 1, memberRevision(anna.id()));
        assertEquals(1, publisher.topic(GamePublisher.STATUS).size());
        assertStatus(409, () -> apply(session, new GameOperation.Catch(anna.id(), null)));
        assertEquals(first, applyId(session, new GameOperation.Release(anna.id())));
        assertStatus(409, () -> apply(session, new GameOperation.Release(anna.id())));
        apply(session, new GameOperation.Catch(anna.id(), null));
        assertEquals(revision + 3, memberRevision(anna.id()));

        var catches = state.catches(session);
        assertEquals(2, catches.size());
        assertNotNull(catches.get(0).releasedAt());
        assertEquals("Hunter Hugo", catches.get(0).actor());
        assertTrue(catches.get(1).active());
        assertEquals(List.of(catches.get(1)), state.snapshot(session).catches());

        assertStatus(400, () -> apply(session, new GameOperation.Catch(hunter.id(), null)));
        assertStatus(400, () -> apply(session, new GameOperation.Catch(foreign.id(), null)));
        assertStatus(400, () -> apply(session, new GameOperation.Catch(-1, null)));
        GameMember retired = plan(session, "supporter");
        members.retire(session, retired.id());
        assertStatus(409, () -> apply(session, new GameOperation.Catch(retired.id(), null)));
        // A failed operation rolls back the whole list.
        GameMember ben = bound(session, "supporter");
        assertStatus(409, () -> apply(session, new GameOperation.Catch(ben.id(), null),
                new GameOperation.Catch(anna.id(), null)));
        assertTrue(state.snapshot(session).catches().stream().noneMatch(item -> item.memberId() == ben.id()));
    }

    // Speedhunts --------------------------------------------------------------------------------------------

    @Test
    void aRunStartsWithItsFirstDeliveredPingAndPingsAreConsecutive() throws Exception {
        long session = session();
        GameMember anna = bound(session, "player");
        GameMember ben = bound(session, "supporter");
        jokers.change(session, new GameJokerService.Change(null, GameJokerRepository.SPEEDHUNT, 1, null), "Operator");

        assertStatus(409, () -> apply(session, ping(anna, 2)));
        var failed = apply(session, new GameOperation.PingFailed(anna.id(), "No position"));
        assertNull(failed.results().getFirst().id());
        assertTrue(state.runs(session).isEmpty());

        long revision = memberRevision(anna.id());
        var first = apply(session, ping(anna, 1)).results().getFirst();
        long run = first.runId();
        assertEquals(1, first.number());
        assertEquals(GameState.REAL, first.source());
        assertEquals(revision + 1, memberRevision(anna.id()));
        assertEquals(1, state.runs(session).size());
        assertTrue(state.speedhuntRunning(session));
        assertEquals(anna.id(), state.lastSpeedhuntTarget(session));
        assertStatus(409, () -> apply(session, ping(anna, 1)));
        assertStatus(409, () -> apply(session, ping(anna, 3)));
        assertEquals(run, apply(session, ping(anna, 2)).results().getFirst().runId());
        assertEquals(revision + 2, memberRevision(anna.id()));

        // The pool is empty now: another speedhunt needs a use beyond the allocation.
        assertStatus(409, () -> apply(session, new GameOperation.PingDelivered(ben.id(), 1, GameState.STATIC,
                GameState.REAL, true, true, true, null, null, "Operator", false)));
        apply(session, new GameOperation.PingDelivered(ben.id(), 1, GameState.STATIC, GameState.OPERATOR_FAKE, true,
                false, true, null, null, "Operator", true));
        assertEquals(List.of(), jokers.reconcile(session));
        var pool = jokers.pool(session).inventories().getFirst();
        assertEquals(1, pool.granted());
        assertEquals(2, pool.used());
        assertEquals(1, pool.overAllocated());

        assertStatus(400, () -> apply(session, new GameOperation.RunEnded(anna.id(), "finished", null)));
        apply(session, new GameOperation.RunEnded(anna.id(), GameState.COMPLETED, "Ping limit"));
        assertStatus(409, () -> apply(session, new GameOperation.RunEnded(anna.id(), GameState.TERMINATED, null)));
        assertStatus(409, () -> apply(session, ping(anna, 3)));

        var history = state.runs(session);
        assertEquals(2, history.size());
        var annaRun = history.stream().filter(item -> item.run().targetMemberId() == anna.id()).findFirst()
                .orElseThrow();
        assertEquals(GameState.COMPLETED, annaRun.run().outcome());
        assertEquals(List.of(1, 2), annaRun.pings().stream().map(GameState.Ping::number).toList());
        var benRun = history.stream().filter(item -> item.run().targetMemberId() == ben.id()).findFirst()
                .orElseThrow();
        assertFalse(benRun.run().toPlayers());
        assertEquals(GameState.OPERATOR_FAKE, benRun.pings().getFirst().source());

        var statistics = state.statistics(session);
        assertEquals(List.of(new GameStateRepository.Statistics(anna.id(), 1, 2, 0, annaRun.run().startedAt()),
                new GameStateRepository.Statistics(ben.id(), 1, 1, 0, benRun.run().startedAt())), statistics);

        assertStatus(400, () -> apply(session, new GameOperation.PingDelivered(anna.id(), 1, "radio",
                GameState.REAL, true, true, true, null, null, null, true)));
        assertStatus(400, () -> apply(session, new GameOperation.PingDelivered(anna.id(), 1, GameState.STATIC,
                GameState.JOKER_FAKE, true, true, true, null, null, null, true)));
        assertStatus(400, () -> apply(session, new GameOperation.PingDelivered(anna.id(), 1, GameState.STATIC,
                GameState.REAL, null, true, true, null, null, null, true)));
        assertStatus(400, () -> apply(session, new GameOperation.PingDelivered(anna.id(), 1, GameState.STATIC,
                GameState.REAL, true, true, true, null, 5L, null, true)));
        apply(session, new GameOperation.Catch(anna.id(), null));
        assertStatus(409, () -> apply(session, ping(anna, 1)));
    }

    @Test
    void aLivePingIsOnePingAcrossItsSamples() throws Exception {
        long session = session();
        GameMember anna = bound(session, "player");
        long revision = memberRevision(anna.id());

        long upload = applyId(session, upload(GameState.SPEEDHUNT_PING, anna, true, null));
        assertEquals(revision + 1, memberRevision(anna.id()));
        // An active live ping counts as a running speedhunt before its first sample reaches anyone.
        assertTrue(state.speedhuntRunning(session));
        assertTrue(state.runs(session).isEmpty());
        assertStatus(409, () -> apply(session, upload(GameState.SPEEDHUNT_PING, anna, true, null)));
        assertStatus(400, () -> apply(session, livePing(anna, 1, upload + 1000)));

        clock.advance(Duration.ofSeconds(5));
        var delivered = apply(session, livePing(anna, 1, upload)).results().getFirst();
        assertEquals(revision + 2, memberRevision(anna.id()));
        assertStatus(409, () -> apply(session, livePing(anna, 2, upload)));
        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofSeconds(30));
            apply(session, new GameOperation.LiveUploadSample(upload, null, null));
        }
        // Samples are not game changes hunters observe: the revision stays.
        assertEquals(revision + 2, memberRevision(anna.id()));
        var row = state.liveUploads(session).getFirst();
        assertEquals(delivered.id(), row.pingId());
        assertEquals(clock.instant(), row.lastSampleAt());
        assertEquals(EPOCH, row.controllerEpoch());
        assertEquals(1, state.runs(session).getFirst().pings().size());
        assertEquals(GameState.LIVE, state.runs(session).getFirst().pings().getFirst().mode());
        assertEquals(1, state.statistics(session).getFirst().livePings());

        apply(session, new GameOperation.LiveUploadEnded(upload, "deadline"));
        assertEquals(revision + 3, memberRevision(anna.id()));
        assertStatus(409, () -> apply(session, new GameOperation.LiveUploadSample(upload, null, null)));
        assertStatus(409, () -> apply(session, new GameOperation.LiveUploadEnded(upload, null)));
        assertEquals("deadline", state.liveUploads(session).getFirst().endReason());
        assertTrue(state.speedhuntRunning(session));
        apply(session, new GameOperation.RunEnded(anna.id(), GameState.TERMINATED, null));
        assertFalse(state.speedhuntRunning(session));
        clock.set(T0);
    }

    // Breaks ------------------------------------------------------------------------------------------------

    @Test
    void breaksFollowTheirLifecycleAndOnlyStartAndEndAreVisible() throws Exception {
        long session = session("{\"break_duration_minutes\": 10}");
        GameMember anna = bound(session, "player");
        GameMember ben = bound(session, "player");
        jokers.change(session, new GameJokerService.Change(anna.id(), GameJokerRepository.BREAK, 2, null),
                "Operator");
        long revision = memberRevision(anna.id());
        Instant later = clock.instant().plusSeconds(3600);

        long planned = applyId(session, new GameOperation.BreakPlanned(anna.id(), later.toString(), false));
        assertEquals(1, jokers.own(anna).reservations().size());
        apply(session, new GameOperation.BreakWaiting(planned));
        assertStatus(409, () -> apply(session, new GameOperation.BreakWaiting(planned)));
        assertStatus(409, () -> apply(session, new GameOperation.BreakEnded(planned)));
        // Planned and waiting breaks are private: hunters see no revision change.
        assertEquals(revision, memberRevision(anna.id()));

        // No break starts while a speedhunt runs; the answer is the same for everyone.
        long upload = applyId(session, upload(GameState.SPEEDHUNT_PING, ben, true, null));
        assertEquals(GameOperations.BREAK_UNAVAILABLE, assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> apply(session, new GameOperation.BreakStarted(planned, null, null))).getMessage());
        apply(session, new GameOperation.LiveUploadEnded(upload, null));

        apply(session, new GameOperation.BreakStarted(planned, null, null));
        assertEquals(revision + 1, memberRevision(anna.id()));
        var active = state.snapshot(session).breaks().stream().filter(GameState.Break::active).findFirst()
                .orElseThrow();
        assertEquals(clock.instant().plusSeconds(600), active.deadline());
        assertEquals(1, jokers.own(anna).inventories().stream()
                .filter(item -> item.type().equals(GameJokerRepository.BREAK)).findFirst().orElseThrow().used());
        assertStatus(409, () -> apply(session, new GameOperation.BreakCancelled(planned, null)));
        assertStatus(409, () -> apply(session, new GameOperation.BreakStarted(planned, null, null)));
        assertStatus(409, () -> apply(session, ping(anna, 1)));

        long second = applyId(session, new GameOperation.BreakPlanned(anna.id(), later.plusSeconds(3600).toString(),
                false));
        assertStatus(409, () -> apply(session, new GameOperation.BreakStarted(second, null, null)));
        apply(session, new GameOperation.BreakEnded(planned));
        assertEquals(revision + 2, memberRevision(anna.id()));
        assertStatus(409, () -> apply(session, new GameOperation.BreakEnded(planned)));
        assertStatus(409, () -> apply(session, new GameOperation.BreakStarted(second, null,
                clock.instant().minusSeconds(1).toString())));
        apply(session, new GameOperation.BreakMissed(second));
        assertStatus(409, () -> apply(session, new GameOperation.BreakMissed(second)));
        long third = applyId(session, new GameOperation.BreakPlanned(anna.id(), later.plusSeconds(9000).toString(),
                false));
        apply(session, new GameOperation.BreakCancelled(third, null));
        assertEquals(revision + 2, memberRevision(anna.id()));

        assertEquals(List.of(GameState.ENDED, GameState.MISSED, GameState.CANCELLED),
                state.breaks(session).stream().map(GameState.Break::state).toList());
        var inventory = jokers.own(anna).inventories().stream()
                .filter(item -> item.type().equals(GameJokerRepository.BREAK)).findFirst().orElseThrow();
        assertEquals(1, inventory.used());
        assertEquals(1, inventory.available());
        assertEquals(List.of(), jokers.reconcile(session));
        assertStatus(400, () -> apply(session, new GameOperation.BreakStarted(null, null, null)));
        assertStatus(400, () -> apply(session, new GameOperation.BreakEnded(-1)));
    }

    @Test
    void breaksNeedADurationAndAJokerOrAConfirmedUseBeyondTheAllocation() throws Exception {
        long unset = session();
        GameMember anna = bound(unset, "player");
        assertStatus(409, () -> apply(unset, new GameOperation.BreakPlanned(anna.id(), null, true)));

        long session = session("{\"break_duration_minutes\": 0.5}");
        GameMember ben = bound(session, "supporter");
        assertStatus(409, () -> apply(session, new GameOperation.BreakPlanned(ben.id(), null, false)));
        long planned = applyId(session, new GameOperation.BreakPlanned(ben.id(), null, true));
        // A plan without a start, or with one that has passed, is due now; another break would overlap it.
        assertStatus(409, () -> apply(session, new GameOperation.BreakPlanned(ben.id(),
                clock.instant().plusSeconds(10).toString(), true)));
        apply(session, new GameOperation.BreakStarted(planned, null, null));
        var inventory = jokers.own(ben).inventories().stream()
                .filter(item -> item.type().equals(GameJokerRepository.BREAK)).findFirst().orElseThrow();
        assertEquals(1, inventory.used());
        assertEquals(1, inventory.overAllocated());
        assertEquals(List.of(), jokers.reconcile(session));
        assertEquals(1, number("SELECT COUNT(*) FROM tc_game_joker_ledger WHERE gamesessionid = ? AND kind = ?",
                session, GameJokerRepository.OVERUSE));
        // The break lasted 30 seconds: once past its deadline it protects nobody, even before it ends.
        clock.advance(Duration.ofSeconds(31));
        try {
            apply(session, ping(ben, 1));
        } finally {
            clock.set(T0);
        }
    }

    // Live uploads ------------------------------------------------------------------------------------------

    @Test
    void hunterLiveSharesComeFromCurrentLiveUploadsThatReachHunters() throws Exception {
        long session = session();
        GameMember anna = bound(session, "player");
        GameMember ben = bound(session, "player");
        GameMember carla = bound(session, "supporter");
        GameMember dora = bound(session, "supporter");
        lease(session, EPOCH);
        long revision = memberRevision(anna.id());

        long manual = applyId(session, upload(GameState.MANUAL, anna, true, "Too far out"));
        assertEquals(revision + 1, memberRevision(anna.id()));
        applyId(session, new GameOperation.LiveUploadStarted(GameState.GEOFENCE_AUTO, ben.id(), null, true, true,
                true, 30, clock.instant().plusSeconds(300).toString(), null, null, null, null));
        long playersOnly = applyId(session, upload(GameState.MANUAL, carla, false, null));
        long carlaRevision = memberRevision(carla.id());
        long relay = applyId(session, new GameOperation.LiveUploadStarted(GameState.HUNTER_RELAY, null, dora.id(),
                false, true, false, 10, null, null, null, true, true));
        long doraRevision = memberRevision(dora.id());
        apply(session, new GameOperation.LiveUploadSample(relay, true, true));
        // A relay is a private joker use: nobody else sees a revision change.
        assertEquals(doraRevision, memberRevision(dora.id()));
        var relayJokers = jokers.own(dora).inventories().stream()
                .filter(item -> item.type().equals(GameJokerRepository.HUNTER_POSITIONS)).findFirst().orElseThrow();
        assertEquals(1, relayJokers.used());
        assertEquals(1, relayJokers.overAllocated());
        assertNotNull(state.liveUploads(session).stream().filter(item -> item.id() == relay).findFirst()
                .orElseThrow().reservationId());

        assertEquals(List.of(new GameStateService.LiveShare(anna.id(), "manual", null, "Too far out"),
                new GameStateService.LiveShare(ben.id(), "geofence", clock.instant().plusSeconds(300), null)),
                state.liveShares(session));

        // A new controller epoch makes rows of the previous one stale until they are sampled again.
        lease(session, EPOCH + 1);
        assertTrue(state.liveShares(session).isEmpty());
        state.apply(session, EPOCH + 1, List.of(new GameOperation.LiveUploadSample(manual, null, null)));
        assertEquals(List.of(anna.id()), state.liveShares(session).stream()
                .map(GameStateService.LiveShare::memberId).toList());
        state.apply(session, EPOCH + 1, List.of(new GameOperation.Catch(anna.id(), null)));
        assertTrue(state.liveShares(session).isEmpty());
        execute("DELETE FROM tc_game_controller WHERE id = 1");
        assertTrue(state.liveShares(session).isEmpty());

        assertStatus(409, () -> apply(session, upload(GameState.MANUAL, carla, true, null)));
        apply(session, new GameOperation.LiveUploadEnded(playersOnly, "stopped"));
        assertEquals(carlaRevision, memberRevision(carla.id()));
        assertStatus(400, () -> apply(session, new GameOperation.LiveUploadStarted("radio", anna.id(), null, true,
                true, true, 30, null, null, null, null, null)));
        assertStatus(400, () -> apply(session, new GameOperation.LiveUploadStarted(GameState.HUNTER_RELAY,
                anna.id(), null, true, true, true, 30, null, null, null, null, null)));
        assertStatus(400, () -> apply(session, new GameOperation.LiveUploadStarted(GameState.MANUAL, ben.id(), null,
                true, true, true, 0, null, null, null, null, null)));
        assertStatus(400, () -> apply(session, new GameOperation.LiveUploadStarted(GameState.MANUAL, carla.id(),
                null, true, true, true, 30, null, null, null, true, null)));
        assertStatus(409, () -> apply(session, new GameOperation.LiveUploadStarted(GameState.MANUAL, carla.id(),
                null, true, true, true, 30, clock.instant().toString(), null, null, null, null)));
        assertEquals(List.of(), jokers.reconcile(session));
        assertEquals(Set.of(dora.id()), publisher.topic(GamePublisher.JOKERS).getLast().members());
    }

    @Test
    void checkpointsTravelWithOperations() throws Exception {
        long session = session();
        var applied = apply(session, new GameOperation.Checkpoint("monitoring", 0, 1, json("{\"cursor\": 3}")));
        assertEquals(1, applied.results().getFirst().id());
        assertStatus(409, () -> apply(session, new GameOperation.Checkpoint("monitoring", 0, 1,
                json("{\"cursor\": 4}"))));
        assertStatus(400, () -> state.apply(session, EPOCH, List.of()));
    }

}
