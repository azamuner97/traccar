package org.traccar.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.api.security.PermissionsService;
import org.traccar.api.security.UserPrincipal;
import org.traccar.database.GameAccessRule;
import org.traccar.model.Device;
import org.traccar.model.User;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class GameAccessFilterTest {

    private static final long ADMIN = 1;
    private static final long PARTICIPANT = 20;
    private static final long MANAGER = 30;

    private final GameAccessRule rule = mock(GameAccessRule.class);
    private final PermissionsService permissions = mock(PermissionsService.class);
    private GameAccessFilter filter;

    private static void inject(Object object, String name, Object value) throws Exception {
        var field = GameAccessFilter.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    @BeforeEach
    void setup() throws Exception {
        filter = new GameAccessFilter();
        inject(filter, "rule", rule);
        inject(filter, "objectMapper", new ObjectMapper());
        var injector = mock(com.google.inject.Injector.class);
        when(injector.getInstance(PermissionsService.class)).thenReturn(permissions);
        inject(filter, "injector", injector);
        when(permissions.notAdmin(anyLong())).thenReturn(true);
        when(permissions.notAdmin(ADMIN)).thenReturn(false);
        // A participant may not change permissions or objects; the manager may.
        doThrow(new SecurityException("Write access denied")).when(permissions)
                .checkWritePermission(any(), eq(PARTICIPANT), anyLong());
    }

    private ContainerRequestContext request(String method, String path, String body) {
        return request(ADMIN, method, path, body);
    }

    private ContainerRequestContext request(long userId, String method, String path, String body) {
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
        when(request.getUriInfo()).thenReturn(uri);
        when(request.getEntityStream()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    private GameAccessRule.Change checked() throws Exception {
        var captor = ArgumentCaptor.forClass(GameAccessRule.Change.class);
        verify(rule).check(captor.capture());
        return captor.getValue();
    }

    @Test
    void permissionGrantsAreCheckedAsUserDeviceAndGroupAccess() throws Exception {
        filter.filter(request("POST", "permissions/bulk",
                "[{\"userId\":10,\"deviceId\":101},{\"userId\":10,\"groupId\":50},{\"deviceId\":1,\"geofenceId\":2}]"));
        var change = checked();
        assertArrayEquals(new long[] {10, 101}, change.userDevices().getFirst());
        assertArrayEquals(new long[] {10, 50}, change.userGroups().getFirst());
        assertEquals(1, change.userDevices().size());
    }

    @Test
    void callersWithoutTheRightAreLeftToTraccarsOwnRefusal() throws Exception {
        doThrow(new jakarta.ws.rs.WebApplicationException("hunter hunter1 could see device 101", 422))
                .when(rule).check(any());
        var probe = request(PARTICIPANT, "POST", "permissions", "{\"userId\":10,\"deviceId\":101}");
        filter.filter(probe);
        verify(probe, never()).abortWith(any());
        verify(rule, never()).check(any());

        var managed = request(MANAGER, "POST", "permissions", "{\"userId\":10,\"deviceId\":101}");
        filter.filter(managed);
        verify(permissions).checkWritePermission(User.class, MANAGER, 10);
        verify(permissions).checkWritePermission(Device.class, MANAGER, 101);
        verify(managed).abortWith(any());
    }

    @Test
    void deviceAndGroupMovesAreChecked() throws Exception {
        filter.filter(request("PUT", "devices/101", "{\"id\":101,\"groupId\":60}"));
        assertEquals(60L, checked().deviceGroups().get(101L));
        reset(rule);
        filter.filter(request("PUT", "groups/50", "{\"id\":50,\"groupId\":0}"));
        var change = checked();
        assertTrue(change.groupParents().containsKey(50L));
        assertNull(change.groupParents().get(50L));
    }

    @Test
    void refusalsAbortWithTheRuleStatusAndOtherRequestsPass() throws Exception {
        doThrow(new WebApplicationException("hunters must see players only through mirrors", 422))
                .when(rule).check(any());
        var refused = request("POST", "permissions", "{\"userId\":10,\"deviceId\":101}");
        filter.filter(refused);
        var captor = ArgumentCaptor.forClass(Response.class);
        verify(refused).abortWith(captor.capture());
        assertEquals(422, captor.getValue().getStatus());

        var removal = request("DELETE", "permissions", "{\"userId\":10,\"deviceId\":101}");
        filter.filter(removal);
        var read = request("GET", "devices/101", "");
        filter.filter(read);
        var create = request("POST", "devices", "{\"groupId\":60}");
        filter.filter(create);
        verify(removal, never()).abortWith(any());
        verify(read, never()).abortWith(any());
        verify(create, never()).abortWith(any());
    }
}
