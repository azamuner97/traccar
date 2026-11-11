package org.traccar.api.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.traccar.api.BaseResource;
import org.traccar.api.security.PermissionsService;
import org.traccar.database.GameSessionService;
import org.traccar.model.Server;
import org.traccar.model.User;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class GameSessionResourceTest {

    private final GameSessionService service = mock(GameSessionService.class);
    private final org.traccar.session.ConnectionManager sockets = mock(org.traccar.session.ConnectionManager.class);

    private void inject(Object object, Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private GameSessionResource resource(boolean administrator) throws Exception {
        User user = new User();
        user.setId(1);
        user.setAdministrator(administrator);
        var storage = mock(Storage.class);
        when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(user);
        when(storage.getObject(eq(Server.class), any(Request.class))).thenReturn(new Server());
        var resource = new GameSessionResource() {
            @Override
            protected long getUserId() {
                return 1;
            }
        };
        inject(resource, BaseResource.class, "storage", storage);
        inject(resource, BaseResource.class, "permissionsService", new PermissionsService(storage));
        inject(resource, GameSessionResource.class, "service", service);
        inject(resource, GameSessionResource.class, "connectionManager", sockets);
        return resource;
    }

    @Test
    void everyEndpointRequiresAnAdministrator() throws Exception {
        var resource = resource(false);
        assertThrows(ForbiddenException.class, resource::capabilities);
        assertThrows(ForbiddenException.class, resource::list);
        assertThrows(ForbiddenException.class, () -> resource.get(1));
        assertThrows(ForbiddenException.class, () -> resource.getCredentials(1));
        assertThrows(ForbiddenException.class, () -> resource.listLogs(1, null, 0));
        assertThrows(ForbiddenException.class, () -> resource.delete(1));
        verifyNoInteractions(service);
    }

    @Test
    void writesRequireTheRevisionTheyWereBasedOn() throws Exception {
        var resource = resource(true);
        assertEquals(GameSessionService.API_VERSION, resource.capabilities().apiVersion());
        var document = new ObjectMapper().readTree("{}");
        assertThrows(BadRequestException.class, () -> resource.updateConfig(
                1, new GameSessionResource.DocumentUpdate(null, 4, document)));
        assertThrows(BadRequestException.class, () -> resource.updateRuntime(
                1, new GameSessionResource.DocumentUpdate(-1L, null, document)));
        assertThrows(BadRequestException.class, () -> resource.create(
                new GameSessionResource.CreateRequest("x", null, document)));
        verifyNoInteractions(service);

        when(service.updateRuntime(1, 3, document)).thenReturn(new GameSessionService.RuntimeUpdate(4, false));
        assertEquals(4, resource.updateRuntime(1, new GameSessionResource.DocumentUpdate(3L, null, document))
                .revision());
        when(service.list()).thenReturn(List.of());
        assertTrue(resource.list().isEmpty());
    }

    @Test
    void onlyListRelevantWritesNotifyAdministrators() throws Exception {
        var resource = resource(true);
        var document = new ObjectMapper().createObjectNode();
        when(service.updateRuntime(5, 1, document)).thenReturn(new GameSessionService.RuntimeUpdate(2, false));
        resource.updateRuntime(5, new GameSessionResource.DocumentUpdate(1L, null, document));
        verifyNoInteractions(sockets);
        resource.updateConfig(5, new GameSessionResource.DocumentUpdate(1L, 4,
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()));
        verify(sockets).updateGameAdmin(true, GameSessionResource.INVALIDATION);
    }

    @Test
    void runtimeWritesNotifySessionMembersWhenVisibleHistoryChanges() throws Exception {
        var resource = resource(true);
        var document = new ObjectMapper().createObjectNode();
        when(service.updateRuntime(5, 1, document)).thenReturn(new GameSessionService.RuntimeUpdate(2, true));
        assertEquals(2, resource.updateRuntime(5, new GameSessionResource.DocumentUpdate(1L, null, document)).revision());
        verify(sockets).updateGameMembers(true, 5L, GameStatusResource.SPEEDHUNT_HISTORY_INVALIDATION);
        verifyNoMoreInteractions(sockets);
    }
}
