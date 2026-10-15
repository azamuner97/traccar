package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.traccar.api.BaseResource;
import org.traccar.database.GameNotificationService;
import org.traccar.database.GameProximityService;
import org.traccar.model.Device;
import org.traccar.model.UserRestrictions;
import org.traccar.model.User;
import org.traccar.session.ConnectionManager;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Path("game-proximity")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GameProximityResource extends BaseResource {
    public record Access(long deviceId, String expiresAt) { }

    @Inject
    private GameProximityService service;
    @Inject
    private ConnectionManager connectionManager;

    private void checkManager(long sessionId) throws Exception {
        try {
            permissionsService.checkManager(getUserId());
            permissionsService.checkRestriction(getUserId(), UserRestrictions::getReadonly);
            permissionsService.checkPermission(Device.class, getUserId(), sessionId);
        } catch (SecurityException error) {
            throw new ForbiddenException(error.getMessage());
        }
    }

    @GET
    @Path("session/{id}")
    public GameProximityService.Snapshot get(@PathParam("id") long sessionId) throws Exception {
        checkManager(sessionId);
        return service.get(sessionId);
    }

    @PUT
    @Path("session/{id}")
    public GameProximityService.Snapshot replace(
            @PathParam("id") long sessionId, GameProximityService.Snapshot snapshot) throws Exception {
        checkManager(sessionId);
        var before = service.get(sessionId);
        if (snapshot != null && snapshot.grants() != null) {
            for (var grant : snapshot.grants()) {
                if (grant != null) {
                    permissionsService.checkPermission(Device.class, getUserId(), grant.sourceDeviceId());
                    permissionsService.checkPermission(Device.class, getUserId(), grant.targetDeviceId());
                }
            }
        }
        var after = service.replace(sessionId, snapshot);
        Set<Long> users = new HashSet<>();
        before.grants().forEach(grant -> users.add(grant.recipientUserId()));
        after.grants().forEach(grant -> users.add(grant.recipientUserId()));
        if (!before.equals(after)) {
            connectionManager.updateGameProximity(true, sessionId, users);
        }
        return after;
    }

    @GET
    @Path("session/{id}/circles")
    public List<GameProximityService.PublicCircle> circles(@PathParam("id") long sessionId) throws Exception {
        var user = permissionsService.getUser(getUserId());
        if (!GameNotificationService.canReceive(user, sessionId) || user.getDisabled()
                || !Set.of("hunter", "spectator").contains(user.getAttributes().get("traccarToolUserRole"))) {
            throw new ForbiddenException("Proximity circles are restricted to session hunters and spectators");
        }
        try {
            permissionsService.checkPermission(Device.class, getUserId(), sessionId);
        } catch (SecurityException error) {
            throw new ForbiddenException(error.getMessage());
        }
        return service.circles(sessionId);
    }

    @GET
    @Path("session/{id}/access")
    public List<Access> access(@PathParam("id") long sessionId) throws Exception {
        var user = permissionsService.getUser(getUserId());
        if (!GameNotificationService.canReceive(user, sessionId) || user.getDisabled()
                || !Set.of("player", "supporter").contains(user.getAttributes().get("traccarToolUserRole"))) {
            throw new ForbiddenException("Proximity access belongs to session participants");
        }
        permissionsService.checkPermission(Device.class, getUserId(), sessionId);
        Set<Long> permanent = new HashSet<>();
        storage.getObjects(Device.class, new Request(new Columns.Include("id"),
                new Condition.Permission(User.class, getUserId(), Device.class).excludeTemporary()))
                .forEach(device -> permanent.add(device.getId()));
        return service.get(sessionId).grants().stream()
                .filter(grant -> grant.recipientUserId() == getUserId() && !permanent.contains(grant.targetDeviceId()))
                .map(grant -> new Access(grant.targetDeviceId(), grant.expiresAt())).toList();
    }
}
