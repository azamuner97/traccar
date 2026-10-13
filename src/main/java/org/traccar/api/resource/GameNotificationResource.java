/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.traccar.api.BaseResource;
import org.traccar.database.GameNotificationService;
import org.traccar.model.Device;
import org.traccar.model.User;
import org.traccar.model.UserRestrictions;
import org.traccar.session.ConnectionManager;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

@Path("game-notifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GameNotificationResource extends BaseResource {

    public record CreateRequest(long sessionDeviceId, String generation, GameNotificationService.Alert alert) { }
    public record ResetRequest(String expectedGeneration, String replacementGeneration) { }

    @Inject
    private GameNotificationService service;
    @Inject
    private ConnectionManager connectionManager;

    private void checkWrite(long deviceId) throws Exception {
        try {
            permissionsService.checkManager(getUserId());
            permissionsService.checkRestriction(getUserId(), UserRestrictions::getReadonly);
            permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        } catch (SecurityException error) {
            throw new ForbiddenException(error.getMessage());
        }
    }

    private void checkTarget(GameNotificationService.Alert alert, long sessionDeviceId) throws Exception {
        try {
            User recipient = storage.getObject(User.class, new Request(
                    new Columns.All(), new Condition.Equals("id", alert.recipientUserId())));
            String role = recipient == null ? null : String.valueOf(
                    recipient.getAttributes().get("traccarToolUserRole"));
            if (recipient == null || recipient.getAdministrator()
                    || !("player".equals(role) || "supporter".equals(role) || "hunter".equals(role))
                    || !GameNotificationService.canReceive(recipient, sessionDeviceId)
                    || !Long.toString(alert.sourceDeviceId()).equals(String.valueOf(
                            recipient.getAttributes().get(GameNotificationService.ASSIGNED_DEVICE_ATTRIBUTE)))) {
                throw new SecurityException("Notification recipient does not match the source device");
            }
            Device permittedSource = storage.getObject(Device.class, new Request(
                    new Columns.Include("id"),
                    new Condition.And(
                            new Condition.Equals("id", alert.sourceDeviceId()),
                            new Condition.Permission(User.class, recipient.getId(), Device.class))));
            if (permittedSource == null) {
                throw new SecurityException("Notification recipient cannot access the source device");
            }
        } catch (SecurityException error) {
            throw new ForbiddenException(error.getMessage());
        }
    }

    private void checkLiveRecipient(GameNotificationService.Alert alert, long sessionDeviceId) throws Exception {
        try {
            User recipient = storage.getObject(User.class, new Request(
                    new Columns.All(), new Condition.Equals("id", alert.recipientUserId())));
            String role = recipient == null ? null : String.valueOf(
                    recipient.getAttributes().get("traccarToolUserRole"));
            if (!GameNotificationService.canReceive(recipient, sessionDeviceId)
                    || !GameNotificationService.liveRecipientAudience(role).equals(alert.audience())
                    || alert.sourceDeviceId().equals(GameNotificationService.assignedDeviceId(recipient))) {
                throw new SecurityException("Live notification recipient is not eligible");
            }
            permissionsService.checkPermission(Device.class, getUserId(), alert.sourceDeviceId());
            Device source = storage.getObject(Device.class, new Request(
                    new Columns.All(), new Condition.Equals("id", alert.sourceDeviceId())));
            if (source == null || Boolean.TRUE.equals(source.getAttributes().get("traccarToolMirror"))) {
                throw new SecurityException("Live notification source is not a real device");
            }
            var owners = storage.getObjects(User.class, new Request(new Columns.All())).stream()
                    .filter(user -> GameNotificationService.canReceive(user, sessionDeviceId))
                    .filter(user -> "player".equals(user.getAttributes().get("traccarToolUserRole"))
                            || "supporter".equals(user.getAttributes().get("traccarToolUserRole")))
                    .filter(user -> alert.sourceDeviceId().equals(GameNotificationService.assignedDeviceId(user)))
                    .toList();
            if (owners.size() != 1) {
                throw new SecurityException("Live notification source has no unique participant in this session");
            }
            Device permittedSource = storage.getObject(Device.class, new Request(new Columns.Include("id"),
                    new Condition.And(new Condition.Equals("id", alert.sourceDeviceId()),
                            new Condition.Permission(User.class, owners.getFirst().getId(), Device.class))));
            if (permittedSource == null) {
                throw new SecurityException("Live notification source owner cannot access the device");
            }
        } catch (SecurityException error) {
            throw new ForbiddenException(error.getMessage());
        }
    }

    @GET
    public GameNotificationService.Page list(
            @QueryParam("sessionDeviceId") long deviceId, @QueryParam("generation") String generation,
            @QueryParam("after") String after, @QueryParam("limit") int limit) throws Exception {
        User user;
        try {
            user = permissionsService.getUser(getUserId());
            permissionsService.checkPermission(Device.class, getUserId(), deviceId);
            if (!user.getAdministrator() && !GameNotificationService.canReceive(user, deviceId)) {
                throw new SecurityException("Notification session access denied");
            }
        } catch (SecurityException error) {
            throw new ForbiddenException(error.getMessage());
        }
        Long sourceDeviceId = GameNotificationService.assignedDeviceId(user);
        if (sourceDeviceId != null) {
            Device source = storage.getObject(Device.class, new Request(new Columns.Include("id"),
                    new Condition.And(new Condition.Equals("id", sourceDeviceId),
                            new Condition.Permission(User.class, user.getId(), Device.class))));
            if (source == null) {
                sourceDeviceId = null;
            }
        }
        return service.list(deviceId, generation, after, limit, user.getAdministrator()
                ? null : new GameNotificationService.ReadAccess(user.getId(), sourceDeviceId,
                        String.valueOf(user.getAttributes().get("traccarToolUserRole"))));
    }

    @POST
    public GameNotificationService.Creation create(CreateRequest request) throws Exception {
        if (request == null) {
            throw new jakarta.ws.rs.BadRequestException("Notification required");
        }
        checkWrite(request.sessionDeviceId());
        var alert = GameNotificationService.validate(request.alert());
        if ("user".equals(alert.audience())) {
            checkTarget(alert, request.sessionDeviceId());
        } else if (GameNotificationService.isLiveRecipientAlert(alert)) {
            checkLiveRecipient(alert, request.sessionDeviceId());
        }
        var result = service.create(request.sessionDeviceId(), request.generation(), alert);
        if (result.created()) {
            connectionManager.updateGameNotifications(true, request.sessionDeviceId(), alert.recipientUserId());
        }
        return result;
    }

    @POST
    @Path("session/{id}")
    public GameNotificationService.State initialize(@PathParam("id") long deviceId) throws Exception {
        checkWrite(deviceId);
        return service.initialize(deviceId);
    }

    @POST
    @Path("session/{id}/reset")
    public GameNotificationService.State reset(@PathParam("id") long deviceId, ResetRequest request) throws Exception {
        if (request == null) {
            throw new jakarta.ws.rs.BadRequestException("Reset required");
        }
        checkWrite(deviceId);
        var result = service.reset(deviceId, request.expectedGeneration(), request.replacementGeneration());
        connectionManager.updateGameNotifications(true, deviceId, null);
        return result;
    }
}
