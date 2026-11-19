package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 3a of game data model 2: session documents (credentials, geofence plans, checkpoints), logs, timetables,
 * notifications and proximity. Runs on H2 ({@link GameScheduleH2Test}) and MySQL ({@link GameScheduleMySqlTest}).
 */
public abstract class GameScheduleTest extends GameTestBase {

    private GameDocumentService documents;
    private GameLogService logs;
    private GameTimetableService timetables;
    private GameNotificationService notifications;
    private GameProximityService proximity;

    @Override
    protected void createServices() {
        documents = new GameDocumentService(transactions, mapper, cipher());
        logs = new GameLogService(transactions);
        timetables = new GameTimetableService(transactions, mapper, publisher);
        notifications = new GameNotificationService(transactions, publisher);
        proximity = new GameProximityService(transactions, publisher);
    }

    // Documents ---------------------------------------------------------------------------------------------

    @Test
    void credentialsAreEncryptedAtRestRevisionCheckedAndNeedTheKey() throws Exception {
        long session = session();
        assertEquals(new GameDocumentService.Document(0, json("{}")), documents.credentials(session));
        long before = revision(session);
        JsonNode secret = json("{\"base\":\"top-secret-password\"}");
        assertEquals(1, documents.updateCredentials(session, 0, secret));
        assertEquals(before + 1, revision(session));
        assertFalse(text("SELECT ciphertext FROM tc_game_session_credentials WHERE gamesessionid = ?", session)
                .contains("top-secret"));
        assertEquals(new GameDocumentService.Document(1, secret), documents.credentials(session));

        assertEquals(1, documents.updateCredentials(session, 0, secret));
        assertStatus(409, () -> documents.updateCredentials(session, 0, json("{\"base\":\"other\"}")));
        assertEquals(2, documents.updateCredentials(session, 1, json("{\"base\":\"other\"}")));
        assertStatus(400, () -> documents.updateCredentials(session, 2, json("[]")));
        assertStatus(404, () -> documents.credentials(-1));

        var withoutKey = new GameDocumentService(transactions, mapper, new GameCredentialCipher((String) null));
        assertStatus(503, () -> withoutKey.credentials(session));
        assertStatus(503, () -> withoutKey.updateCredentials(session, 2, secret));
        var wrongKey = new GameDocumentService(transactions, mapper, new GameCredentialCipher(
                java.util.Base64.getEncoder().encodeToString(new byte[] {
                    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                    1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16})));
        assertStatus(503, () -> wrongKey.credentials(session));
        assertEquals(2, documents.credentials(session).revision());
    }

    @Test
    void geofencePlansAndCheckpointsAreRevisionChecked() throws Exception {
        long session = session();
        JsonNode plans = json("{\"plans\":[{\"key\":\"zone\"}]}");
        assertEquals(0, documents.geofencePlans(session).revision());
        assertEquals(1, documents.updateGeofencePlans(session, 0, plans));
        assertEquals(1, documents.updateGeofencePlans(session, 0, plans));
        assertStatus(409, () -> documents.updateGeofencePlans(session, 0, json("{}")));
        assertEquals(plans, documents.geofencePlans(session).document());

        var empty = documents.checkpoint(session, "monitoring");
        assertEquals(0, empty.revision());
        JsonNode cursor = json("{\"cursor\":5}");
        long before = revision(session);
        var written = documents.putCheckpoint(session, "monitoring", 0, 2, cursor);
        assertEquals(1, written.revision());
        assertEquals(before + 1, revision(session));
        assertEquals(1, documents.putCheckpoint(session, "monitoring", 0, 2, cursor).revision());
        assertStatus(409, () -> documents.putCheckpoint(session, "monitoring", 0, 3, cursor));
        assertStatus(409, () -> documents.putCheckpoint(session, "monitoring", 5, 2, cursor));
        assertEquals(2, documents.putCheckpoint(session, "monitoring", 1, 2, json("{\"cursor\":6}")).revision());
        documents.putCheckpoint(session, "relay", 0, 1, json("{}"));
        assertEquals(List.of("monitoring", "relay"), documents.checkpoints(session).stream()
                .map(GameDocumentService.Checkpoint::component).toList());
        assertStatus(400, () -> documents.putCheckpoint(session, "bad name", 0, 1, json("{}")));
        assertStatus(400, () -> documents.putCheckpoint(session, "other", 0, 0, json("{}")));
        assertStatus(400, () -> documents.putCheckpoint(session, "other", 0, 1, json("1")));

        // Losing a checkpoint is allowed: it only makes monitoring re-scan.
        documents.deleteCheckpoint(session, "monitoring");
        documents.deleteCheckpoint(session, "monitoring");
        assertEquals(0, documents.checkpoint(session, "monitoring").revision());
        assertEquals(1, documents.putCheckpoint(session, "monitoring", 0, 2, cursor).revision());
    }

