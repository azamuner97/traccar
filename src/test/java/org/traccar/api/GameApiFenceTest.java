package org.traccar.api;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.api.security.UserPrincipal;
import org.traccar.game.GameControllerService;
import org.traccar.game.GameExecutionService;
import org.traccar.game.GameOwnershipService;
import org.traccar.game.GameUnfencedWrites;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * The write paths of the REST resources of game data model 2 ({@code org.traccar.api.game}) under the path rules of
 * {@link GameControllerFilter}. A fenced path makes the filter check the controller epoch; an unfenced one is let
 * through without a check.
 */
public class GameApiFenceTest {

    private static final long RUNNING = 5;
    private static final long STORED = 6;

    private final GameControllerService controller = mock(GameControllerService.class);
    private final GameExecutionService execution = mock(GameExecutionService.class);
    private GameControllerFilter filter;

    private static void inject(Object object, String name, Object value) throws Exception {
        var field = GameControllerFilter.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    @BeforeEach
    void setup() throws Exception {
        filter = new GameControllerFilter();
        inject(filter, "controller", controller);
        inject(filter, "ownership", mock(GameOwnershipService.class));
        inject(filter, "execution", execution);
        when(execution.runningSessionId()).thenReturn(RUNNING);
        when(controller.lockShared()).thenReturn(1L);
        when(controller.administrator(1L)).thenReturn(true);
    }

    @AfterEach
    void clearMark() {
        GameUnfencedWrites.end();
    }

    private static ContainerRequestContext request(String method, String path) {
        return request(method, path, null);
    }

    private static ContainerRequestContext request(String method, String path, String epoch) {
        return request(1, method, path, epoch);
    }

    private static ContainerRequestContext request(long userId, String method, String path, String epoch) {
        var request = mock(ContainerRequestContext.class);
        when(request.getMethod()).thenReturn(method);
        var security = mock(SecurityContext.class);
        when(security.getUserPrincipal()).thenReturn(new UserPrincipal(userId, null));
        when(request.getSecurityContext()).thenReturn(security);
        var uri = mock(UriInfo.class);
        List<PathSegment> segments = Arrays.stream(path.split("/")).map(value -> {
            var segment = mock(PathSegment.class);
            when(segment.getPath()).thenReturn(value);
            return segment;
        }).toList();
        when(uri.getPathSegments()).thenReturn(segments);
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(request.getUriInfo()).thenReturn(uri);
        when(request.getHeaderString(GameControllerService.EPOCH_HEADER)).thenReturn(epoch);
        return request;
    }

    /** Whether the filter checks the controller epoch for this write, sent with an epoch. */
    private boolean fenced(String write) throws Exception {
        return checked(write, "1");
    }

    /** Whether the filter checks the epoch of this write sent without one: the write needs the lease. */
    private boolean required(String write) throws Exception {
        return checked(write, null);
    }

    private boolean checked(String write, String epoch) throws Exception {
        clearInvocations(controller);
        String[] parts = write.split(" ", 2);
        filter.filter(request(parts[0], parts[1], epoch));
        GameUnfencedWrites.end();
        try {
            verify(controller, never()).check(anyLong(), any());
            return false;
        } catch (AssertionError checked) {
            return true;
        }
    }

    /** The operator writes of {@code GameSessionResource} of a session. */
    private static List<String> sessionWrites(long id) {
        String base = "game-sessions/" + id;
        return List.of("DELETE " + base, "PUT " + base + "/config",
                "POST " + base + "/members", "PUT " + base + "/members/3/name", "PUT " + base + "/members/3/account",
                "DELETE " + base + "/members/3/account", "PUT " + base + "/members/3/tracker",
                "DELETE " + base + "/members/3/tracker", "POST " + base + "/members/3/retire",
                "POST " + base + "/resources/mirrors",
                "POST " + base + "/resources/groups", "POST " + base + "/resources/geofences",
                "POST " + base + "/resources/mirrors/2/retire", "POST " + base + "/resources/mirrors/2/reactivate",
                "PUT " + base + "/resources/mirrors/2", "DELETE " + base + "/resources/mirrors/2",
                "PUT " + base + "/credentials", "PUT " + base + "/geofence-plans", "PUT " + base + "/device-list",
                "PUT " + base + "/checkpoints/monitoring", "DELETE " + base + "/checkpoints/monitoring",
                "POST " + base + "/timetable/changes", "PUT " + base + "/timetable/execution",
                "POST " + base + "/logs",
                "POST " + base + "/requests/9/decision", "POST " + base + "/requests/9/claim",
                "POST " + base + "/jokers/change", "POST " + base + "/jokers/breaks", "POST " + base + "/effects",
                "PUT " + base + "/state");
    }

    @Test
    void everyWriteOfTheRunningSessionIsFenced() throws Exception {
        for (String write : sessionWrites(RUNNING)) {
            assertTrue(fenced(write), write);
        }
        assertFalse(fenced("POST game-sessions/5/events/test"));
    }

    @Test
    void executionWritesOfAStoredSessionAreFencedAndConfigurationIsNot() throws Exception {
        // Configuration of a session that is not running needs only its revision check; members, resource rows,
        // checkpoints and the game state are written by the controlling Tool and always fenced.
        Set<String> configuration = Set.of("DELETE game-sessions/6", "PUT game-sessions/6/config",
                "PUT game-sessions/6/credentials", "PUT game-sessions/6/geofence-plans",
                "PUT game-sessions/6/device-list", "POST game-sessions/6/timetable/changes",
                "PUT game-sessions/6/timetable/execution", "POST game-sessions/6/logs");
        for (String write : sessionWrites(STORED)) {
            if (!write.endsWith("/state")) {
                assertEquals(!configuration.contains(write), required(write), write);
            }
            // Review SC-6: an epoch that a configuration write carries must be current as well.
            assertTrue(fenced(write), write);
        }
        assertFalse(required("POST game-sessions"));
        assertTrue(fenced("POST game-sessions"));
    }

    @Test
    void onlyAdministratorsLearnAnythingAboutTheLease() throws Exception {
        // Review SC-5: whatever a member sends, the answer never depends on the lease: the resource's 403, or 403
        // from a failed check; administrators get 423.
        when(controller.check(anyLong(), any())).thenThrow(GameControllerService.locked("lease"));
        for (boolean active : new boolean[] {true, false}) {
            when(controller.status()).thenReturn(new GameControllerService.Lease(3, active ? "active" : "expired",
                    active, null, null, null, null, "2026-05-01T10:00:00Z", 30, 5));
            for (String epoch : new String[] {null, "1"}) {
                for (String path : List.of("game-sessions/5/state", "game-sessions/6/state",
                        "game-sessions/6/config", "game-sessions/5/config")) {
                    var request = request(2, "PUT", path, epoch);
                    filter.filter(request);
                    GameUnfencedWrites.end();
                    var captor = org.mockito.ArgumentCaptor.forClass(jakarta.ws.rs.core.Response.class);
                    verify(request, org.mockito.Mockito.atMost(1)).abortWith(captor.capture());
                    Integer status = captor.getAllValues().isEmpty() ? null : captor.getValue().getStatus();
                    boolean refusedWithoutEpoch = epoch == null && path.equals("game-sessions/5/config");
                    assertEquals(epoch != null || refusedWithoutEpoch ? Integer.valueOf(403) : null, status,
                            path + " " + epoch + " " + active);
                }
            }
        }
        var operator = request(1, "PUT", "game-sessions/6/config", "1");
        filter.filter(operator);
        verify(operator).abortWith(org.mockito.ArgumentMatchers.argThat(response -> response.getStatus() == 423));
    }

    @Test
    void notificationAndProximityWritesAreAlwaysFencedAndMemberRequestsNever() throws Exception {
        for (String write : List.of("POST game-notifications", "POST game-notifications/session/6",
                "POST game-notifications/session/6/reset", "PUT game-proximity/session/6",
                "POST game-proximity/session/6/reset")) {
            assertTrue(fenced(write), write);
        }
        for (String write : List.of("POST game-requests", "POST game-requests/9/cancel",
                "POST game-jokers/reservations/4/cancel", "POST game-sessions/6/events/test",
                "POST game-controller/release", "GET game-sessions/5/snapshot", "GET game-status")) {
            assertFalse(fenced(write), write);
        }
    }

}
