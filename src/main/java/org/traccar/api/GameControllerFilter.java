/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.servlet.ServletRequest;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.security.UserPrincipal;
import org.traccar.game.GameControllerService;
import org.traccar.game.GameOwnershipService;
import org.traccar.game.GameTransactions;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fences mutations with the game controller lease.
 * <ul>
 * <li>Game endpoints that change execution state require the current {@code controllerEpoch}: effects, timetable
 * progress, members, resource rows, checkpoints, requests and jokers of any session, every write of the executing
 * session, and every write of game notifications and proximity. Configuration edits of a stored session that is not
 * executing (name, settings, credentials, geofence plans, timetable, logs) need only their revision check.</li>
 * <li>Standard mutations of the Traccar resources that any session owns ({@link GameOwnershipService}: members'
 * accounts and trackers, mirrors, groups and geofences), and pushes that reach members of the executing session,
 * require the epoch while a lease is held. While no lease is held, administrators may make them; the change is
 * recorded for Tool to report on its next acquisition.</li>
 * <li>A request that carries an epoch must carry the current one, whatever it changes.</li>
 * </ul>
 * Users updating their own account are never fenced: participants register push tokens and save preferences
 * that way while a game runs.
 * A fenced request holds the controller fence shared until its response, so acquisition, release and takeover
 * wait for it to complete.
 */