    @Test
    void logsAreAppendOnlyPaginatedAndFilteredByCategory() throws Exception {
        long session = session();
        logs.append(session, List.of(
                new GameLogService.LogInput("operation", "2026-05-01T10:00:00Z", "first"),
                new GameLogService.LogInput(GameLogService.SPEEDHUNT, "2026-05-01T10:00:01.250Z", "second"),
                new GameLogService.LogInput("operation", "2026-05-01T10:00:02Z", "third")));
        var page = logs.list(session, null, 2, null, false);
        assertEquals(List.of("first", "second"), page.items().stream().map(GameLogService.LogEntry::message)
                .toList());
        assertTrue(page.hasMore());
        assertEquals(Instant.parse("2026-05-01T10:00:01.250Z"), page.items().get(1).occurredAt());
        var rest = logs.list(session, page.nextCursor(), 2, null, false);
        assertEquals(List.of("third"), rest.items().stream().map(GameLogService.LogEntry::message).toList());
        assertFalse(rest.hasMore());
        assertEquals(List.of("second"), logs.list(session, null, 10, GameLogService.SPEEDHUNT, false).items().stream()
                .map(GameLogService.LogEntry::message).toList());
        assertTrue(logs.list(session(), null, 10, null, false).items().isEmpty());
        assertStatus(400, () -> logs.append(session, List.of(
                new GameLogService.LogInput("bad category", "2026-05-01T10:00:00Z", "x"))));
        assertStatus(400, () -> logs.append(session, List.of(
                new GameLogService.LogInput("operation", "yesterday", "x"))));
        assertStatus(400, () -> logs.append(session, List.of()));
        assertStatus(400, () -> logs.list(session, "abc", 10, null, false));
        assertStatus(404, () -> logs.list(-1, null, 10, null, false));
        assertEquals(3, logs.list(session, null, 0, null, false).items().size());
    }

    // Timetables --------------------------------------------------------------------------------------------

    @Test
    void membersSeeTheirTimetablePingsWithoutCellContent() throws Exception {
        long session = session();
        GameMember anna = plan(session, "player", "Anna");
        GameMember ben = plan(session, "supporter", "Ben");
        GameMember spectator = plan(session, "spectator");
        GameMember hunter = plan(session, "hunter");
        Instant now = clock.instant().plusSeconds(8000);
        timetable(timetables, session, List.of(anna, ben),
                row(now.minusSeconds(600), anna, "p"),
                row(now.minusSeconds(7200), anna, "p"),
                row(now.plusSeconds(10), anna, "2H"),
                row(now.plusSeconds(600), anna, "b", ben, "47.1,8.5|2P"),
                row(now.plusSeconds(900), anna, "2P2S"),
                row(now.plusSeconds(1200), anna, "p", ben, ""));
        Instant start = clock.instant();
        clock.set(now);
        try {

            var own = timetables.pings(anna);
            assertEquals(List.of(now.minusSeconds(600), now.plusSeconds(10), now.plusSeconds(1200)),
                    own.stream().map(GameTimetableService.Ping::scheduledAt).toList());
            // The fake cutoff is 15 s: a ping 10 s ahead can no longer carry a fake.
            assertEquals(java.util.Arrays.asList(false, false, true),
                    own.stream().map(GameTimetableService.Ping::fakeAllowed).toList());
            assertTrue(timetables.pings(ben).isEmpty());

            var watched = timetables.pings(spectator);
            assertEquals(List.of(now.minusSeconds(600), now.plusSeconds(10), now.plusSeconds(600),
                    now.plusSeconds(1200)), watched.stream().map(GameTimetableService.Ping::scheduledAt).toList());
            assertEquals(ben.id(), watched.get(2).memberId());
            assertEquals(GameTimetableRepository.SCHEDULED, watched.get(2).state());
            assertNull(watched.getFirst().fakeAllowed());
            assertEquals(List.of(now.minusSeconds(600), now.plusSeconds(10), now.plusSeconds(600),
                    now.plusSeconds(1200)), timetables.hunterSlots(session));
            assertStatus(403, () -> timetables.pings(hunter));

            // A retired member's pings are no longer shown.
            members.retire(session, anna.id());
            assertEquals(List.of(now.plusSeconds(600)), timetables.hunterSlots(session));
        } finally {
            clock.set(start);
        }
    }

