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

    private GameNotificationService.Alert alert() {
        return new GameNotificationService.Alert("test", "notificationTest", "session",
                "2026-01-01T12:00:00Z", "UTC", "test", null, null);
    }

    private GameNotificationResource resource(User user, boolean deviceAccess) throws Exception {
        return resource(user, deviceAccess, null);
    }

    private GameNotificationResource resource(User user, boolean deviceAccess, User recipient) throws Exception {
        var storage = mock(Storage.class);
        if (recipient == null) {
            when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(user);
        } else {
            when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(user, recipient);
        }
        when(storage.getObject(eq(Server.class), any(Request.class))).thenReturn(new Server());
        when(storage.getObject(eq(Device.class), any(Request.class)))
                .thenReturn(deviceAccess ? new Device() : null);
        var resource = new GameNotificationResource() {
            @Override
            protected long getUserId() { return 1; }
        };
        inject(resource, BaseResource.class, "storage", storage);
        inject(resource, BaseResource.class, "permissionsService", new PermissionsService(storage));
        inject(resource, GameNotificationResource.class, "service", journal);
        inject(resource, GameNotificationResource.class, "connectionManager", sockets);
        return resource;
    }

    private User recipient(String role, long sessionId, long sourceDeviceId) {
        User user = user(role, sessionId);
        user.setId(10);
        user.getAttributes().put(GameNotificationService.ASSIGNED_DEVICE_ATTRIBUTE, sourceDeviceId);
        return user;
    }

    private GameNotificationService.Alert targetedAlert(long recipientUserId, long sourceDeviceId) {
        return new GameNotificationService.Alert("exit", "geofenceExit", "user",
                "2026-01-01T12:00:00Z", "UTC", "Hunter left geofence", recipientUserId, sourceDeviceId);
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
        var request = new GameNotificationResource.CreateRequest(900, "generation", alert());
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
        var request = new GameNotificationResource.CreateRequest(900, "generation", alert());
        when(journal.create(900, "generation", alert()))
                .thenReturn(new GameNotificationService.Creation(2, "generation", null, true))
                .thenReturn(new GameNotificationService.Creation(2, "generation", null, false));
        resource.create(request);
        resource.create(request);
        resource.list(900, null, null, 100);
        resource.initialize(900);
        verify(sockets, times(1)).updateGameNotifications(true, 900, null);
        resource.reset(900, new GameNotificationResource.ResetRequest("old", "new"));
        verify(sockets, times(2)).updateGameNotifications(true, 900, null);
        verifyNoMoreInteractions(sockets);
    }

    @Test
    void targetedCreationRequiresMatchingGeneratedRecipientAndSourcePermission() throws Exception {
        User admin = user("operator", 900);
        admin.setAdministrator(true);
        var target = recipient("hunter", 900, 31);
        var alert = targetedAlert(10, 31);
        when(journal.create(900, "generation", alert))
                .thenReturn(new GameNotificationService.Creation(2, "generation", null, true));

        assertDoesNotThrow(() -> resource(admin, true, target).create(
                new GameNotificationResource.CreateRequest(900, "generation", alert)));
        verify(sockets).updateGameNotifications(true, 900, 10L);

        assertThrows(ForbiddenException.class, () -> resource(admin, true, recipient("hunter", 901, 31)).create(
                new GameNotificationResource.CreateRequest(900, "generation", alert)));
        assertThrows(ForbiddenException.class, () -> resource(admin, true, recipient("hunter", 900, 32)).create(
                new GameNotificationResource.CreateRequest(900, "generation", alert)));
        assertThrows(ForbiddenException.class, () -> resource(admin, true, recipient("spectator", 900, 31)).create(
                new GameNotificationResource.CreateRequest(900, "generation", alert)));
        assertThrows(ForbiddenException.class, () -> resource(admin, false, target).create(
                new GameNotificationResource.CreateRequest(900, "generation", alert)));
    }

    @Test
    void historyUsesPrivateRecipientFilterExceptForAdministrators() throws Exception {
        resource(user("player", 900), true).list(900, null, null, 100);
        verify(journal).list(900, null, null, 100, new GameNotificationService.ReadAccess(1, null, "player"));

        User admin = user("operator", 900);
        admin.setAdministrator(true);
        resource(admin, false).list(900, null, null, 100);
        verify(journal).list(900, null, null, 100, null);
    }

    @Test
    void personalHistoryRequiresCurrentRoleAssignmentAndSourcePermission() throws Exception {
        User player = user("player", 900);
        player.getAttributes().put(GameNotificationService.ASSIGNED_DEVICE_ATTRIBUTE, 31);
        resource(player, true).list(900, null, null, 100);
        verify(journal).list(900, null, null, 100, new GameNotificationService.ReadAccess(1, 31L, "player"));
        reset(journal);
        player.getAttributes().put("traccarToolUserRole", "spectator");
        resource(player, true).list(900, null, null, 100);
        verify(journal).list(900, null, null, 100, new GameNotificationService.ReadAccess(1, null, "spectator"));
        reset(journal);
        player.getAttributes().put("traccarToolUserRole", "player");
        var resource = resource(player, true);
        var field = BaseResource.class.getDeclaredField("storage");
        field.setAccessible(true);
        Storage storage = (Storage) field.get(resource);
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(new Device(), (Device) null);
        resource.list(900, null, null, 100);
        verify(journal).list(900, null, null, 100, new GameNotificationService.ReadAccess(1, null, "player"));
    }

    private GameNotificationResource liveResource(User target, List<User> owners) throws Exception {
        User admin = user("operator", 900);
        admin.setAdministrator(true);
        var resource = resource(admin, true, target);
        var field = BaseResource.class.getDeclaredField("storage");
        field.setAccessible(true);
        Storage storage = (Storage) field.get(resource);
        when(storage.getObjects(eq(User.class), any(Request.class))).thenReturn(owners);
        return resource;
    }

    @Test
    void liveRecipientsRequireTheirRoleAndSessionAndAUniqueParticipantSource() throws Exception {
        User owner = recipient("player", 900, 31);
        owner.setId(20);
        for (String role : List.of("hunter", "player", "supporter", "spectator")) {
            User target = recipient(role, 900, 100);
            var alert = new GameNotificationService.Alert("live-" + role, "livePingReceived",
                    GameNotificationService.liveRecipientAudience(role), "2026-01-01T12:00:00Z",
                    "UTC", "Alice live ping", 10L, 31L);
            var request = new GameNotificationResource.CreateRequest(900, "generation", alert);
            when(journal.create(900, "generation", alert))
                    .thenReturn(new GameNotificationService.Creation(2, "generation", null, true));
            assertDoesNotThrow(() -> liveResource(target, List.of(owner)).create(request));
            assertThrows(ForbiddenException.class, () -> liveResource(target, List.of()).create(request));
            assertThrows(ForbiddenException.class, () -> liveResource(target, List.of(owner, owner)).create(request));
            assertThrows(ForbiddenException.class, () -> liveResource(recipient(role, 901, 100), List.of(owner))
                    .create(request));
            if (!"spectator".equals(role)) {
                assertThrows(ForbiddenException.class, () -> liveResource(recipient(role, 900, 31), List.of(owner))
                        .create(request));
            }
            target.setAdministrator(true);
            assertThrows(ForbiddenException.class, () -> liveResource(target, List.of(owner)).create(request));
        }
        verify(sockets, times(4)).updateGameNotifications(true, 900, 10L);
        var alert = new GameNotificationService.Alert("live-denied", "livePingReceived", "liveHunter",
                "2026-01-01T12:00:00Z", "UTC", "Alice live ping", 10L, 31L);
        var request = new GameNotificationResource.CreateRequest(900, "generation", alert);
        User target = recipient("hunter", 900, 100);
        assertThrows(ForbiddenException.class, () -> liveResource(recipient("player", 900, 100), List.of(owner))
                .create(request));
        assertThrows(ForbiddenException.class, () -> liveResource(target, List.of(recipient("hunter", 900, 31)))
                .create(request));
        assertThrows(ForbiddenException.class, () -> liveResource(target, List.of(recipient("player", 901, 31)))
                .create(request));
        var resource = liveResource(target, List.of(owner));
        var field = BaseResource.class.getDeclaredField("storage");
        field.setAccessible(true);
        Storage storage = (Storage) field.get(resource);
        var mirror = new Device();
        mirror.getAttributes().put("traccarToolMirror", true);
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(mirror);
        assertThrows(ForbiddenException.class, () -> resource.create(request));
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(new Device(), (Device) null);
        assertThrows(ForbiddenException.class, () -> resource.create(request));
    }
}
