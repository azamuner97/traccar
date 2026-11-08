/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.api.resource;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.HeaderParam;
import org.traccar.api.BaseResource;
import org.traccar.database.GameControllerService;
import org.traccar.database.GameEffectsService;
import org.traccar.database.GamePushOutbox;
import org.traccar.database.GameSessionService;
import org.traccar.model.Device;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;

import java.util.Date;
import java.util.List;

/**
 * Central game-session storage. Operators are Traccar administrators in this version, so every endpoint
 * requires administrator access. Writes that change what a session list or settings view shows notify
 * administrators' WebSocket clients ({@value #INVALIDATION}); runtime saves and effects do not.
 * {@code GameControllerFilter} fences writes with the controller lease: runtime, resources, timetable progress
 * and effects always, and every write of the executing session.
 */
@Path("game-sessions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GameSessionResource extends BaseResource {

    public record Capabilities(int apiVersion) { }
    public record CreateRequest(String name, Integer schemaVersion, JsonNode config) { }
    public record RenameRequest(Long expectedRevision, String name) { }
    public record DocumentUpdate(Long expectedRevision, Integer schemaVersion, JsonNode document) { }
    public record ResourcesUpdate(Long expectedRevision, Long sessionDeviceId, JsonNode resources,
                                  List<GameSessionService.Member> members) { }
    public record TimetablePublish(Long expectedRevision, JsonNode columns, JsonNode source,
                                   List<GameSessionService.EntryInput> entries) { }
    public record ProgressUpdate(List<GameSessionService.Progress> entries) { }
    public record LogAppend(List<GameSessionService.LogInput> entries) { }

    public static final String INVALIDATION = "game-sessions";

    @Inject
    private GameSessionService service;

    @Inject
    private GameEffectsService effects;

    @Inject
    private GamePushOutbox outbox;

    @Inject
    private ConnectionManager connectionManager;

    @Inject
    private CacheManager cacheManager;

    private <T> T changed(T result) {
        connectionManager.updateGameAdmin(true, INVALIDATION);
        return result;
    }

    private void checkOperator() throws Exception {
        try {
            permissionsService.checkAdmin(getUserId());
        } catch (SecurityException error) {
            throw new ForbiddenException(error.getMessage());
        }
    }

    private static long expected(Long value) {
        if (value == null || value < 0) {
            throw new BadRequestException("expectedRevision is required");
        }
        return value;
    }

    @GET
    @Path("capabilities")
    public Capabilities capabilities() throws Exception {
        checkOperator();
        return new Capabilities(GameSessionService.API_VERSION);
    }

    @GET
    public List<GameSessionService.Summary> list() throws Exception {
        checkOperator();
        return service.list();
    }

    @POST
    public GameSessionService.Session create(CreateRequest request) throws Exception {
        checkOperator();
        if (request == null || request.schemaVersion() == null) {
            throw new BadRequestException("name, schemaVersion and config are required");
        }
        return changed(service.create(request.name(), request.schemaVersion(), request.config()));
    }

    @GET
    @Path("{id}")
    public GameSessionService.Session get(@PathParam("id") long id) throws Exception {
        checkOperator();
        return service.get(id);
    }

    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") long id) throws Exception {
        checkOperator();
        service.delete(id);
        return changed(Response.noContent().build());
    }

    @PUT
    @Path("{id}/name")
    public GameSessionService.Revision rename(@PathParam("id") long id, RenameRequest request) throws Exception {
        checkOperator();
        if (request == null) {
            throw new BadRequestException("Rename request is required");
        }
        return changed(service.rename(id, expected(request.expectedRevision()), request.name()));
    }

    @PUT
    @Path("{id}/config")
    public GameSessionService.Revision updateConfig(@PathParam("id") long id, DocumentUpdate request)
            throws Exception {
        checkOperator();
        if (request == null) {
            throw new BadRequestException("Configuration update is required");
        }
        return changed(service.updateConfig(id, expected(request.expectedRevision()), request.schemaVersion(),
                request.document()));
    }

    @PUT
    @Path("{id}/resources")
    public GameSessionService.Revision updateResources(@PathParam("id") long id, ResourcesUpdate request)
            throws Exception {
        checkOperator();
        if (request == null) {
            throw new BadRequestException("Resource update is required");
        }
        return changed(service.updateResources(id, expected(request.expectedRevision()),
                request.sessionDeviceId(), request.resources(), request.members()));
    }

    @PUT
    @Path("{id}/runtime")
    public GameSessionService.Revision updateRuntime(@PathParam("id") long id, DocumentUpdate request)
            throws Exception {
        checkOperator();
        if (request == null) {
            throw new BadRequestException("Runtime update is required");
        }
        // Runtime saves follow every game action; they change nothing an administrator list shows.
        return service.updateRuntime(id, expected(request.expectedRevision()), request.document());
    }

    @GET
    @Path("{id}/credentials")
    public GameSessionService.Document getCredentials(@PathParam("id") long id) throws Exception {
        checkOperator();
        return service.getCredentials(id);
    }

    @PUT
    @Path("{id}/credentials")
    public GameSessionService.Revision updateCredentials(@PathParam("id") long id, DocumentUpdate request)
            throws Exception {
        checkOperator();
        if (request == null) {
            throw new BadRequestException("Credentials update is required");
        }
        return changed(service.updateCredentials(id, expected(request.expectedRevision()), request.document()));
    }

    @GET
    @Path("{id}/geofence-plans")
    public GameSessionService.Document getGeofencePlans(@PathParam("id") long id) throws Exception {
        checkOperator();
        return service.getGeofencePlans(id);
    }

    @PUT
    @Path("{id}/geofence-plans")
    public GameSessionService.Revision updateGeofencePlans(@PathParam("id") long id, DocumentUpdate request)
            throws Exception {
        checkOperator();
        if (request == null) {
            throw new BadRequestException("Geofence plan update is required");
        }
        return changed(service.updateGeofencePlans(id, expected(request.expectedRevision()),
                request.document()));
    }

    @GET
    @Path("{id}/timetable")
    public GameSessionService.Timetable getTimetable(@PathParam("id") long id) throws Exception {
        checkOperator();
        return service.getTimetable(id);
    }

    @PUT
    @Path("{id}/timetable")
    public GameSessionService.Published publishTimetable(@PathParam("id") long id, TimetablePublish request)
            throws Exception {
        checkOperator();
        if (request == null) {
            throw new BadRequestException("Timetable is required");
        }
        return changed(service.publishTimetable(id, expected(request.expectedRevision()), request.columns(),
                request.source(), request.entries()));
    }

    @PUT
    @Path("{id}/timetable/progress")
    public Response updateTimetableProgress(@PathParam("id") long id, ProgressUpdate request) throws Exception {
        checkOperator();
        service.updateTimetableProgress(id, request == null ? null : request.entries());
        return Response.noContent().build();
    }

    @GET
    @Path("{id}/logs")
    public GameSessionService.LogPage listLogs(
            @PathParam("id") long id, @QueryParam("after") String after, @QueryParam("limit") int limit)
            throws Exception {
        checkOperator();
        return service.listLogs(id, after, limit);
    }

    @POST
    @Path("{id}/logs")
    public Response appendLogs(@PathParam("id") long id, LogAppend request) throws Exception {
        checkOperator();
        service.appendLogs(id, request == null ? null : request.entries());
        return Response.noContent().build();
    }

    /**
     * Applies one fenced, idempotent game operation. After commit, latest positions reach the cache and
     * WebSocket subscribers like tracker uploads, destination devices show as online with a new last-update
     * time, archived notifications are announced, and queued pushes are sent from the outbox.
     */
    @POST
    @Path("{id}/effects")
    public GameEffectsService.Outcome applyEffects(
            @PathParam("id") long id, @HeaderParam(GameControllerService.EPOCH_HEADER) String header,
            GameEffectsService.Delivery delivery) throws Exception {
        checkOperator();
        Long epoch = GameControllerService.parseEpoch(header);
        if (epoch == null) {
            throw GameControllerService.locked("Effects require the game controller lease");
        }
        var alerts = effects.validate(delivery);
        for (var alert : alerts) {
            new GameNotificationTargets(storage, permissionsService).check(
                    getUserId(), delivery.notifications().sessionDeviceId(), alert);
        }
        var applied = effects.apply(id, epoch, delivery, alerts);
        Date now = new Date();
        for (long deviceId : applied.devices()) {
            connectionManager.updateDevice(deviceId, Device.STATUS_ONLINE, now);
        }
        for (var position : applied.latest()) {
            cacheManager.updatePosition(position);
            connectionManager.updatePosition(true, position);
        }
        if (applied.notificationDevice() != null) {
            if (applied.sessionNotified()) {
                connectionManager.updateGameNotifications(true, applied.notificationDevice(), null);
            }
            for (long recipient : applied.notifiedRecipients()) {
                connectionManager.updateGameNotifications(true, applied.notificationDevice(), recipient);
            }
        }
        outbox.dispatch(applied.queuedPushes());
        return applied.outcome();
    }
}