    // Notifications -----------------------------------------------------------------------------------------

    private static GameNotificationService.Alert alert(String id) {
        return new GameNotificationService.Alert(id, "speedhunt", "session", "2026-01-01T12:00:00Z", "UTC",
                "Speedhunt started", null, null);
    }

    private static GameNotificationService.Alert alert(String id, String type, String audience, Long recipient,
                                                       Long source) {
        return new GameNotificationService.Alert(id, type, audience, "2026-01-01T12:00:00Z", "Europe/Zurich",
                "Message " + id, recipient, source);
    }

    @Test
    void archivePaginationDeduplicationAndReset() throws Exception {
        long session = session();
        var state = notifications.initialize(session);
        assertEquals(state, notifications.initialize(session));
        for (int i = 0; i < 110; i++) {
            assertTrue(notifications.create(session, state.generation(), alert("alert" + i)).created());
        }
        assertFalse(notifications.create(session, state.generation(), alert("alert0")).created());
        var first = notifications.list(session, null, null, 0, null);
        assertEquals(100, first.items().size());
        assertTrue(first.hasMore());
        var second = notifications.list(session, state.generation(), first.nextCursor(), 100, null);
        assertEquals(10, second.items().size());
        assertFalse(second.hasMore());
        assertEquals("alert100", second.items().getFirst().id());
        assertTrue(notifications.list(session(), null, null, 100, null).items().isEmpty());

        String replacement = UUID.randomUUID().toString();
        var reset = notifications.reset(session, state.generation(), replacement);
        assertEquals(state.revision() + 1, reset.revision());
        assertEquals(reset, notifications.reset(session, state.generation(), replacement));
        assertTrue(notifications.list(session, replacement, null, 100, null).items().isEmpty());
        assertStatus(409, () -> notifications.create(session, state.generation(), alert("late")));
        assertStatus(409, () -> notifications.list(session, state.generation(), null, 100, null));
        assertTrue(notifications.create(session, replacement, alert("alert0")).created());
    }

    @Test
    void conflictsAndMalformedAlertsDoNotMutateTheArchive() throws Exception {
        long session = session();
        var state = notifications.initialize(session);
        notifications.create(session, state.generation(), alert("one"));
        assertStatus(409, () -> notifications.create(session, state.generation(),
                new GameNotificationService.Alert("one", "playerCaught", "session", "2026-01-01T12:00:00Z", "UTC",
                        "Alice was caught", null, null)));
        assertStatus(400, () -> notifications.list(session, null, "-1", 100, null));
        assertStatus(400, () -> notifications.list(session, null, null, 501, null));
        assertStatus(400, () -> notifications.create(session, state.generation(),
                new GameNotificationService.Alert("a", "speedhunt", "participants", "2026-01-01T12:00:00Z", "UTC",
                        "message", null, null)));
        assertStatus(400, () -> notifications.create(session, state.generation(),
                new GameNotificationService.Alert("a", "speedhunt", "session",
                        clock.instant().plusSeconds(120).toString(), "UTC", "message", null, null)));
        assertTrue(notifications.create(session, state.generation(), alert("CaseId")).created());
        assertTrue(notifications.create(session, state.generation(), alert("caseid")).created());
        assertFalse(notifications.create(session, state.generation(), alert("CaseId")).created());
        for (String zone : new String[] {"GMT+02:00", "+02:00", "SystemV/EST5EDT"}) {
            assertStatus(400, () -> notifications.create(session, state.generation(),
                    new GameNotificationService.Alert("invalid", "speedhunt", "session", "2026-01-01T12:00:00Z",
                            zone, "message", null, null)));
        }
        assertStatus(400, () -> notifications.create(session, state.generation(),
                new GameNotificationService.Alert("invalid", "speedhunt", "session", "1900-01-01T12:00:00Z", "UTC",
                        "message", null, null)));
        assertEquals(3, notifications.list(session, null, null, 100, null).items().size());
    }

