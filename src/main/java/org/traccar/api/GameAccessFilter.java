/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Injector;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.security.PermissionsService;
import org.traccar.api.security.UserPrincipal;
import org.traccar.database.GameAccessRule;
import org.traccar.model.Device;
import org.traccar.model.Group;
import org.traccar.model.User;
import org.traccar.model.UserRestrictions;
import org.traccar.storage.StorageException;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Refuses permission grants and device or group moves that would let a hunter see a real participant tracker
 * of a session with break or fake jokers (mirror-only hunter access, see {@link GameAccessRule}). Applies to
 * every caller allowed to make the change, the controlling Tool included. Callers without that right are left to
 * Traccar's own refusal, so the answer never tells them about the game's trackers or hunters.
 */
@Priority(Priorities.USER + 110)
public class GameAccessFilter implements ContainerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameAccessFilter.class);

    @Inject
    private GameAccessRule rule;

    @Inject
    private ObjectMapper objectMapper;

    /** Permissions are request-scoped; they are resolved per request, as in {@code SecurityRequestFilter}. */
    @Inject
    private Injector injector;

    private static Long number(String value) {
        return value != null && value.matches("[1-9][0-9]{0,17}") ? Long.parseLong(value) : null;
    }

    private static Long parent(JsonNode node) {
        long value = node.asLong(0);
        return value > 0 ? value : null;
    }

    @Override
    public void filter(ContainerRequestContext request) {
        String method = request.getMethod();
        if (!"POST".equals(method) && !"PUT".equals(method)) {
            return;
        }
        List<String> path = new ArrayList<>();
        for (PathSegment segment : request.getUriInfo().getPathSegments()) {
            if (!segment.getPath().isEmpty()) {
                path.add(segment.getPath());
            }
        }
        if (path.isEmpty()) {
            return;
        }
        String root = path.getFirst();
        boolean permissions = "POST".equals(method) && "permissions".equals(root)
                && (path.size() == 1 || path.size() == 2 && "bulk".equals(path.get(1)));
        Long id = path.size() == 2 ? number(path.get(1)) : null;
        boolean move = "PUT".equals(method) && id != null && ("devices".equals(root) || "groups".equals(root));
        if (!permissions && !move || request.getSecurityContext() == null
                || !(request.getSecurityContext().getUserPrincipal() instanceof UserPrincipal principal)) {
            return;
        }
        try {
            byte[] body = request.getEntityStream().readAllBytes();
            request.setEntityStream(new ByteArrayInputStream(body));
            if (body.length == 0) {
                return;
            }
            JsonNode tree = objectMapper.readTree(body);
            GameAccessRule.Change change = permissions ? grants(tree) : moves(root, id, tree);
            if (change.isEmpty() || !allowed(principal.getUserId(), permissions, root, id, change)) {
                return;
            }
            rule.check(change);
        } catch (WebApplicationException error) {
            request.abortWith(Response.status(error.getResponse().getStatus())
                    .entity(error.getMessage()).type(MediaType.TEXT_PLAIN).build());
        } catch (Exception error) {
            LOGGER.warn("Hunter access check failed", error);
            request.abortWith(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("Hunter access could not be checked").type(MediaType.TEXT_PLAIN).build());
        }
    }

    /** Whether the caller may make the change at all; the same checks as the permission and object resources. */
    private boolean allowed(long userId, boolean permissions, String root, Long id, GameAccessRule.Change change)
            throws StorageException {
        PermissionsService permissionsService = injector.getInstance(PermissionsService.class);
        if (!permissionsService.notAdmin(userId)) {
            return true;
        }
        try {
            permissionsService.checkRestriction(userId, UserRestrictions::getReadonly);
            if (permissions) {
                for (long[] grant : change.userDevices()) {
                    permissionsService.checkWritePermission(User.class, userId, grant[0]);
                    permissionsService.checkWritePermission(Device.class, userId, grant[1]);
                }
                for (long[] grant : change.userGroups()) {
                    permissionsService.checkWritePermission(User.class, userId, grant[0]);
                    permissionsService.checkWritePermission(Group.class, userId, grant[1]);
                }
            } else {
                if ("devices".equals(root)) {
                    permissionsService.checkWritePermission(Device.class, userId, id);
                } else {
                    permissionsService.checkWritePermission(Group.class, userId, id);
                }
            }
            return true;
        } catch (SecurityException error) {
            return false;
        }
    }

    static GameAccessRule.Change grants(JsonNode tree) {
        List<JsonNode> items = new ArrayList<>();
        if (tree.isArray()) {
            tree.forEach(items::add);
        } else if (tree.isObject()) {
            items.add(tree);
        }
        List<long[]> devices = new ArrayList<>();
        List<long[]> groups = new ArrayList<>();
        for (JsonNode item : items) {
            long user = item.path("userId").asLong(0);
            if (user <= 0) {
                continue;
            }
            if (item.path("deviceId").asLong(0) > 0) {
                devices.add(new long[] {user, item.get("deviceId").asLong()});
            }
            if (item.path("groupId").asLong(0) > 0) {
                groups.add(new long[] {user, item.get("groupId").asLong()});
            }
        }
        return new GameAccessRule.Change(devices, groups, Map.of(), Map.of());
    }

    static GameAccessRule.Change moves(String root, long id, JsonNode tree) {
        if (!tree.isObject() || !tree.has("groupId")) {
            return GameAccessRule.Change.NONE;
        }
        Map<Long, Long> moved = new HashMap<>();
        moved.put(id, parent(tree.get("groupId")));
        return "devices".equals(root)
                ? new GameAccessRule.Change(List.of(), List.of(), moved, Map.of())
                : new GameAccessRule.Change(List.of(), List.of(), Map.of(), moved);
    }
}