@Priority(Priorities.USER + 100)
public class GameControllerFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameControllerFilter.class);

    public static final String FENCE_PROPERTY = "org.traccar.gameControllerFence";
    private static final String UNFENCED_PROPERTY = "org.traccar.gameControllerUnfenced";

    private static final Set<String> STANDARD = Set.of("devices", "groups", "users", "geofences");
    /** Parts of a session path fenced whatever session they name: exact parts, and first segments. */
    private static final Set<String> ALWAYS_FENCED_PARTS = Set.of("timetable/progress", "effects");
    private static final Set<String> ALWAYS_FENCED_SEGMENTS = Set.of(
            "members", "resources", "checkpoints", "requests", "jokers");

    private enum Decision { NONE, REQUIRED, OWNED, STANDARD }

    /** One shared hold of the fence, released exactly once by the response filter or the servlet fallback. */
    public static final class Hold {
        private final GameControllerService service;
        private final long stamp;
        private final AtomicBoolean released = new AtomicBoolean();

        Hold(GameControllerService service, long stamp) {
            this.service = service;
            this.stamp = stamp;
        }

        public void release() {
            if (released.compareAndSet(false, true)) {
                service.unlockShared(stamp);
            }
        }
    }

    @Inject
    private GameControllerService controller;

    @Inject
    private GameOwnershipService ownership;

    @Inject
    private GameTransactions transactions;

    @Inject
    private DataSource dataSource;

    @Inject
    private ObjectMapper objectMapper;

    /** Servlet-level fallback that releases a hold left behind by an aborted response. */
    public static void releaseFrom(ServletRequest request) {
        if (request.getAttribute(FENCE_PROPERTY) instanceof Hold hold) {
            hold.release();
            request.removeAttribute(FENCE_PROPERTY);
        }
    }

    private static Response locked(String message) {
        return Response.status(GameControllerService.LOCKED).entity(message).type(MediaType.TEXT_PLAIN).build();
    }

    private static Long number(String value) {
        return value != null && value.matches("[1-9][0-9]{0,17}") ? Long.parseLong(value) : null;
    }

    @Override
    public void filter(ContainerRequestContext request) {
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)
                || request.getSecurityContext() == null
                || !(request.getSecurityContext().getUserPrincipal() instanceof UserPrincipal principal)) {
            return;
        }
        long userId = principal.getUserId();
        Long epoch;
        try {
            epoch = GameControllerService.parseEpoch(request.getHeaderString(GameControllerService.EPOCH_HEADER));
        } catch (BadRequestException error) {
            request.abortWith(Response.status(Response.Status.BAD_REQUEST)
                    .entity(error.getMessage()).type(MediaType.TEXT_PLAIN).build());
            return;
        }
        List<String> path = new ArrayList<>();
        for (PathSegment segment : request.getUriInfo().getPathSegments()) {
            if (!segment.getPath().isEmpty()) {
                path.add(segment.getPath());
            }
        }
        try {
            Decision decision = classify(method, path, request, userId);
            switch (decision) {
                case NONE -> { }
                case REQUIRED -> fence(request, userId, epoch);
                case STANDARD -> {
                    if (epoch != null) {
                        fence(request, userId, epoch);
                    }
                }
                case OWNED -> {
                    if (epoch != null) {
                        fence(request, userId, epoch);
                    } else if (controller.status().active()) {
                        request.abortWith(locked("Tool controls this game resource; change it in Tool,"
                                + " or release control first"));
                    } else {
                        request.setProperty(UNFENCED_PROPERTY, method + " /api/" + String.join("/", path));
                    }
                }
                default -> throw new IllegalStateException();
            }
        } catch (WebApplicationException error) {
            Response response = error.getResponse();
            request.abortWith(Response.status(response.getStatus())
                    .entity(error.getMessage()).type(MediaType.TEXT_PLAIN).build());
        } catch (Exception error) {
            LOGGER.warn("Game controller fencing failed", error);
            request.abortWith(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("Game controller fencing failed").type(MediaType.TEXT_PLAIN).build());
        }
    }

    private void fence(ContainerRequestContext request, long userId, Long epoch) throws Exception {
        long stamp = controller.lockShared();
        Hold hold = new Hold(controller, stamp);
        try {
            controller.check(userId, epoch);
        } catch (Exception error) {
            hold.release();
            throw error;
        }
        request.setProperty(FENCE_PROPERTY, hold);
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (request.getProperty(FENCE_PROPERTY) instanceof Hold hold) {
            hold.release();
            request.removeProperty(FENCE_PROPERTY);
        }
        if (request.getProperty(UNFENCED_PROPERTY) instanceof String detail
                && response.getStatus() >= 200 && response.getStatus() < 300
                && request.getSecurityContext().getUserPrincipal() instanceof UserPrincipal principal) {
            try {
                controller.recordUnfencedEdit(principal.getUserId(), detail);
            } catch (Exception error) {
                LOGGER.warn("Unfenced game resource edit could not be recorded", error);
            }
        }
    }

    private Decision classify(String method, List<String> path, ContainerRequestContext request, long userId)
            throws Exception {
        if (path.isEmpty()) {
            return Decision.NONE;
        }
        String root = path.getFirst();
        switch (root) {
            case "game-controller" -> {
                return Decision.NONE;
            }
            case "game-notifications", "game-proximity" -> {
                return Decision.REQUIRED;
            }
            case "game-sessions" -> {
                if (path.size() < 2) {
                    return Decision.NONE;
                }
                Long id = number(path.get(1));
                String part = String.join("/", path.subList(2, path.size()));
                if (ALWAYS_FENCED_PARTS.contains(part)
                        || path.size() > 2 && ALWAYS_FENCED_SEGMENTS.contains(path.get(2))) {
                    return Decision.REQUIRED;
                }
                Long executing = controller.executingSessionId();
                return id != null && id.equals(executing) ? Decision.REQUIRED : Decision.NONE;
            }
            case "permissions" -> {
                return owned(permissionTargets(request)) ? Decision.OWNED : Decision.STANDARD;
            }
            case "positions" -> {
                if (!"DELETE".equals(method)) {
                    return Decision.NONE;
                }
                Long deviceId = path.size() > 1 ? positionDevice(number(path.get(1)))
                        : number(request.getUriInfo().getQueryParameters().getFirst("deviceId"));
                return deviceId != null && ownership.owned().devices().contains(deviceId)
                        ? Decision.OWNED : Decision.STANDARD;
            }
            case "notifications" -> {
                if (path.size() < 2 || !"send".equals(path.get(1))) {
                    return Decision.NONE;
                }
                Long executing = controller.executingSessionId();
                if (executing == null) {
                    return Decision.STANDARD;
                }
                Set<Long> members = memberAccounts(executing);
                List<String> recipients = request.getUriInfo().getQueryParameters().get("userId");
                boolean reachesMember = recipients == null || recipients.isEmpty()
                        ? !members.isEmpty()
                        : recipients.stream().map(GameControllerFilter::number).anyMatch(members::contains);
                return reachesMember ? Decision.OWNED : Decision.STANDARD;
            }
            default -> {
                if (!STANDARD.contains(root)) {
                    return Decision.NONE;
                }
                Long id = path.size() > 1 ? number(path.get(1)) : null;
                if (id == null || "users".equals(root) && id == userId) {
                    return Decision.STANDARD;
                }
                return owned(Map.of(root, Set.of(id))) ? Decision.OWNED : Decision.STANDARD;
            }
        }
    }

    /** The accounts bound to active members of a session. */
    private Set<Long> memberAccounts(long sessionId) throws Exception {
        return transactions.read(transaction -> Set.copyOf(transaction.list("SELECT userid FROM"
                + " tc_game_session_members WHERE gamesessionid = ? AND state = 'active' AND userid IS NOT NULL",
                rows -> rows.getLong(1), sessionId)));
    }

    private boolean owned(Map<String, Set<Long>> targets) throws Exception {
        if (targets.isEmpty()) {
            return false;
        }
        var owned = ownership.owned();
        for (var entry : targets.entrySet()) {
            Set<Long> ids = switch (entry.getKey()) {
                case "devices" -> owned.devices();
                case "groups" -> owned.groups();
                case "users" -> owned.users();
                case "geofences" -> owned.geofences();
                default -> Set.of();
            };
            if (entry.getValue().stream().anyMatch(ids::contains)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Set<Long>> permissionTargets(ContainerRequestContext request) throws Exception {
        byte[] body = request.getEntityStream().readAllBytes();
        request.setEntityStream(new ByteArrayInputStream(body));
        if (body.length == 0) {
            return Map.of();
        }
        var tree = objectMapper.readTree(body);
        List<Map<String, Object>> items = new ArrayList<>();
        if (tree.isArray()) {
            items.addAll(objectMapper.convertValue(tree, new TypeReference<List<Map<String, Object>>>() { }));
        } else if (tree.isObject()) {
            items.add(objectMapper.convertValue(tree, new TypeReference<Map<String, Object>>() { }));
        }
        Map<String, Set<Long>> result = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            for (var entry : item.entrySet()) {
                String type = switch (entry.getKey()) {
                    case "userId", "managedUserId" -> "users";
                    case "deviceId" -> "devices";
                    case "groupId" -> "groups";
                    case "geofenceId" -> "geofences";
                    default -> null;
                };
                if (type != null && entry.getValue() instanceof Number value) {
                    result.computeIfAbsent(type, ignored -> new java.util.HashSet<>()).add(value.longValue());
                }
            }
        }
        return result;
    }

    private Long positionDevice(Long positionId) throws Exception {
        if (positionId == null) {
            return null;
        }
        try (var connection = dataSource.getConnection();
             var query = connection.prepareStatement("SELECT deviceid FROM tc_positions WHERE id = ?")) {
            query.setLong(1, positionId);
            try (var rows = query.executeQuery()) {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }
}
