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
import org.traccar.game.GameMemberService;
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

    @Inject
    private GameMemberService members;

    @Context
    private HttpServletRequest request;

    /** The game membership of an account, from its member row (data model 2), or null. */
    private DrawingUtil.Membership membership(long userId) throws StorageException {
        try {
            return members.find(userId)
                    .map(member -> new DrawingUtil.Membership(member.gameSessionId(), member.role()))
                    .orElse(null);
        } catch (StorageException error) {
            throw error;
        } catch (Exception error) {
            throw new StorageException(error);
        }
    }

    private Condition visibilityCondition(User user, DrawingUtil.Membership membership) {
        if (user.getAdministrator()) {
            return null;
        }
        if (DrawingUtil.isSpectator(membership)) {
            return new Condition.Equals("gameSessionId", membership.gameSessionId());
        }
        if (DrawingUtil.hasGroupSharing(user, membership)) {
            return new Condition.Or(new Condition.Equals("ownerId", user.getId()),
                    new Condition.Equals("gameSessionId", membership.gameSessionId()));
        }
        return new Condition.Equals("ownerId", user.getId());
    }

    private void checkCreateOrEdit(User user, DrawingUtil.Membership membership) {
        if (!DrawingUtil.canCreateOrEdit(user, membership)) {
            throw new SecurityException("Map drawing access is disabled");
        }
    }

    private Drawing getDrawing(long id) throws StorageException {
        return storage.getObject(Drawing.class, new Request(
                new Columns.All(), new Condition.Equals("id", id)));
    }

    private User getOwner(Drawing drawing) throws StorageException {
        return storage.getObject(User.class, new Request(
                new Columns.All(), new Condition.Equals("id", drawing.getOwnerId())));
    }

    /** A drawing's owner with the owner's game membership. */
    private record Owner(User user, DrawingUtil.Membership membership) {
    }

    private Owner getOwnerWithMembership(Drawing drawing) throws StorageException {
        User owner = getOwner(drawing);
        return new Owner(owner, owner != null ? membership(owner.getId()) : null);
    }

    private Map<Long, Owner> getOwners(List<Drawing> drawings) throws StorageException {
        Map<Long, Owner> owners = new HashMap<>();
        for (Drawing drawing : drawings) {
            if (!owners.containsKey(drawing.getOwnerId())) {
                owners.put(drawing.getOwnerId(), getOwnerWithMembership(drawing));
            }
        }
        return owners;
    }

    private void populateCapabilities(User user, DrawingUtil.Membership membership, Drawing drawing, Owner owner) {
        drawing.setOwnerName(owner.user() != null ? owner.user().getName() : "");
        drawing.setCanEdit(DrawingUtil.canEdit(user, membership, drawing, owner.user(), owner.membership()));
        drawing.setCanDelete(DrawingUtil.canDelete(user, membership, drawing, owner.user(), owner.membership()));
    }

    private void invalidate(long id, ObjectOperation operation) throws Exception {
        cacheManager.invalidateObject(true, Drawing.class, id, operation);
        connectionManager.invalidateObject(true, Drawing.class, id, operation);
    }

    @GET
    public List<Drawing> get() throws StorageException {
        User user = permissionsService.getUser(getUserId());
        DrawingUtil.Membership membership = membership(user.getId());
        List<Drawing> drawings = storage.getObjects(Drawing.class, new Request(
                new Columns.All(), visibilityCondition(user, membership)));
        Map<Long, Owner> owners = getOwners(drawings);
        drawings = drawings.stream()
                .filter(drawing -> {
                    Owner owner = owners.get(drawing.getOwnerId());
                    return DrawingUtil.canView(user, membership, drawing, owner.user(), owner.membership());
                })
                .toList();
        drawings.forEach(drawing -> populateCapabilities(
                user, membership, drawing, owners.get(drawing.getOwnerId())));
        return drawings;
    }

    @Path("{id}")
    @GET
    public Response getSingle(@PathParam("id") long id) throws StorageException {
        User user = permissionsService.getUser(getUserId());
        DrawingUtil.Membership membership = membership(user.getId());
        Drawing drawing = getDrawing(id);
        if (drawing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        Owner owner = getOwnerWithMembership(drawing);
        if (!DrawingUtil.canView(user, membership, drawing, owner.user(), owner.membership())) {
            throw new SecurityException("Drawing access denied");
        }
        populateCapabilities(user, membership, drawing, owner);
        return Response.ok(drawing).build();
    }

    @POST
    public Response add(Drawing drawing) throws Exception {
        User user = permissionsService.getUser(getUserId());
        DrawingUtil.Membership membership = membership(user.getId());
        checkCreateOrEdit(user, membership);
        DrawingUtil.validate(drawing);
        drawing.setOwnerId(user.getId());
        drawing.setGameSessionId(membership != null ? membership.gameSessionId() : null);
        drawing.setId(storage.addObject(drawing, new Request(new Columns.Exclude("id", "ownerName"))));
        populateCapabilities(user, membership, drawing, new Owner(user, membership));
        actionLogger.create(request, getUserId(), drawing);
        invalidate(drawing.getId(), ObjectOperation.ADD);
        return Response.ok(drawing).build();
    }

    @Path("{id}")
    @PUT
    public Response update(@PathParam("id") long id, Drawing drawing) throws Exception {
        User user = permissionsService.getUser(getUserId());
        DrawingUtil.Membership membership = membership(user.getId());
        checkCreateOrEdit(user, membership);
        Drawing before = getDrawing(id);
        if (before == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        Owner owner = getOwnerWithMembership(before);
        if (!DrawingUtil.canEdit(user, membership, before, owner.user(), owner.membership())) {
            throw new SecurityException("Drawing edit access denied");
        }
        DrawingUtil.validate(drawing);
        drawing.setId(id);
        drawing.setOwnerId(before.getOwnerId());
        drawing.setGameSessionId(before.getGameSessionId());
        storage.updateObject(drawing, new Request(
                new Columns.Exclude("id", "ownerName"), new Condition.Equals("id", id)));
        populateCapabilities(user, membership, drawing, owner);
        actionLogger.edit(request, getUserId(), drawing);
        invalidate(id, ObjectOperation.UPDATE);
        return Response.ok(drawing).build();
    }

    @Path("{id}")
    @DELETE
    public Response remove(@PathParam("id") long id) throws Exception {
        User user = permissionsService.getUser(getUserId());
        DrawingUtil.Membership membership = membership(user.getId());
        Drawing drawing = getDrawing(id);
        if (drawing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        Owner owner = getOwnerWithMembership(drawing);
        if (!DrawingUtil.canDelete(user, membership, drawing, owner.user(), owner.membership())) {
            throw new SecurityException("Drawing delete access denied");
        }
        storage.removeObject(Drawing.class, new Request(new Condition.Equals("id", id)));
        actionLogger.remove(request, getUserId(), Drawing.class, id);
        invalidate(id, ObjectOperation.DELETE);
        return Response.noContent().build();
    }

}
