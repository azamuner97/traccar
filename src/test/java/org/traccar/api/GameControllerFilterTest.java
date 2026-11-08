package org.traccar.api;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.api.security.UserPrincipal;
import org.traccar.database.GameControllerService;
import org.traccar.database.GameSessionService;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

public class GameControllerFilterTest {

    private final GameControllerService controller = mock(GameControllerService.class);
    private final GameSessionService sessions = mock(GameSessionService.class);
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
        inject(filter, "sessions", sessions);
        // User 10 is a member of a stored session; a Tool holds the lease without being the caller.
        when(sessions.owned()).thenReturn(new GameSessionService.Owned(Set.of(201L), Set.of(), Set.of(10L, 11L),
                Set.of()));
        when(controller.status()).thenReturn(new GameControllerService.Lease(3, "active", true, null, null,
                null, null, null, "2026-05-01T10:00:00Z", 30, 5));
        when(controller.lockShared()).thenReturn(1L);
    }

    private ContainerRequestContext request(long userId, String method, String path) {
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
        return request;
    }

    private Integer abortStatus(ContainerRequestContext request) {
        var captor = ArgumentCaptor.forClass(Response.class);
        verify(request, atMost(1)).abortWith(captor.capture());
        return captor.getAllValues().isEmpty() ? null : captor.getValue().getStatus();
    }

    @Test
    void membersMayUpdateTheirOwnAccountWhileAToolControlsTheGame() throws Exception {
        var own = request(10, "PUT", "users/10");
        filter.filter(own);
        assertNull(abortStatus(own));
        verify(controller, never()).check(anyLong(), any());

        var other = request(10, "PUT", "users/11");
        filter.filter(other);
        assertEquals(423, abortStatus(other));

        var device = request(10, "PUT", "devices/201");
        filter.filter(device);
        assertEquals(423, abortStatus(device));
    }

    @Test
    void gameWritesAlwaysNeedTheEpochAndReadsNever() throws Exception {
        when(controller.check(anyLong(), any())).thenThrow(GameControllerService.locked("lease required"));
        var write = request(1, "PUT", "game-sessions/5/runtime");
        filter.filter(write);
        assertEquals(423, abortStatus(write));
        verify(controller).unlockShared(1L);

        var read = request(1, "GET", "game-sessions/5/runtime");
        filter.filter(read);
        assertNull(abortStatus(read));
    }
}
