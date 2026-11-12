package org.traccar.api;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.traccar.game.GameControllerService;
import org.traccar.game.GameOwnershipService;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

public class GameControllerFilterTest {

    private final GameControllerService controller = mock(GameControllerService.class);
    private final GameOwnershipService ownership = mock(GameOwnershipService.class);
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
        inject(filter, "ownership", ownership);
        inject(filter, "objectMapper", new ObjectMapper());
        // User 10 is a member of a stored session; a Tool holds the lease without being the caller.
        when(ownership.owned()).thenReturn(new GameOwnershipService.Owned(Set.of(201L), Set.of(), Set.of(10L, 11L),
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
        var write = request(1, "PUT", "game-sessions/5/checkpoints/monitoring");
        filter.filter(write);
        assertEquals(423, abortStatus(write));
        verify(controller).unlockShared(1L);

        var read = request(1, "GET", "game-sessions/5/snapshot");
        filter.filter(read);
        assertNull(abortStatus(read));
    }

    private ContainerRequestContext request(long userId, String method, String path, String body) {
        var request = request(userId, method, path);
        when(request.getEntityStream()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    @Test
    void pathNumbersAreReadLikeJerseyReadsThem() throws Exception {
        // "07" names session 7 for the resource, so it is the executing session's write.
        when(controller.executingSessionId()).thenReturn(7L);
        when(controller.check(anyLong(), any())).thenThrow(GameControllerService.locked("lease required"));
        for (String path : new String[] {"game-sessions/07/name", "game-sessions/+7/name", "game-sessions/7/name"}) {
            var rename = request(1, "PUT", path);
            filter.filter(rename);
            assertEquals(423, abortStatus(rename), path);
        }
        var owned = request(1, "PUT", "devices/0201");
        filter.filter(owned);
        assertEquals(423, abortStatus(owned));
    }

    @Test
    void permissionKeysAreReadLikeTheResourceReadsThem() throws Exception {
        // Keys resolve to classes case-insensitively, and values may be strings; device 201 is game-owned.
        for (String body : new String[] {"{\"UserId\":30,\"DeviceId\":201}", "{\"userId\":\"30\",\"deviceId\":\"201\"}",
                "[{\"USERID\":30,\"DEVICEID\":201}]", "{\"userXX\":30,\"deviceXX\":201}"}) {
            var grant = request(1, "POST", "permissions", body);
            filter.filter(grant);
            assertEquals(423, abortStatus(grant), body);
        }
        var unrelated = request(1, "POST", "permissions", "{\"userId\":30,\"deviceId\":999}");
        filter.filter(unrelated);
        assertNull(abortStatus(unrelated));
    }
}