    @Test
    void concurrentDuplicatesAndResetsAreSerialized() throws Exception {
        long session = session();
        var state = notifications.initialize(session);
        try (var executor = Executors.newFixedThreadPool(4)) {
            Future<GameNotificationService.Creation> one = executor.submit(
                    () -> notifications.create(session, state.generation(), alert("one")));
            Future<GameNotificationService.Creation> duplicate = executor.submit(
                    () -> notifications.create(session, state.generation(), alert("one")));
            assertNotEquals(one.get().created(), duplicate.get().created());
            String replacement = UUID.randomUUID().toString();
            Future<Object> racing = executor.submit(() -> {
                try {
                    return notifications.create(session, state.generation(), alert("racing"));
                } catch (jakarta.ws.rs.WebApplicationException expected) {
                    assertEquals(409, expected.getResponse().getStatus());
                    return null;
                }
            });
            Future<GameNotificationService.State> reset = executor.submit(
                    () -> notifications.reset(session, state.generation(), replacement));
            racing.get();
            reset.get();
            assertTrue(notifications.list(session, replacement, null, 100, null).items().isEmpty());
        }
    }

    @Test
    void audiencesAreStrictAndMembersSeeOnlyTheirAlerts() throws Exception {
        long session = session();
        GameMember anna = plan(session, "player", "Anna");
        GameMember ben = plan(session, "supporter", "Ben");
        GameMember hunter = plan(session, "hunter", "Hugo");
        GameMember spectator = plan(session, "spectator");
        String generation = notifications.initialize(session).generation();
        publisher.clear();

        notifications.create(session, generation, alert("session"));
        notifications.create(session, generation, alert("toHunters", "speedhunt", "hunters", null, null));
        notifications.create(session, generation, alert("toOthers", "speedhunt", "nonHunters", null, null));
        notifications.create(session, generation, alert("annaExit", "geofenceExit", "user", anna.id(), anna.id()));
        notifications.create(session, generation, alert("hunterExit", "geofenceExit", "user", hunter.id(),
                hunter.id()));
        notifications.create(session, generation, alert("benGetsAnna", "livePingReceived", "liveSupporter",
                ben.id(), anna.id()));
        notifications.create(session, generation, alert("spectatorChange", "livePingTargetsChanged",
                "liveSpectator", spectator.id(), anna.id()));
        assertEquals(Set.of(anna.id()), publisher.topic(GamePublisher.NOTIFICATIONS).get(3).members());
        assertNull(publisher.topic(GamePublisher.NOTIFICATIONS).get(0).members());

        Map<GameMember, List<String>> expected = Map.of(
                anna, List.of("session", "toOthers", "annaExit"),
                ben, List.of("session", "toOthers", "benGetsAnna"),
                hunter, List.of("session", "toHunters", "hunterExit"),
                spectator, List.of("session", "toHunters", "spectatorChange"));
        for (var item : expected.entrySet()) {
            assertEquals(item.getValue(), notifications.list(session, null, null, 100, item.getKey()).items()
                    .stream().map(GameNotificationService.Entry::id).toList(), item.getKey().label());
        }
        assertEquals(7, notifications.list(session, null, null, 100, null).items().size());

        // Labels are snapshots: renaming a member never rewrites history.
        members.rename(session, anna.id(), "Anna Renamed");
        var stored = notifications.history(session, null, 100).items().get(5);
        assertEquals("Ben", stored.recipientLabel());
        assertEquals("Anna", stored.sourceLabel());

        List<GameNotificationService.Alert> invalid = List.of(
                alert("a", "geofenceExit", "user", anna.id(), ben.id()),
                alert("b", "geofenceExit", "user", spectator.id(), spectator.id()),
                alert("c", "geofenceExit", "session", anna.id(), anna.id()),
                alert("d", "livePingReceived", "livePlayer", ben.id(), anna.id()),
                alert("e", "livePingReceived", "liveSupporter", ben.id(), ben.id()),
                alert("f", "livePingReceived", "liveHunter", hunter.id(), hunter.id()),
                alert("g", "livePingTargetsChanged", "liveHunter", hunter.id(), anna.id()),
                alert("h", "livePingReceived", "liveHunter", hunter.id(), spectator.id()),
                alert("i", "participantProximityConsequence", "nonHunters", null, null),
                alert("j", "playerCaught", "hunters", null, null),
                alert("k", "geofenceExit", "user", -5L, -5L));
        for (var item : invalid) {
            assertStatus(400, () -> notifications.create(session, generation, item));
        }
        GameMember retired = plan(session, "player");
        members.retire(session, retired.id());
        assertStatus(409, () -> notifications.create(session, generation,
                alert("l", "geofenceExit", "user", retired.id(), retired.id())));
        GameMember foreign = plan(session(), "player");
        assertStatus(400, () -> notifications.create(session, generation,
                alert("m", "geofenceExit", "user", foreign.id(), foreign.id())));
        assertEquals(7, notifications.history(session, null, 100).items().size());

        // A role change makes a new member, which does not see the old member's personal history.
        GameMember changed = members.changeRole(session, anna.id(), "supporter", null, "Anna");
        assertEquals(List.of("session", "toOthers"), notifications.list(session, null, null, 100, changed).items()
                .stream().map(GameNotificationService.Entry::id).toList());
    }

