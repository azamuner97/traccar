/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.traccar.api.BaseResource;
import org.traccar.database.GameControllerService;
import org.traccar.session.ConnectionManager;

/**
 * The server-wide game controller lease: status, acquisition, renewal, release, forced takeover, the executing
 * session, and history. Operators are Traccar administrators in this version.
 */
@Path("game-controller")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GameControllerResource extends BaseResource {

    public static final String INVALIDATION = "game-controller";

    public record InstanceRequest(String instanceId, String label) { }
    public record SessionRequest(Long gameSessionId) { }

    @Inject
    private GameControllerService controller;

    @Inject
    private ConnectionManager connectionManager;

    private void checkOperator() throws Exception {
        try {
            permissionsService.checkAdmin(getUserId());
        } catch (SecurityException error) {
            throw new ForbiddenException(error.getMessage());
        }
    }

    private static long epoch(String header) {
        Long value = GameControllerService.parseEpoch(header);
        if (value == null) {
            throw new BadRequestException(GameControllerService.EPOCH_HEADER + " header required");
        }
        return value;
    }

    private static InstanceRequest instance(InstanceRequest request) {
        if (request == null) {
            throw new BadRequestException("Controller instance required");
        }
        return request;
    }

    private void changed() {
        connectionManager.updateGameAdmin(true, INVALIDATION);
    }

    @GET
    public GameControllerService.Lease status() throws Exception {
        checkOperator();
        return controller.status();
    }

    @POST
    @Path("acquire")
    public GameControllerService.Acquisition acquire(InstanceRequest request) throws Exception {
        checkOperator();
        var result = controller.acquire(getUserId(), instance(request).instanceId(), request.label());
        if (result.acquired()) {
            changed();
        }
        return result;
    }

    @POST
    @Path("takeover")
    public GameControllerService.Acquisition takeover(InstanceRequest request) throws Exception {
        checkOperator();
        var result = controller.takeover(getUserId(), instance(request).instanceId(), request.label());
        changed();
        return result;
    }

    @POST
    @Path("renew")
    public GameControllerService.Lease renew(
            @HeaderParam(GameControllerService.EPOCH_HEADER) String header, InstanceRequest request)
            throws Exception {
        checkOperator();
        return controller.renew(getUserId(), epoch(header), instance(request).instanceId());
    }

    @POST
    @Path("release")
    public GameControllerService.Lease release(@HeaderParam(GameControllerService.EPOCH_HEADER) String header)
            throws Exception {
        checkOperator();
        var result = controller.release(getUserId(), epoch(header));
        changed();
        return result;
    }

    @PUT
    @Path("session")
    public GameControllerService.Lease session(
            @HeaderParam(GameControllerService.EPOCH_HEADER) String header, SessionRequest request)
            throws Exception {
        checkOperator();
        if (request == null) {
            throw new BadRequestException("Executing session required");
        }
        var result = controller.setExecuting(getUserId(), epoch(header), request.gameSessionId());
        changed();
        return result;
    }

    @GET
    @Path("history")
    public GameControllerService.HistoryPage history(
            @QueryParam("before") String before, @QueryParam("limit") int limit) throws Exception {
        checkOperator();
        return controller.history(before, limit);
    }
}
