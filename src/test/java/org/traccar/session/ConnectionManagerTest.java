package org.traccar.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.broadcast.BroadcastService;
import org.traccar.config.Config;
import org.traccar.database.DeviceLookupService;
import org.traccar.database.NotificationManager;
import org.traccar.model.Device;
import org.traccar.model.Drawing;
import org.traccar.model.Geofence;
import org.traccar.model.Group;
import org.traccar.model.Notification;
import org.traccar.model.ObjectOperation;
import org.traccar.model.User;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class ConnectionManagerTest {

    private ConnectionManager connectionManager;
    private ConnectionManager.UpdateListener listener;
    private ConnectionManager.UpdateListener secondListener;
    private Storage storage;
    private BroadcastService broadcastService;

    @BeforeEach
    public void setUp() throws Exception {
        storage = mock(Storage.class);
        when(storage.getObjects(eq(Device.class), any(Request.class))).thenReturn(List.of());
        broadcastService = mock(BroadcastService.class);

        connectionManager = new ConnectionManager(
                new Config(),
                mock(CacheManager.class),
                storage,
                mock(NotificationManager.class),
                broadcastService,
                mock(DeviceLookupService.class));

        listener = mock(ConnectionManager.UpdateListener.class);
        secondListener = mock(ConnectionManager.UpdateListener.class);
        connectionManager.addListener(1, listener);
        connectionManager.addListener(2, secondListener);
        reset(listener, secondListener, broadcastService);
    }

    @Test
    public void testGeofenceObjectInvalidation() {
        connectionManager.invalidateObject(true, Geofence.class, 1, ObjectOperation.ADD);
        connectionManager.invalidateObject(true, Geofence.class, 1, ObjectOperation.UPDATE);
        connectionManager.invalidateObject(true, Geofence.class, 1, ObjectOperation.DELETE);

        verify(listener, times(3)).onUpdateGeofences();

        reset(listener);
        connectionManager.invalidateObject(true, Device.class, 1, ObjectOperation.UPDATE);
        verify(listener, never()).onUpdateGeofences();
    }

    @Test
    public void testGeofencePermissionInvalidation() {
        connectionManager.invalidatePermission(true, Device.class, 1, Geofence.class, 2, true);
        connectionManager.invalidatePermission(true, Geofence.class, 2, Device.class, 1, false);

        verify(listener, times(2)).onUpdateGeofences();

        reset(listener);
        connectionManager.invalidatePermission(true, Group.class, 1, Notification.class, 2, true);
        verify(listener, never()).onUpdateGeofences();
    }

    @Test
    public void testDrawingAndUserInvalidation() {
        connectionManager.invalidateObject(true, Drawing.class, 1, ObjectOperation.ADD);
        verify(listener).onUpdateDrawings();

        reset(listener);
        connectionManager.invalidateObject(true, User.class, 1, ObjectOperation.UPDATE);
        verify(listener).onUpdateUser();
        verify(listener).onUpdateDrawings();
        verify(secondListener, times(2)).onUpdateDrawings();

        reset(listener);
        connectionManager.invalidateObject(true, User.class, 2, ObjectOperation.UPDATE);
        verify(listener, never()).onUpdateUser();
    }

    @Test
    public void testRemoteAccountChangesInvalidateDrawingPermissionsForAllViewers() {
        connectionManager.invalidateObject(false, User.class, 1, ObjectOperation.UPDATE);
        connectionManager.invalidateObject(false, User.class, 1, ObjectOperation.DELETE);
        verify(listener, times(2)).onUpdateDrawings();
        verify(secondListener, times(2)).onUpdateDrawings();
    }

    @Test
    public void testTargetedGameNotificationInvalidationStaysPrivateAcrossBroadcast() throws Exception {
        User recipient = new User();
        recipient.setId(1);
        recipient.getAttributes().put("traccarToolUserRole", "hunter");
        recipient.getAttributes().put("traccarToolSessionDeviceId", 900);
        when(storage.getObject(eq(User.class), any(Request.class))).thenReturn(recipient);

        connectionManager.updateGameNotifications(true, 900, 1L);
        verify(broadcastService).updateGameNotifications(true, 900, 1L);
        verify(listener).onUpdateGameNotifications();
        verify(secondListener, never()).onUpdateGameNotifications();

        reset(listener, secondListener, broadcastService);
        connectionManager.updateGameNotifications(false, 900, 1L);
        verifyNoInteractions(broadcastService);
        verify(listener).onUpdateGameNotifications();
        verify(secondListener, never()).onUpdateGameNotifications();
    }

}
