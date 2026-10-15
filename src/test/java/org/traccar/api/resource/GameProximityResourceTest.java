package org.traccar.api.resource;

import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.traccar.api.BaseResource;
import org.traccar.api.security.PermissionsService;
import org.traccar.database.GameProximityService;
import org.traccar.model.Device;
import org.traccar.model.User;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class GameProximityResourceTest {
    private final GameProximityService service = mock(GameProximityService.class);

    private User user(String role, long sessionId) {
        User user = new User();
        user.setId(111);
        user.setReadonly(true);
        user.getAttributes().put("traccarToolUserRole", role);
        user.getAttributes().put("traccarToolSessionDeviceId", sessionId);
        return user;
    }

    private void inject(Object object, Class<?> type, String fieldName, Object value) throws Exception {
        var field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(object, value);
    }

    private GameProximityResource resource(User user) throws Exception {
        Storage storage = mock(Storage.class);
        when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(user);
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(new Device());
        var resource = new GameProximityResource() {
            @Override
            protected long getUserId() { return 111; }
        };
        inject(resource, BaseResource.class, "storage", storage);
        inject(resource, BaseResource.class, "permissionsService", new PermissionsService(storage));
        inject(resource, GameProximityResource.class, "service", service);
        return resource;
    }

    @Test
    void onlySessionHuntersAndSpectatorsCanReadCircles() throws Exception {
        when(service.circles(900)).thenReturn(List.of());
        for (String role : List.of("hunter", "spectator")) {
            assertEquals(List.of(), resource(user(role, 900)).circles(900));
        }
        for (String role : List.of("player", "supporter", "unrelated")) {
            assertThrows(ForbiddenException.class, () -> resource(user(role, 900)).circles(900));
        }
        assertThrows(ForbiddenException.class, () -> resource(user("hunter", 901)).circles(900));
        User administrator = user("hunter", 900);
        administrator.setAdministrator(true);
        assertThrows(ForbiddenException.class, () -> resource(administrator).circles(900));
        User disabled = user("hunter", 900);
        disabled.setDisabled(true);
        assertThrows(ForbiddenException.class, () -> resource(disabled).circles(900));
    }

    @Test
    void generatedUsersCannotReadManagerSnapshotsOrWriteState() throws Exception {
        for (String role : List.of("player", "supporter", "hunter", "spectator")) {
            assertThrows(ForbiddenException.class, () -> resource(user(role, 900)).get(900));
            assertThrows(ForbiddenException.class, () -> resource(user(role, 900)).replace(900, null));
        }
        verifyNoInteractions(service);
    }
}
