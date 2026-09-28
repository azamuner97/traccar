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
import static org.mockito.Mockito.when;

public class ConnectionManagerTest {

    private ConnectionManager connectionManager;
    private ConnectionManager.UpdateListener listener;

    @BeforeEach
    public void setUp() throws Exception {
        Storage storage = mock(Storage.class);
        when(storage.getObjects(eq(Device.class), any(Request.class))).thenReturn(List.of());

        connectionManager = new ConnectionManager(
                new Config(),
                mock(CacheManager.class),
                storage,
                mock(NotificationManager.class),
                mock(BroadcastService.class),
                mock(DeviceLookupService.class));

        listener = mock(ConnectionManager.UpdateListener.class);
        connectionManager.addListener(1, listener);
        reset(listener);
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

        reset(listener);
        connectionManager.invalidateObject(true, User.class, 2, ObjectOperation.UPDATE);
        verify(listener, never()).onUpdateUser();
    }

}
