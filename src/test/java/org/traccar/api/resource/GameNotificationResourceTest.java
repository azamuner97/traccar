package org.traccar.api.resource;

import org.junit.jupiter.api.Test;
import jakarta.ws.rs.ForbiddenException;
import org.traccar.api.BaseResource;
import org.traccar.api.security.PermissionsService;
import org.traccar.database.GameNotificationService;
import org.traccar.model.Device;
import org.traccar.model.Server;
import org.traccar.model.User;
import org.traccar.session.ConnectionManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class GameNotificationResourceTest {

    private final GameNotificationService journal = mock(GameNotificationService.class);
    private final ConnectionManager sockets = mock(ConnectionManager.class);

    private void inject(Object object, Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private User user(String role, long sessionId) {
        User user = new User();
        user.setId(1);
        user.getAttributes().put("traccarToolUserRole", role);
        user.getAttributes().put("traccarToolSessionDeviceId", sessionId);
        return user;
    }

    private GameNotificationResource resource(User user, boolean deviceAccess) throws Exception {
        var storage = mock(Storage.class);
        when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(user);
        when(storage.getObject(eq(Server.class), any(Request.class))).thenReturn(new Server());
        when(storage.getObject(eq(Device.class), any(Request.class)))
                .thenReturn(deviceAccess ? new Device() : null);
        var resource = new GameNotificationResource() {
            @Override
            protected long getUserId() { return 1; }
        };
        inject(resource, BaseResource.class, "permissionsService", new PermissionsService(storage));
        inject(resource, GameNotificationResource.class, "service", journal);
        inject(resource, GameNotificationResource.class, "connectionManager", sockets);
        return resource;
    }

    @Test
    void allGeneratedRolesReadOnlyTheirAuthorizedSession() throws Exception {
        for (String role : List.of("player", "supporter", "hunter", "spectator")) {
            assertDoesNotThrow(() -> resource(user(role, 900), true).list(900, null, null, 100));
            assertThrows(ForbiddenException.class,
                    () -> resource(user(role, 901), true).list(900, null, null, 100));
            assertThrows(ForbiddenException.class,
                    () -> resource(user(role, 900), false).list(900, null, null, 100));
        }
        assertThrows(ForbiddenException.class,
                () -> resource(user("unrelated", 900), true).list(900, null, null, 100));
        verifyNoInteractions(sockets);
    }

    @Test
    void creationInitializationAndResetRequireManagerAndDeviceAccess() throws Exception {
        var request = new GameNotificationResource.CreateRequest(900, "generation", null);
        var reset = new GameNotificationResource.ResetRequest("old", "new");
        var ordinary = resource(user("player", 900), true);
        assertThrows(ForbiddenException.class, () -> ordinary.create(request));
        assertThrows(ForbiddenException.class, () -> ordinary.initialize(900));
        assertThrows(ForbiddenException.class, () -> ordinary.reset(900, reset));
        User manager = user("operator", 900);
        manager.setUserLimit(10);
        var denied = resource(manager, false);
        assertThrows(ForbiddenException.class, () -> denied.initialize(900));
        manager.setReadonly(true);
        assertThrows(ForbiddenException.class, () -> resource(manager, true).reset(900, reset));
        verifyNoInteractions(journal, sockets);
    }

    @Test
    void onlyFreshInsertAndResetInvalidateNeverPush() throws Exception {
        User admin = user("operator", 900);
        admin.setAdministrator(true);
        var resource = resource(admin, false);
        var request = new GameNotificationResource.CreateRequest(900, "generation", null);
        when(journal.create(900, "generation", null))
                .thenReturn(new GameNotificationService.Creation(1, "generation", null, true))
                .thenReturn(new GameNotificationService.Creation(1, "generation", null, false));
        resource.create(request);
        resource.create(request);
        resource.list(900, null, null, 100);
        resource.initialize(900);
        verify(sockets, times(1)).updateGameNotifications(true, 900);
        resource.reset(900, new GameNotificationResource.ResetRequest("old", "new"));
        verify(sockets, times(2)).updateGameNotifications(true, 900);
        verifyNoMoreInteractions(sockets);
    }
}
