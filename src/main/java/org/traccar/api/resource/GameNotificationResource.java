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
import org.traccar.model.UserRestrictions;
import org.traccar.session.ConnectionManager;

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

    @GET
    public GameNotificationService.Page list(
            @QueryParam("sessionDeviceId") long deviceId, @QueryParam("generation") String generation,
            @QueryParam("after") String after, @QueryParam("limit") int limit) throws Exception {
        try {
            var user = permissionsService.getUser(getUserId());
            permissionsService.checkPermission(Device.class, getUserId(), deviceId);
            if (!user.getAdministrator() && !GameNotificationService.canReceive(user, deviceId)) {
                throw new SecurityException("Notification session access denied");
            }
        } catch (SecurityException error) {
            throw new ForbiddenException(error.getMessage());
        }
        return service.list(deviceId, generation, after, limit);
    }

    @POST
    public GameNotificationService.Creation create(CreateRequest request) throws Exception {
        if (request == null) {
            throw new jakarta.ws.rs.BadRequestException("Notification required");
        }
        checkWrite(request.sessionDeviceId());
        var result = service.create(request.sessionDeviceId(), request.generation(), request.alert());
        if (result.created()) {
            connectionManager.updateGameNotifications(true, request.sessionDeviceId());
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
        connectionManager.updateGameNotifications(true, deviceId);
        return result;
    }
}
