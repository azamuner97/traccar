package org.traccar.api.resource;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.traccar.api.BaseResource;
import org.traccar.api.security.PermissionsService;
import org.traccar.database.GameRequestService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class GameStatusResourceTest {

    private static final long USER_ID = 1;
    private static final long SESSION_ID = 2;

    private final GameRequestService requests = mock(GameRequestService.class);
    private final PermissionsService permissions = mock(PermissionsService.class);

    private GameStatusResource resource(boolean administrator) throws Exception {
        var resource = new GameStatusResource() {
            @Override
            protected long getUserId() {
                return USER_ID;
            }
        };
        var permissionsField = BaseResource.class.getDeclaredField("permissionsService");
        permissionsField.setAccessible(true);
        permissionsField.set(resource, permissions);
        var requestsField = GameStatusResource.class.getDeclaredField("requests");
        requestsField.setAccessible(true);
        requestsField.set(resource, requests);
        when(permissions.notAdmin(USER_ID)).thenReturn(!administrator);
        return resource;
    }

    @Test
    void membersReceiveProgressForTheirSession() throws Exception {
        var resource = resource(false);
        var progress = JsonNodeFactory.instance.objectNode().put("active", true).put("pingCount", 1);
        when(requests.speedhuntStatus(SESSION_ID)).thenReturn(progress);
        for (String role : List.of("player", "supporter", "hunter", "spectator")) {
            when(requests.membership(USER_ID)).thenReturn(
                    new GameRequestService.Membership(SESSION_ID, USER_ID, role, null));
            try (var response = resource.speedhunt()) {
                assertEquals(progress, response.getEntity());
                assertEquals("no-store", response.getHeaderString(HttpHeaders.CACHE_CONTROL));
            }
        }
        verify(requests, never()).speedhuntStatus(null);
    }

    @Test
    void administratorsReceiveTheExecutingSessionStatus() throws Exception {
        var resource = resource(true);
        var progress = JsonNodeFactory.instance.objectNode().put("active", true).put("pingCount", 2);
        when(requests.speedhuntStatus(null)).thenReturn(progress);
        try (var response = resource.speedhunt()) {
            assertEquals(progress, response.getEntity());
        }
        verify(requests, never()).membership(USER_ID);
    }

    @Test
    void unrelatedAccountsAreDenied() throws Exception {
        var resource = resource(false);
        when(requests.membership(USER_ID)).thenThrow(new ForbiddenException());
        assertThrows(ForbiddenException.class, resource::speedhunt);
        verify(requests, never()).speedhuntStatus(any());
    }
}
