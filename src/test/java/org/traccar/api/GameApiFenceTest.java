package org.traccar.api;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.api.security.UserPrincipal;
import org.traccar.game.GameControllerService;
import org.traccar.game.GameOwnershipService;

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

    private static final long EXECUTING = 5;
    private static final long STORED = 6;

    private final GameControllerService controller = mock(GameControllerService.class);
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
        when(controller.executingSessionId()).thenReturn(EXECUTING);
        when(controller.lockShared()).thenReturn(1L);
    }

    private static ContainerRequestContext request(String method, String path) {
        var request = mock(ContainerRequestContext.class);
        when(request.getMethod()).thenReturn(method);
        var security = mock(SecurityContext.class);
        when(security.getUserPrincipal()).thenReturn(new UserPrincipal(1, null));
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
        return request;
    }

    /** Whether the filter checks the controller epoch for this write. */
    private boolean fenced(String write) throws Exception {
        clearInvocations(controller);
        String[] parts = write.split(" ", 2);
        filter.filter(request(parts[0], parts[1]));
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
        return List.of("DELETE " + base, "PUT " + base + "/name", "PUT " + base + "/config",
                "POST " + base + "/members", "PUT " + base + "/members/3/name", "PUT " + base + "/members/3/account",
                "DELETE " + base + "/members/3/account", "PUT " + base + "/members/3/tracker",
                "DELETE " + base + "/members/3/tracker", "POST " + base + "/members/3/retire",
                "POST " + base + "/members/3/role", "POST " + base + "/resources/mirrors",
                "POST " + base + "/resources/groups", "POST " + base + "/resources/geofences",
                "POST " + base + "/resources/mirrors/2/retire", "POST " + base + "/resources/mirrors/2/reactivate",
                "PUT " + base + "/resources/mirrors/2", "DELETE " + base + "/resources/mirrors/2",
                "PUT " + base + "/credentials", "PUT " + base + "/geofence-plans",
                "PUT " + base + "/checkpoints/monitoring", "DELETE " + base + "/checkpoints/monitoring",
                "PUT " + base + "/timetable", "PUT " + base + "/timetable/progress", "POST " + base + "/logs",
                "POST " + base + "/requests/9/decision", "POST " + base + "/requests/9/claim",
                "POST " + base + "/jokers/change", "POST " + base + "/jokers/breaks", "POST " + base + "/effects");
    }

    @Test
    void everyWriteOfTheExecutingSessionIsFenced() throws Exception {
        for (String write : sessionWrites(EXECUTING)) {
            assertTrue(fenced(write), write);
        }
    }

    @Test
    void executionWritesOfAStoredSessionAreFencedAndConfigurationIsNot() throws Exception {
        // Configuration of a session that is not executing needs only its revision check; members, resource rows
        // and checkpoints are written by the controlling Tool and always fenced.
        Set<String> configuration = Set.of("DELETE game-sessions/6", "PUT game-sessions/6/name",
                "PUT game-sessions/6/config", "PUT game-sessions/6/credentials", "PUT game-sessions/6/geofence-plans",
                "PUT game-sessions/6/timetable", "POST game-sessions/6/logs");
        for (String write : sessionWrites(STORED)) {
            assertEquals(!configuration.contains(write), fenced(write), write);
        }
    }

    @Test
    void notificationAndProximityWritesAreAlwaysFencedAndMemberRequestsNever() throws Exception {
        for (String write : List.of("POST game-notifications", "POST game-notifications/session/6",
                "POST game-notifications/session/6/reset", "PUT game-proximity/session/6",
                "POST game-proximity/session/6/reset")) {
            assertTrue(fenced(write), write);
        }
        for (String write : List.of("POST game-requests", "POST game-requests/9/cancel",
                "POST game-jokers/reservations/4/cancel", "POST game-sessions",
                "GET game-sessions/5/snapshot", "GET game-status")) {
            assertFalse(fenced(write), write);
        }
    }

}
