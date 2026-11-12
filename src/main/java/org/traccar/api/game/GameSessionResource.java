/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.api.game;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.game.GameControllerService;
import org.traccar.game.GameDocumentService;
import org.traccar.game.GameEffectsService;
import org.traccar.game.GameJokerService;
import org.traccar.game.GameLogService;
import org.traccar.game.GameRequestService;
import org.traccar.game.GameResource;
import org.traccar.game.GameResourceService;
import org.traccar.game.GameSessionService;
import org.traccar.game.GameSnapshotService;
import org.traccar.game.GameTimetableService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Game sessions of data model 2 for operators (administrators): sessions and their settings, members and their
 * bindings, resource rows, documents and checkpoints, the timetable, logs, requests, jokers, effects deliveries and
 * the operator snapshot. Model 1's paths are kept where the concept is unchanged.
 *
 * <p>{@code GameControllerFilter} fences writes by path: effects, timetable progress, {@code members/*},
 * {@code resources/*}, {@code checkpoints/*}, {@code requests/*} and {@code jokers/*} always, and every write of the
 * executing session.
 */
@Path("game-sessions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GameSessionResource extends GameApiResource {

    public record Capabilities(int apiVersion) {
    }

    /** {@code settingsSchemaVersion} defaults to {@code schemaVersion}. */
    public record CreateRequest(String name, Integer schemaVersion, Integer settingsSchemaVersion, JsonNode config) {
    }

    /** The session name is configuration: it is renamed based on the settings revision. */
    public record RenameRequest(Long expectedRevision, String name) {
    }

    public record DocumentUpdate(Long expectedRevision, Integer schemaVersion, JsonNode document) {
    }

    public record Revision(long revision) {
    }

    public record MemberInput(String role, Integer number, String displayName) {
    }

    public record NameInput(String displayName) {
    }

    public record AccountInput(Long userId) {
    }

    public record TrackerInput(Long deviceId) {
    }

    public record MirrorInput(Long deviceId, Long sourceMemberId, String destinationRole, Long destinationMemberId) {
    }

    public record GroupInput(Long groupId, String purpose, Long memberId) {
    }

    public record GeofenceInput(Long geofenceId, String planKey, String purpose) {
    }

    public record RebindInput(Long resourceId) {
    }

    public record TimetablePublish(Long expectedRevision, JsonNode columns, JsonNode source,
                                   List<GameTimetableService.EntryInput> entries) {
    }

    public record ProgressUpdate(List<GameTimetableService.Progress> entries) {
    }

    public record LogAppend(List<GameLogService.LogInput> entries) {
    }

    public record RequestDecision(String decision, String message, String detail) {
    }

    /** An operator's plan of a member's break, from {@code startsAt} (now when missing or past). */
    public record BreakPlan(Long memberId, String startsAt, Boolean overAllocate) {
    }

    @Inject
    private GameSessionService sessions;

    @Inject
    private GameResourceService resources;

    @Inject
    private GameDocumentService documents;

    @Inject
    private GameTimetableService timetables;

    @Inject
    private GameLogService logs;

    @Inject
    private GameRequestService requests;

    @Inject
    private GameJokerService jokers;

    @Inject
    private GameEffectsService effects;

    @Inject
    private GameSnapshotService snapshots;

    private static long expected(Long value) {
        if (value == null || value < 0) {
            throw new BadRequestException("expectedRevision is required");
        }
        return value;
    }

    private static GameResource.Kind kind(String value) {
        return switch (value) {
            case "mirrors" -> GameResource.Kind.MIRROR;
            case "groups" -> GameResource.Kind.GROUP;
            case "geofences" -> GameResource.Kind.GEOFENCE;
            default -> throw new NotFoundException("Unknown game resource kind");
        };
    }

    // Sessions and settings ---------------------------------------------------------------------------------

    @GET
    @Path("capabilities")
    public Capabilities capabilities() throws Exception {
        checkOperator();
        return new Capabilities(GameSessionService.API_VERSION);
    }

    @GET
    public JsonNode list() throws Exception {
        checkOperator();
        return json(sessions.list());
    }

    @POST
    public JsonNode create(CreateRequest request) throws Exception {
        checkOperator();
        if (request == null || request.schemaVersion() == null) {
            throw new BadRequestException("name, schemaVersion and config are required");
        }
        int settingsSchemaVersion = request.settingsSchemaVersion() == null
                ? request.schemaVersion() : request.settingsSchemaVersion();
        return json(sessions.create(request.name(), request.schemaVersion(), settingsSchemaVersion,
                request.config()));
    }

    @GET
    @Path("{id}")
    public JsonNode get(@PathParam("id") long id) throws Exception {
        checkOperator();
        return json(sessions.get(id));
    }

    /** Deletes the session and every game record of it; its managed Traccar resources are cleaned up first. */
    @DELETE
    @Path("{id}")
    public Response delete(@PathParam("id") long id) throws Exception {
        checkOperator();
        sessions.delete(id);
        return Response.noContent().build();
    }

    @PUT
    @Path("{id}/name")
    public JsonNode rename(@PathParam("id") long id, RenameRequest request) throws Exception {
        checkOperator();
        required(request, "Name required");
        return json(sessions.rename(id, expected(request.expectedRevision()), request.name()));
    }

    /** Replaces the settings document (model 1's configuration) based on the settings revision. */
    @PUT
    @Path("{id}/config")
    public JsonNode updateSettings(@PathParam("id") long id, DocumentUpdate request) throws Exception {
        checkOperator();
        required(request, "Settings required");
        return json(sessions.updateSettings(id, expected(request.expectedRevision()),
                required(request.schemaVersion(), "schemaVersion is required"), request.document()));
    }

    /** Everything the Tool loads and recovers a session from, at one database snapshot. */
    @GET
    @Path("{id}/snapshot")
    public Response snapshot(@PathParam("id") long id) throws Exception {
        checkOperator();
        return noStore(snapshots.snapshot(id));
    }

    // Members -----------------------------------------------------------------------------------------------

    @GET
    @Path("{id}/members")
    public JsonNode members(@PathParam("id") long id) throws Exception {
        checkOperator();
        return json(members().list(id));
    }

    /** Plans a member without account or tracker; without a number it gets the next one never used. */
    @POST
    @Path("{id}/members")
    public JsonNode plan(@PathParam("id") long id, MemberInput input) throws Exception {
        checkOperator();
        required(input, "Member required");
        return json(members().plan(id, input.role(), input.number(), input.displayName()));
    }

    @GET
    @Path("{id}/members/{memberId}")
    public JsonNode member(@PathParam("id") long id, @PathParam("memberId") long memberId) throws Exception {
        checkOperator();
        return json(members().get(id, memberId));
    }

    @PUT
    @Path("{id}/members/{memberId}/name")
    public JsonNode renameMember(@PathParam("id") long id, @PathParam("memberId") long memberId, NameInput input)
            throws Exception {
        checkOperator();
        required(input, "Display name required");
        return json(members().rename(id, memberId, input.displayName()));
    }

    @PUT
    @Path("{id}/members/{memberId}/account")
    public JsonNode bindAccount(@PathParam("id") long id, @PathParam("memberId") long memberId,
                                AccountInput input) throws Exception {
        checkOperator();
        required(input, "Account required");
        return json(members().bindAccount(id, memberId, required(input.userId(), "userId is required")));
    }

    @DELETE
    @Path("{id}/members/{memberId}/account")
    public JsonNode unbindAccount(@PathParam("id") long id, @PathParam("memberId") long memberId)
            throws Exception {
        checkOperator();
        return json(members().unbindAccount(id, memberId));
    }

    @PUT
    @Path("{id}/members/{memberId}/tracker")
    public JsonNode bindTracker(@PathParam("id") long id, @PathParam("memberId") long memberId,
                                TrackerInput input) throws Exception {
        checkOperator();
        required(input, "Tracker required");
        return json(members().bindTracker(id, memberId, required(input.deviceId(), "deviceId is required")));
    }

    @DELETE
    @Path("{id}/members/{memberId}/tracker")
    public JsonNode unbindTracker(@PathParam("id") long id, @PathParam("memberId") long memberId)
            throws Exception {
        checkOperator();
        return json(members().unbindTracker(id, memberId));
    }

    @POST
    @Path("{id}/members/{memberId}/retire")
    public JsonNode retire(@PathParam("id") long id, @PathParam("memberId") long memberId) throws Exception {
        checkOperator();
        return json(members().retire(id, memberId));
    }

    /** Retires the member and plans a new one with the new role, which takes over its bindings. */
    @POST
    @Path("{id}/members/{memberId}/role")
    public JsonNode changeRole(@PathParam("id") long id, @PathParam("memberId") long memberId, MemberInput input)
            throws Exception {
        checkOperator();
        required(input, "Role required");
        return json(members().changeRole(id, memberId, input.role(), input.number(), input.displayName()));
    }

    // Resource rows -----------------------------------------------------------------------------------------

    @GET
    @Path("{id}/resources")
    public JsonNode resources(@PathParam("id") long id) throws Exception {
        checkOperator();
        sessions.get(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mirrors", resources.mirrors(id));
        result.put("groups", resources.groups(id));
        result.put("geofences", resources.geofences(id));
        return json(result);
    }

    @POST
    @Path("{id}/resources/mirrors")
    public JsonNode addMirror(@PathParam("id") long id, MirrorInput input) throws Exception {
        checkOperator();
        required(input, "Mirror required");
        return json(resources.addMirror(id, required(input.deviceId(), "deviceId is required"),
                required(input.sourceMemberId(), "sourceMemberId is required"), input.destinationRole(),
                input.destinationMemberId()));
    }

    @POST
    @Path("{id}/resources/groups")
    public JsonNode addGroup(@PathParam("id") long id, GroupInput input) throws Exception {
        checkOperator();
        required(input, "Group required");
        return json(resources.addGroup(id, required(input.groupId(), "groupId is required"), input.purpose(),
                input.memberId()));
    }

    @POST
    @Path("{id}/resources/geofences")
    public JsonNode addGeofence(@PathParam("id") long id, GeofenceInput input) throws Exception {
        checkOperator();
        required(input, "Geofence required");
        return json(resources.addGeofence(id, required(input.geofenceId(), "geofenceId is required"),
                input.planKey(), input.purpose()));
    }

    /** Moves an active row to retiring: it stays owned until cleanup removes it. */
    @POST
    @Path("{id}/resources/{kind}/{rowId}/retire")
    public JsonNode retireResource(@PathParam("id") long id, @PathParam("kind") String kind,
                                   @PathParam("rowId") long rowId) throws Exception {
        checkOperator();
        return json(resources.retire(kind(kind), id, rowId));
    }

    @POST
    @Path("{id}/resources/{kind}/{rowId}/reactivate")
    public JsonNode reactivateResource(@PathParam("id") long id, @PathParam("kind") String kind,
                                       @PathParam("rowId") long rowId) throws Exception {
        checkOperator();
        return json(resources.reactivate(kind(kind), id, rowId));
    }

    /** Replaces the Traccar device, group or geofence of an active row. */
    @PUT
    @Path("{id}/resources/{kind}/{rowId}")
    public JsonNode rebindResource(@PathParam("id") long id, @PathParam("kind") String kind,
                                   @PathParam("rowId") long rowId, RebindInput input) throws Exception {
        checkOperator();
        required(input, "Resource required");
        return json(resources.rebind(kind(kind), id, rowId, required(input.resourceId(), "resourceId is required")));
    }

    /** Removes a retiring row once cleanup is done. */
    @DELETE
    @Path("{id}/resources/{kind}/{rowId}")
    public Response removeResource(@PathParam("id") long id, @PathParam("kind") String kind,
                                   @PathParam("rowId") long rowId) throws Exception {
        checkOperator();
        resources.remove(kind(kind), id, rowId);
        return Response.noContent().build();
    }

    // Documents and checkpoints -----------------------------------------------------------------------------

    /** The decrypted generated credentials; 503 when the server's key is missing or wrong. */
    @GET
    @Path("{id}/credentials")
    public Response credentials(@PathParam("id") long id) throws Exception {
        checkOperator();
        return noStore(documents.credentials(id));
    }

    @PUT
    @Path("{id}/credentials")
    public Revision updateCredentials(@PathParam("id") long id, DocumentUpdate request) throws Exception {
        checkOperator();
        required(request, "Credentials required");
        return new Revision(documents.updateCredentials(id, expected(request.expectedRevision()),
                request.document()));
    }

    @GET
    @Path("{id}/geofence-plans")
    public JsonNode geofencePlans(@PathParam("id") long id) throws Exception {
        checkOperator();
        return json(documents.geofencePlans(id));
    }

    @PUT
    @Path("{id}/geofence-plans")
    public Revision updateGeofencePlans(@PathParam("id") long id, DocumentUpdate request) throws Exception {
        checkOperator();
        required(request, "Geofence plans required");
        return new Revision(documents.updateGeofencePlans(id, expected(request.expectedRevision()),
                request.document()));
    }

    @GET
    @Path("{id}/checkpoints")
    public JsonNode checkpoints(@PathParam("id") long id) throws Exception {
        checkOperator();
        return json(documents.checkpoints(id));
    }

    @GET
    @Path("{id}/checkpoints/{component}")
    public JsonNode checkpoint(@PathParam("id") long id, @PathParam("component") String component)
            throws Exception {
        checkOperator();
        return json(documents.checkpoint(id, component));
    }

    @PUT
    @Path("{id}/checkpoints/{component}")
    public JsonNode putCheckpoint(@PathParam("id") long id, @PathParam("component") String component,
                                  DocumentUpdate request) throws Exception {
        checkOperator();
        required(request, "Checkpoint required");
        return json(documents.putCheckpoint(id, component, expected(request.expectedRevision()),
                required(request.schemaVersion(), "schemaVersion is required"), request.document()));
    }

    @DELETE
    @Path("{id}/checkpoints/{component}")
    public Response deleteCheckpoint(@PathParam("id") long id, @PathParam("component") String component)
            throws Exception {
        checkOperator();
        documents.deleteCheckpoint(id, component);
        return Response.noContent().build();
    }

    // Timetable and logs ------------------------------------------------------------------------------------

    @GET
    @Path("{id}/timetable")
    public JsonNode timetable(@PathParam("id") long id) throws Exception {
        checkOperator();
        return json(timetables.get(id));
    }

    @PUT
    @Path("{id}/timetable")
    public JsonNode publishTimetable(@PathParam("id") long id, TimetablePublish request) throws Exception {
        checkOperator();
        required(request, "Timetable required");
        return json(timetables.publish(id, expected(request.expectedRevision()), request.columns(),
                request.source(), request.entries()));
    }

    @PUT
    @Path("{id}/timetable/progress")
    public Response timetableProgress(@PathParam("id") long id, ProgressUpdate request) throws Exception {
        checkOperator();
        timetables.progress(id, request == null ? null : request.entries());
        return Response.noContent().build();
    }

    @GET
    @Path("{id}/logs")
    public JsonNode logs(@PathParam("id") long id, @QueryParam("after") String after, @QueryParam("limit") int limit,
                         @QueryParam("category") String category) throws Exception {
        checkOperator();
        return json(logs.list(id, after, limit, category));
    }

    @POST
    @Path("{id}/logs")
    public Response appendLogs(@PathParam("id") long id, LogAppend request) throws Exception {
        checkOperator();
        logs.append(id, request == null ? null : request.entries());
        return Response.noContent().build();
    }

    // Requests ----------------------------------------------------------------------------------------------

    /** Open requests ({@code state} other than {@code all}) or every request, newest first. */
    @GET
    @Path("{id}/requests")
    public JsonNode listRequests(@PathParam("id") long id, @QueryParam("state") String state,
                                 @QueryParam("limit") int limit) throws Exception {
        checkOperator();
        return json(requests.operatorList(id, !"all".equals(state), limit));
    }

    /** The controller's decision: {@code await} (operator approval needed), {@code approve} or {@code reject}. */
    @POST
    @Path("{id}/requests/{requestId}/decision")
    public JsonNode decideRequest(@PathParam("id") long id, @PathParam("requestId") long requestId,
                                  RequestDecision decision) throws Exception {
        checkOperator();
        required(decision, "Decision required");
        return json(requests.decide(id, requestId, decision.decision(), decision.message(), decision.detail()));
    }

    @POST
    @Path("{id}/requests/{requestId}/claim")
    public JsonNode claimRequest(@PathParam("id") long id, @PathParam("requestId") long requestId,
                                 @HeaderParam(GameControllerService.EPOCH_HEADER) String header) throws Exception {
        checkOperator();
        return json(requests.claim(id, requestId, epoch(header, "Claiming")));
    }

    // Jokers ------------------------------------------------------------------------------------------------

    @GET
    @Path("{id}/jokers")
    public JsonNode jokerOverview(@PathParam("id") long id) throws Exception {
        checkOperator();
        return json(jokers.overview(id));
    }

    @GET
    @Path("{id}/jokers/ledger")
    public JsonNode jokerLedger(@PathParam("id") long id, @QueryParam("limit") int limit) throws Exception {
        checkOperator();
        return json(jokers.ledger(id, limit));
    }

    /** Balances that differ from their ledger or consumed reservations; empty when consistent. */
    @GET
    @Path("{id}/jokers/reconcile")
    public JsonNode reconcileJokers(@PathParam("id") long id) throws Exception {
        checkOperator();
        return json(jokers.reconcile(id));
    }

    /** Grants (positive quantity) or revokes (negative) unused jokers of a member, or of the hunters' pool. */
    @POST
    @Path("{id}/jokers/change")
    public JsonNode changeJokers(@PathParam("id") long id, GameJokerService.Change change) throws Exception {
        checkOperator();
        return json(jokers.change(id, change, actor()));
    }

    @POST
    @Path("{id}/jokers/breaks")
    public JsonNode planBreak(@PathParam("id") long id, BreakPlan plan) throws Exception {
        checkOperator();
        required(plan, "Break plan required");
        return json(jokers.planBreak(id, required(plan.memberId(), "memberId is required"), plan.startsAt(),
                Boolean.TRUE.equals(plan.overAllocate()), actor()));
    }

    // Effects -----------------------------------------------------------------------------------------------

    /**
     * Applies one fenced, idempotent effects delivery under the epoch that the controller fence admitted. After
     * commit, the services publish positions, invalidations, proximity access changes and queued pushes.
     */
    @POST
    @Path("{id}/effects")
    public JsonNode applyEffects(@PathParam("id") long id,
                                 @HeaderParam(GameControllerService.EPOCH_HEADER) String header,
                                 GameEffectsService.Delivery delivery) throws Exception {
        checkOperator();
        return json(effects.apply(id, epoch(header, "Effects"), delivery));
    }

}