    @Test
    void historyCoversEveryGeneration() throws Exception {
        long session = session();
        var state = notifications.initialize(session);
        notifications.create(session, state.generation(), alert("before"));
        String next = UUID.randomUUID().toString();
        notifications.reset(session, state.generation(), next);
        notifications.create(session, next, alert("after"));
        // A reset deletes the session's history, as in model 1.
        assertEquals(List.of("after"), notifications.history(session, null, 10).items().stream()
                .map(GameNotificationService.Entry::id).toList());
        assertStatus(404, () -> notifications.history(-1, null, 10));
    }

    // Proximity ---------------------------------------------------------------------------------------------

    private static GameProximityService.GrantInput grant(String episode, GameMember recipient, GameMember target,
                                                         Instant expires) {
        return new GameProximityService.GrantInput(episode, recipient.id(), target.id(), expires.toString());
    }

    private List<GameProximityService.GrantInput> pair(String episode, GameMember a, GameMember b,
                                                       Instant expires) {
        return List.of(grant(episode, a, b, expires), grant(episode, b, a, expires));
    }

    @Test
    void proximityGrantsResolveAccessColumnsAndCirclesDeriveCounts() throws Exception {
        long session = session();
        GameMember anna = bound(session, "player");
        GameMember ben = bound(session, "supporter");
        GameMember carla = bound(session, "player");
        Instant expires = clock.instant().plusSeconds(600);
        var circle = new GameProximityService.CircleInput("circle-1", 47.1, 8.5, 250, expires.toString(),
                List.of(anna.id(), ben.id(), carla.id()));
        long before = revision(session);
        var snapshot = proximity.replace(session, 0, pair("episode-1", anna, ben, expires), List.of(circle));
        assertEquals(1, snapshot.revision());
        assertEquals(before + 1, revision(session));
        assertEquals(2, snapshot.grants().size());
        var annaSeesBen = snapshot.grants().stream().filter(item -> item.recipientMemberId() == anna.id())
                .findFirst().orElseThrow();
        assertEquals(anna.userId(), annaSeesBen.userId());
        assertEquals(ben.trackerId(), annaSeesBen.deviceId());
        assertEquals(1, number("SELECT COUNT(*) FROM tc_game_proximity_grants WHERE userid = ? AND deviceid = ?",
                ben.userId(), anna.trackerId()));
        assertEquals(List.of(new GameProximityService.PublicCircle("circle-1", 47.1, 8.5, 250, 2, 1, expires)),
                proximity.circles(session));
        assertEquals(Set.of(anna.userId(), ben.userId()), publisher.proximityAccess.getLast());

        // The same content is idempotent at the current revision and for a retry; older revisions conflict.
        assertEquals(1, proximity.replace(session, 1, pair("episode-1", anna, ben, expires),
                List.of(circle)).revision());
        assertEquals(1, proximity.replace(session, 0, List.of(grant("episode-1", ben, anna, expires),
                grant("episode-1", anna, ben, expires)), List.of(circle)).revision());
        assertStatus(409, () -> proximity.replace(session, 0, List.of(), List.of()));
        assertEquals(2, proximity.replace(session, 1, List.of(), List.of(circle)).revision());
        assertStatus(409, () -> proximity.replace(session, 5, List.of(), List.of()));

        // Expired content disappears from reads and is purged without a new revision.
        proximity.replace(session, 2, pair("episode-2", anna, carla, clock.instant().plusSeconds(60)),
                List.of(circle));
        clock.advance(Duration.ofSeconds(120));
        try {
            assertTrue(proximity.snapshot(session).grants().isEmpty());
            assertEquals(1, proximity.snapshot(session).circles().size());
            publisher.clear();
            assertEquals(2, proximity.purgeExpired());
            // The recipients of the expired grants have their device access recomputed.
            assertEquals(List.of(Set.of(anna.userId(), carla.userId())), publisher.proximityAccess);
            assertEquals(3, proximity.snapshot(session).revision());
            assertEquals(0, number("SELECT COUNT(*) FROM tc_game_proximity_grants WHERE gamesessionid = ?",
                    session));
        } finally {
            clock.set(T0);
        }

        var reset = proximity.reset(session);
        assertEquals(4, reset.revision());
        assertTrue(reset.circles().isEmpty());
    }

