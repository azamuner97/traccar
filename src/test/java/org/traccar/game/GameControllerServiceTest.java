package org.traccar.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class GameControllerServiceTest {

    private static final String A = "instance-a-0001";
    private static final String B = "instance-b-0002";

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-05-01T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }

    private final MutableClock clock = new MutableClock();
    private final GameTestBase.RecordingPublisher publisher = new GameTestBase.RecordingPublisher();
    private DataSource source;
    private GameSessionService sessions;
    private GameMemberService members;
    private GameRequestService requests;
    private GameControllerService service;
    private long alice;
    private long bob;
    private long session;

    private long insert(String sql, Object... params) throws Exception {
        try (var connection = source.getConnection();
             var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < params.length; i++) {
                if (params[i] instanceof Instant instant) {
                    GameTime.set(statement, i + 1, instant);
                } else {
                    statement.setObject(i + 1, params[i]);
                }
            }
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    /** The real schema of data model 2, with two operators and one stored session. */
    @BeforeEach
    void setup() throws Exception {
        source = GameSchemaFixture.model2();
        alice = insert("INSERT INTO tc_users (name, email) VALUES ('Alice', 'a@x')");
        bob = insert("INSERT INTO tc_users (name, email) VALUES ('', 'b@x')");
        GameTransactions transactions = new GameTransactions(source, clock);
        sessions = new GameSessionService(transactions, new ObjectMapper(), publisher);
        members = new GameMemberService(transactions, Set.of(), publisher);
        requests = new GameRequestService(transactions, new ObjectMapper(), publisher);
        session = sessions.create("Controller test", 5, 5, JsonNodeFactory.instance.objectNode()).id();
        service = new GameControllerService(source, clock, 30, 5, requests, publisher);
        publisher.clear();
    }

    /** A request awaiting the controller, as a member's submission leaves it. */
    private long pending(String status) throws Exception {
        GameMember hunter = members.plan(session, GameMember.HUNTER, null, "Hunter");
        Instant now = clock.instant();
        return insert("INSERT INTO tc_game_requests (gamesessionid, type, requestermemberid, requesterrole,"
                + " parameters, status, epoch, createdat, updatedat) VALUES (?, ?, ?, 'hunter', '{}', ?, ?, ?, ?)",
                session, GameRequest.TERMINATE, hunter.id(), status, GameRequest.EXECUTING.equals(status) ? 1L : null,
                now, now);
    }

    private String requestStatus(long id) throws Exception {
        return requests.operatorList(session, false, 0).stream().filter(item -> item.id() == id).findFirst()
                .orElseThrow().status();
    }

    private static int status(Throwable error) {
        return ((WebApplicationException) error).getResponse().getStatus();
    }

    private List<String> events() throws Exception {
        return service.history(null, 0).items().stream().map(GameControllerService.HistoryEntry::event)
                .toList().reversed();
    }

    @Test
    void acquireIsExclusiveAndIdempotentForTheHolder() throws Exception {
        var first = service.acquire(alice, A, "Laptop");
        assertTrue(first.acquired());
        assertEquals(1, first.lease().epoch());
        assertTrue(first.lease().active());
        assertEquals("Alice", first.lease().holder().userLabel());
        assertEquals(30, first.lease().leaseSeconds());
        assertEquals(5, first.lease().renewSeconds());

        var repeat = service.acquire(alice, A, "Laptop");
        assertFalse(repeat.acquired());
        assertEquals(1, repeat.lease().epoch());

        // Same account on another PC, and another operator, are both refused while the lease is active.
        assertEquals(423, status(assertThrows(WebApplicationException.class, () -> service.acquire(alice, B, "PC"))));
        assertEquals(423, status(assertThrows(WebApplicationException.class, () -> service.acquire(bob, B, "PC"))));
        assertThrows(BadRequestException.class, () -> service.acquire(alice, "bad id", "PC"));
        assertEquals(List.of("acquired"), events());
    }

    @Test
    void renewKeepsTheEpochUntilExpiry() throws Exception {
        long epoch = service.acquire(alice, A, "Laptop").lease().epoch();
        clock.advance(20);
        var renewed = service.renew(alice, epoch, A);
        assertEquals(epoch, renewed.epoch());
        assertEquals("2026-05-01T10:00:50Z", renewed.expiresAt());
        assertEquals(423, status(assertThrows(WebApplicationException.class, () -> service.renew(alice, epoch, B))));
        assertEquals(423, status(assertThrows(WebApplicationException.class, () -> service.renew(bob, epoch, A))));

        clock.advance(31);
        assertEquals("expired", service.status().state());
        assertFalse(service.status().active());
        assertEquals(423, status(assertThrows(WebApplicationException.class, () -> service.renew(alice, epoch, A))));
        assertEquals(423, status(assertThrows(WebApplicationException.class, () -> service.check(alice, epoch))));
        assertTrue(service.recordExpiry());
        assertFalse(service.recordExpiry());

        // Re-acquiring after expiry starts a new epoch; the old one stays rejected.
        var again = service.acquire(alice, A, "Laptop");
        assertEquals(epoch + 1, again.lease().epoch());
        assertEquals(423, status(assertThrows(WebApplicationException.class, () -> service.check(alice, epoch))));
        assertEquals(epoch + 1, service.check(alice, epoch + 1).epoch());
        assertEquals(List.of("acquired", "expired", "acquired"), events());
    }

    @Test
    void takeoverFencesThePreviousHolder() throws Exception {
        long first = service.acquire(alice, A, "Laptop").lease().epoch();
        var takeover = service.takeover(bob, B, "Desk");
        assertEquals(first + 1, takeover.lease().epoch());
        assertEquals("b@x", takeover.lease().holder().userLabel());
        assertEquals(423, status(assertThrows(WebApplicationException.class, () -> service.check(alice, first))));
        assertEquals(423, status(assertThrows(WebApplicationException.class, () -> service.renew(alice, first, A))));
        // The new epoch belongs to the new operator only.
        assertEquals(423, status(assertThrows(WebApplicationException.class, () -> service.check(alice, first + 1))));
        assertEquals(List.of("acquired", "takenOver", "takeover"), events());

        // Releasing with the old epoch changes nothing; with the current one it frees the lease.
        assertTrue(service.release(alice, first).active());
        assertFalse(service.release(bob, first + 1).active());
        assertEquals("released", service.status().state());
        assertEquals(423, status(assertThrows(WebApplicationException.class, () -> service.check(bob, first + 1))));
    }

    @Test
    void epochKeepsIncreasingAcrossServerRestarts() throws Exception {
        long epoch = service.acquire(alice, A, "Laptop").lease().epoch();
        // A new service instance reads the persisted row, as after a restart.
        service = new GameControllerService(source, clock, 30, 5, requests, publisher);
        assertTrue(service.endAfterRestart());
        assertFalse(service.endAfterRestart());
        assertEquals("restarted", service.status().state());
        assertEquals(423, status(assertThrows(WebApplicationException.class, () -> service.renew(alice, epoch, A))));
        assertEquals(epoch + 1, service.acquire(alice, A, "Laptop").lease().epoch());
        assertEquals(List.of("acquired", "serverRestart", "acquired"), events());
    }

    @Test
    void driftReportsUnfencedEditsSinceThePreviousAcquisition() throws Exception {
        long epoch = service.acquire(alice, A, "Laptop").lease().epoch();
        service.release(alice, epoch);

        service.recordUnfencedEdit(bob, "PUT /api/devices/101");
        var acquisition = service.acquire(alice, A, "Laptop");
        assertEquals(1, acquisition.drift().size());
        assertEquals("PUT /api/devices/101", acquisition.drift().getFirst().detail());
        service.release(alice, acquisition.lease().epoch());
        assertTrue(service.acquire(alice, A, "Laptop").drift().isEmpty());
    }

    @Test
    void aToolThatLostControlResumesItOnlyIfNobodyAcquiredItSince() throws Exception {
        long epoch = service.acquire(alice, A, "Laptop").lease().epoch();
        // While its lease is still active, resuming returns it unchanged.
        var same = service.acquire(alice, A, "Laptop", epoch);
        assertFalse(same.acquired());
        assertEquals(epoch, same.lease().epoch());

        // Expired: granted without a takeover, with a new epoch.
        clock.advance(31);
        var resumed = service.acquire(alice, A, "Laptop", epoch);
        assertTrue(resumed.acquired());
        assertEquals(epoch + 1, resumed.lease().epoch());

        // Ended by a server restart: free, so granted too.
        service = new GameControllerService(source, clock, 30, 5, requests, publisher);
        assertTrue(service.endAfterRestart());
        assertEquals(epoch + 2, service.acquire(alice, A, "Laptop", epoch + 1).lease().epoch());

        // Another Tool acquired control meanwhile: refused even though the lease is free again.
        clock.advance(31);
        long other = service.acquire(bob, B, "Desk").lease().epoch();
        service.release(bob, other);
        assertEquals(409, status(assertThrows(WebApplicationException.class,
                () -> service.acquire(alice, A, "Laptop", epoch + 2))));
        // Another instance cannot resume this one's epoch, and an active lease of another holder stays locked.
        assertEquals(409, status(assertThrows(WebApplicationException.class,
                () -> service.acquire(alice, B, "PC", other))));
        long held = service.acquire(bob, B, "Desk").lease().epoch();
        assertEquals(423, status(assertThrows(WebApplicationException.class,
                () -> service.acquire(alice, A, "Laptop", held))));
        assertEquals(List.of("acquired", "expired", "acquired", "serverRestart", "acquired", "expired",
                "acquired", "released", "acquired"), events());
    }

    @Test
    void anInFlightFencedRequestCompletesBeforeATakeover() throws Exception {
        long epoch = service.acquire(alice, A, "Laptop").lease().epoch();
        long stamp = service.lockShared();
        service.check(alice, epoch);
        var executor = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch started = new CountDownLatch(1);
            Future<GameControllerService.Acquisition> takeover = executor.submit(() -> {
                started.countDown();
                return service.takeover(bob, B, "Desk");
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.sleep(200);
            assertFalse(takeover.isDone(), "Takeover must wait for the in-flight request");
            assertEquals(epoch, service.status().epoch());
            service.unlockShared(stamp);
            assertEquals(epoch + 1, takeover.get(5, TimeUnit.SECONDS).lease().epoch());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aChangeOfControlEndsNoRequestAndAnnouncesItself() throws Exception {
        long epoch = service.acquire(alice, A, "Laptop").lease().epoch();
        assertEquals(List.of(GameControllerService.INVALIDATION), publisher.operatorTopics);
        assertTrue(publisher.topic(GamePublisher.STATUS).isEmpty());

        // A takeover keeps waiting requests and returns one still executing under an older epoch to approved.
        long waiting = pending(GameRequest.PENDING);
        long approved = pending(GameRequest.APPROVED);
        long executing = pending(GameRequest.EXECUTING);
        publisher.clear();
        var takeover = service.takeover(bob, B, "Desk");
        assertEquals(epoch + 1, takeover.lease().epoch());
        assertEquals(GameRequest.PENDING, requestStatus(waiting));
        assertEquals(GameRequest.APPROVED, requestStatus(approved));
        assertEquals(GameRequest.APPROVED, requestStatus(executing));
        assertEquals(List.of(new GamePublisher.Invalidation(session, GamePublisher.REQUESTS, null)),
                publisher.topic(GamePublisher.REQUESTS));
        assertTrue(publisher.topic(GamePublisher.STATUS).isEmpty());
        assertEquals(List.of(GameControllerService.INVALIDATION), publisher.operatorTopics);

        // Expiry, release and a restart end no request either; a renewal announces nothing.
        publisher.clear();
        service.renew(bob, epoch + 1, B);
        assertTrue(publisher.operatorTopics.isEmpty());
        clock.advance(31);
        assertTrue(service.recordExpiry());
        assertEquals(GameRequest.PENDING, requestStatus(waiting));
        assertEquals(GameRequest.APPROVED, requestStatus(approved));
        assertEquals(List.of(GameControllerService.INVALIDATION), publisher.operatorTopics);
        assertTrue(publisher.topic(GamePublisher.REQUESTS).isEmpty());
    }
}
