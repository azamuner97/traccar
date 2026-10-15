package org.traccar.session;

import io.netty.channel.Channel;
import org.traccar.Protocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.broadcast.BroadcastService;
import org.traccar.config.Config;
import org.traccar.database.DeviceLookupService;
import org.traccar.database.NotificationManager;
import org.traccar.model.Device;
import org.traccar.model.Drawing;
import org.traccar.model.Event;
import org.traccar.model.Geofence;
import org.traccar.model.Group;
import org.traccar.model.Notification;
import org.traccar.model.LogRecord;
import org.traccar.model.ObjectOperation;
import org.traccar.model.User;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.util.List;
import java.net.InetSocketAddress;

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
    private DeviceLookupService deviceLookupService;

    @BeforeEach
    public void setUp() throws Exception {
        storage = mock(Storage.class);
        when(storage.getObjects(eq(Device.class), any(Request.class))).thenReturn(List.of());
        broadcastService = mock(BroadcastService.class);
        deviceLookupService = mock(DeviceLookupService.class);

        connectionManager = new ConnectionManager(
                new Config(),
                mock(CacheManager.class),
                storage,
                mock(NotificationManager.class),
                broadcastService,
                deviceLookupService);

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
    public void testRevokedAuthorizationStopsEveryDeviceSocketPayloadBeforeCacheRefresh() throws Exception {
        Device device = new Device();
        device.setId(100);
        device.setUniqueId("proximity-fixture");
        when(storage.getObjects(eq(Device.class), any(Request.class))).thenReturn(List.of(device));
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(device);
        connectionManager.removeListener(1, listener);
        connectionManager.addListener(1, listener);
        Channel channel = mock(Channel.class);
        var local = new InetSocketAddress("127.0.0.1", 5056);
        var remote = new InetSocketAddress("127.0.0.1", 12345);
        when(channel.localAddress()).thenReturn(local);
        when(deviceLookupService.lookup(any(String[].class))).thenReturn(device);
        connectionManager.getDeviceSession(mock(Protocol.class), channel, remote, "proximity-fixture");
        var position = new Position();
        position.setDeviceId(100);
        var event = new Event();
        event.setDeviceId(100);
        var log = new LogRecord(local, remote);
        connectionManager.updateDevice(false, device);
        connectionManager.updatePosition(false, position);
        connectionManager.updateEvent(false, 1, event);
        connectionManager.updateLog(log);
        verify(listener).onUpdateDevice(device);
        verify(listener).onUpdatePosition(position);
        verify(listener).onUpdateEvent(event);
        verify(listener).onUpdateLog(log);
        reset(listener);
        when(storage.getObject(eq(Device.class), any(Request.class))).thenReturn(null);
        connectionManager.updateDevice(false, device);
        connectionManager.updatePosition(false, position);
        connectionManager.updateEvent(false, 1, event);
        connectionManager.updateLog(log);
        verifyNoInteractions(listener);
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
    public void testDeviceAndGroupPermissionChangesRevalidateOnlyAffectedNotificationHistory() {
        connectionManager.invalidatePermission(true, User.class, 1, Device.class, 100, false);
        connectionManager.invalidatePermission(false, User.class, 1, Group.class, 200, false);
        connectionManager.invalidatePermission(true, User.class, 1, Device.class, 100, true);
        verify(listener, times(3)).onUpdateGameNotifications();
        verify(secondListener, never()).onUpdateGameNotifications();
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
