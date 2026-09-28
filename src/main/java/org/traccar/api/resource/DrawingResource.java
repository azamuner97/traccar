/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.helper.LogAction;
import org.traccar.helper.model.DrawingUtil;
import org.traccar.model.Drawing;
import org.traccar.model.ObjectOperation;
import org.traccar.model.User;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("drawings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DrawingResource extends BaseResource {

    @Inject
    private CacheManager cacheManager;

    @Inject
    private ConnectionManager connectionManager;

    @Inject
    private LogAction actionLogger;

    @Context
    private HttpServletRequest request;

    private Condition visibilityCondition(User user) {
        if (user.getAdministrator()) {
            return null;
        }
        if (DrawingUtil.isSpectator(user)) {
            Long scope = DrawingUtil.getSessionDeviceId(user);
            return new Condition.Equals("sessionDeviceId", scope != null ? scope : 0);
        }
        return new Condition.Equals("ownerId", user.getId());
    }

    private void checkCreateOrEdit(User user) {
        if (!DrawingUtil.canCreateOrEdit(user)) {
            throw new SecurityException("Map drawing access is disabled");
        }
    }

    private Drawing getDrawing(long id) throws StorageException {
        return storage.getObject(Drawing.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
    }

    private boolean visible(User user, Drawing drawing) {
        return DrawingUtil.canView(user, drawing);
    }

    private void populateOwnerNames(List<Drawing> drawings) throws StorageException {
        Map<Long, String> names = new HashMap<>();
        for (Drawing drawing : drawings) {
            if (!names.containsKey(drawing.getOwnerId())) {
                User owner = storage.getObject(User.class, new Request(
                        new Columns.Include("name"),
                        new Condition.Equals("id", drawing.getOwnerId())));
                names.put(drawing.getOwnerId(), owner != null ? owner.getName() : "");
            }
            drawing.setOwnerName(names.get(drawing.getOwnerId()));
        }
    }

    private void invalidate(long id, ObjectOperation operation) throws Exception {
        cacheManager.invalidateObject(true, Drawing.class, id, operation);
        connectionManager.invalidateObject(true, Drawing.class, id, operation);
    }

    @GET
    public List<Drawing> get() throws StorageException {
        User user = permissionsService.getUser(getUserId());
        List<Drawing> drawings = storage.getObjects(Drawing.class, new Request(
                new Columns.All(), visibilityCondition(user)));
        populateOwnerNames(drawings);
        return drawings;
    }

    @Path("{id}")
    @GET
    public Response getSingle(@PathParam("id") long id) throws StorageException {
        User user = permissionsService.getUser(getUserId());
        Drawing drawing = getDrawing(id);
        if (drawing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (!visible(user, drawing)) {
            throw new SecurityException("Drawing access denied");
        }
        populateOwnerNames(List.of(drawing));
        return Response.ok(drawing).build();
    }

    @POST
    public Response add(Drawing drawing) throws Exception {
        User user = permissionsService.getUser(getUserId());
        checkCreateOrEdit(user);
        DrawingUtil.validate(drawing);
        drawing.setOwnerId(user.getId());
        drawing.setSessionDeviceId(DrawingUtil.getSessionDeviceId(user));
        drawing.setId(storage.addObject(drawing, new Request(new Columns.Exclude("id", "ownerName"))));
        drawing.setOwnerName(user.getName());
        actionLogger.create(request, getUserId(), drawing);
        invalidate(drawing.getId(), ObjectOperation.ADD);
        return Response.ok(drawing).build();
    }

    @Path("{id}")
    @PUT
    public Response update(@PathParam("id") long id, Drawing drawing) throws Exception {
        User user = permissionsService.getUser(getUserId());
        checkCreateOrEdit(user);
        Drawing before = getDrawing(id);
        if (before == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (!DrawingUtil.canEdit(user, before)) {
            throw new SecurityException("Only the drawing owner can edit it");
        }
        DrawingUtil.validate(drawing);
        drawing.setId(id);
        drawing.setOwnerId(before.getOwnerId());
        drawing.setSessionDeviceId(before.getSessionDeviceId());
        storage.updateObject(drawing, new Request(
                new Columns.Exclude("id", "ownerName"), new Condition.Equals("id", id)));
        drawing.setOwnerName(user.getName());
        actionLogger.edit(request, getUserId(), drawing);
        invalidate(id, ObjectOperation.UPDATE);
        return Response.ok(drawing).build();
    }

    @Path("{id}")
    @DELETE
    public Response remove(@PathParam("id") long id) throws Exception {
        User user = permissionsService.getUser(getUserId());
        Drawing drawing = getDrawing(id);
        if (drawing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (!DrawingUtil.canDelete(user, drawing)) {
            throw new SecurityException("Only the drawing owner can delete it");
        }
        storage.removeObject(Drawing.class, new Request(new Condition.Equals("id", id)));
        actionLogger.remove(request, getUserId(), Drawing.class, id);
        invalidate(id, ObjectOperation.DELETE);
        return Response.noContent().build();
    }

}
