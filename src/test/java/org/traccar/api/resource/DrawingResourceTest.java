package org.traccar.api.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.traccar.api.BaseResource;
import org.traccar.api.security.PermissionsService;
import org.traccar.helper.LogAction;
import org.traccar.helper.model.DrawingUtil;
import org.traccar.model.Drawing;
import org.traccar.model.User;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class DrawingResourceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private void inject(Object object, Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private User user(long id, String role, long session) {
        User user = new User();
        user.setId(id);
        user.setName("Owner " + id);
        user.setDisableDrawings(false);
        user.getAttributes().put(DrawingUtil.ATTRIBUTE_USER_ROLE, role);
        user.getAttributes().put(DrawingUtil.ATTRIBUTE_SESSION_DEVICE_ID, session);
        user.getAttributes().put(DrawingUtil.ATTRIBUTE_SHARE_GROUP_DRAWINGS, true);
        return user;
    }

    private Drawing drawing(long ownerId) throws Exception {
        Drawing drawing = new Drawing();
        drawing.setId(50);
        drawing.setOwnerId(ownerId);
        drawing.setSessionDeviceId(900L);
        drawing.setType("line");
        drawing.setColor("#FF0000");
        drawing.setGeometry(mapper.readTree("{\"type\":\"LineString\",\"coordinates\":[[7,46],[8,47]]}"));
        return drawing;
    }

    private DrawingResource resource(User actor, User owner, Drawing drawing, Storage storage) throws Exception {
        when(storage.getObject(eq(User.class), any(Request.class))).thenAnswer(invocation -> {
            var condition = (Condition.Equals) ((Request) invocation.getArgument(1)).getCondition();
            return ((Number) condition.getValue()).longValue() == actor.getId() ? actor : owner;
        });
        when(storage.getObject(eq(Drawing.class), any(Request.class))).thenReturn(drawing);
        when(storage.getObjects(eq(Drawing.class), any(Request.class))).thenReturn(List.of(drawing));
        DrawingResource resource = new DrawingResource() {
            @Override
            protected long getUserId() {
                return actor.getId();
            }
        };
        inject(resource, BaseResource.class, "storage", storage);
        inject(resource, BaseResource.class, "permissionsService", new PermissionsService(storage));
        inject(resource, DrawingResource.class, "cacheManager", mock(CacheManager.class));
        inject(resource, DrawingResource.class, "connectionManager", mock(ConnectionManager.class));
        inject(resource, DrawingResource.class, "actionLogger", mock(LogAction.class));
        return resource;
    }

    @Test
    public void testSharedListDetailUpdateAndDeletePreserveOwnership() throws Exception {
        for (String role : List.of("player", "supporter", "hunter")) {
            User actor = user(10, role, 900);
            User owner = user(11, role, 900);
            Drawing before = drawing(11);
            Storage storage = mock(Storage.class);
            DrawingResource resource = resource(actor, owner, before, storage);
            assertEquals(1, resource.get().size());
            assertTrue(before.getCanEdit());
            assertTrue(before.getCanDelete());
            assertEquals(200, resource.getSingle(50).getStatus());
            Drawing payload = drawing(99);
            payload.setSessionDeviceId(901L);
            Drawing updated = (Drawing) resource.update(50, payload).getEntity();
            assertEquals(11, updated.getOwnerId());
            assertEquals(900L, updated.getSessionDeviceId());
            assertEquals("Owner 11", updated.getOwnerName());
            assertTrue(updated.getCanEdit());
            assertEquals(204, resource.remove(50).getStatus());
        }
    }

    @Test
    public void testDeniedPeersCannotFetchOrMutateEvenWithForgedCapabilities() throws Exception {
        User owner = user(11, "hunter", 900);
        for (User actor : List.of(user(10, "player", 900), user(10, "hunter", 901))) {
            Drawing before = drawing(11);
            before.setCanEdit(true);
            before.setCanDelete(true);
            Storage storage = mock(Storage.class);
            DrawingResource resource = resource(actor, owner, before, storage);
            assertTrue(resource.get().isEmpty());
            assertThrows(SecurityException.class, () -> resource.getSingle(50));
            assertThrows(SecurityException.class, () -> resource.update(50, drawing(10)));
            assertThrows(SecurityException.class, () -> resource.remove(50));
            verify(storage, never()).updateObject(any(), any());
            verify(storage, never()).removeObject(any(), any());
        }
    }

    @Test
    public void testRevocationAndDisabledOwnerRemainViewableOnlyToOwner() throws Exception {
        User actor = user(10, "hunter", 900);
        User owner = user(11, "hunter", 900);
        Drawing before = drawing(11);
        DrawingResource peer = resource(actor, owner, before, mock(Storage.class));
        assertEquals(1, peer.get().size());
        owner.setDisableDrawings(true);
        assertTrue(peer.get().isEmpty());
        DrawingResource own = resource(owner, owner, before, mock(Storage.class));
        assertEquals(1, own.get().size());
        assertFalse(before.getCanEdit());
        assertFalse(before.getCanDelete());
    }

    @Test
    public void testCapabilitiesAreSerializedButCannotBeSuppliedByClients() throws Exception {
        Drawing drawing = drawing(11);
        drawing.setCanEdit(true);
        drawing.setCanDelete(true);
        var json = mapper.readTree(mapper.writeValueAsString(drawing));
        assertTrue(json.get("canEdit").asBoolean());
        assertTrue(json.get("canDelete").asBoolean());
        assertFalse(json.has("sessionDeviceId"));
        Drawing supplied = mapper.readValue("{\"canEdit\":true,\"canDelete\":true}", Drawing.class);
        assertFalse(supplied.getCanEdit());
        assertFalse(supplied.getCanDelete());
    }
}