    @Test
    void invalidProximityContentChangesNothing() throws Exception {
        long session = session();
        GameMember anna = bound(session, "player");
        GameMember ben = bound(session, "player");
        GameMember hunter = bound(session, "hunter");
        GameMember unbound = plan(session, "supporter");
        GameMember foreign = bound(session(), "player");
        Instant expires = clock.instant().plusSeconds(600);
        List<List<GameProximityService.GrantInput>> invalid = List.of(
                List.of(grant("one", anna, ben, expires)),
                List.of(grant("one", anna, ben, expires), grant("one", ben, anna, expires.plusSeconds(1))),
                List.of(grant("one", anna, anna, expires), grant("one", anna, anna, expires)),
                List.of(grant("bad id!", anna, ben, expires), grant("bad id!", ben, anna, expires)));
        for (var grants : invalid) {
            assertStatus(400, () -> proximity.replace(session, 0, grants, List.of()));
        }
        assertStatus(400, () -> proximity.replace(session, 0, pair("one", anna, hunter, expires), List.of()));
        assertStatus(400, () -> proximity.replace(session, 0, pair("one", anna, foreign, expires), List.of()));
        assertStatus(409, () -> proximity.replace(session, 0, pair("one", anna, unbound, expires), List.of()));
        assertStatus(400, () -> proximity.replace(session, 0, List.of(), List.of(
                new GameProximityService.CircleInput("c", 47, 8, 100, expires.toString(), List.of(anna.id())))));
        assertStatus(400, () -> proximity.replace(session, 0, List.of(), List.of(
                new GameProximityService.CircleInput("c", 47, 8, 0, expires.toString(),
                        List.of(anna.id(), ben.id())))));
        assertStatus(400, () -> proximity.replace(session, 0, List.of(), List.of(
                new GameProximityService.CircleInput("c", 47, 8, 10, expires.toString(),
                        List.of(anna.id(), hunter.id())))));
        assertEquals(0, proximity.snapshot(session).revision());
    }

    @Test
    void aBindingChangeRevokesGrantsAndARetryOfTheEarlierWriteConflicts() throws Exception {
        long session = session();
        GameMember anna = bound(session, "player");
        GameMember ben = bound(session, "player");
        GameMember carla = bound(session, "supporter");
        Instant expires = clock.instant().plusSeconds(600);
        var content = new ArrayList<>(pair("ab", anna, ben, expires));
        content.addAll(pair("bc", ben, carla, expires));
        proximity.replace(session, 0, content, List.of());

        members.bindTracker(session, anna.id(), device());
        var snapshot = proximity.snapshot(session);
        assertEquals(2, snapshot.revision());
        assertEquals(Set.of("bc"), Set.copyOf(snapshot.grants().stream()
                .map(GameProximityService.Grant::episodeId).toList()));
        // The publisher's lost answer is retried with the old base revision: it must not pass as applied.
        assertStatus(409, () -> proximity.replace(session, 0, content, List.of()));
        assertStatus(409, () -> proximity.replace(session, 1, content, List.of()));
        // Based on the current revision, the same content applies again, with the new tracker.
        var again = proximity.replace(session, 2, content, List.of());
        assertEquals(3, again.revision());
        assertEquals(1, number("SELECT COUNT(*) FROM tc_game_proximity_grants WHERE userid = ? AND deviceid = ?",
                ben.userId(), members.get(session, anna.id()).trackerId()));
    }

}
