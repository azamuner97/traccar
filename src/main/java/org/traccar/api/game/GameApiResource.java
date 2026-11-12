/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.api.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.game.GameControllerService;
import org.traccar.game.GameMember;
import org.traccar.game.GameMemberService;
import org.traccar.model.User;
import org.traccar.storage.StorageException;

import java.time.Instant;

/**
 * Shared base of the REST resources of game data model 2. {@code WebServer} registers this package with Jersey next
 * to {@code org.traccar.api.resource}.
 *
 * <ul>
 * <li>Operators are Traccar administrators. Members are resolved from the caller's account
 * ({@link GameMemberService#caller}); an account without membership gets 403.</li>
 * <li>Responses are JSON trees in which every {@link Instant} is an ISO-8601 string, because Traccar's
 * {@code ObjectMapper} has no JSR-310 module.</li>
 * <li>Writes are fenced by the controller lease in {@code GameControllerFilter}, by path. The
 * services publish their invalidations after commit through {@code GamePublisher}, so the resources announce
 * nothing themselves.</li>
 * </ul>
 */
public abstract class GameApiResource extends BaseResource {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new SimpleModule().addSerializer(Instant.class, ToStringSerializer.instance))
            .build();

    @Inject
    private GameMemberService members;

    /** A service result as JSON, with times as ISO-8601 strings. */
    static JsonNode json(Object value) {
        return JSON.valueToTree(value);
    }

    /** A member read that clients must not cache. */
    static Response noStore(Object value) {
        return Response.ok(json(value)).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    static <T> T required(T value, String message) {
        if (value == null) {
            throw new BadRequestException(message);
        }
        return value;
    }

    /** The controller epoch of a fenced call; 423 without one. */
    static long epoch(String header, String action) {
        Long epoch = GameControllerService.parseEpoch(header);
        if (epoch == null) {
            throw GameControllerService.locked(action + " requires the game controller lease");
        }
        return epoch;
    }

    protected GameMemberService members() {
        return members;
    }

    /** Operators are administrators in this version. */
    protected void checkOperator() throws StorageException {
        try {
            permissionsService.checkAdmin(getUserId());
        } catch (SecurityException error) {
            throw new ForbiddenException(error.getMessage());
        }
    }

    protected boolean administrator() throws StorageException {
        return !permissionsService.notAdmin(getUserId());
    }

    /** The caller's member; 403 when the account takes part in no game. */
    protected GameMember caller() throws Exception {
        return members.caller(getUserId());
    }

    /** The operator's name for ledgers and logs: the account's name, or its email. */
    protected String actor() throws StorageException {
        User user = permissionsService.getUser(getUserId());
        return user.getName() == null || user.getName().isBlank() ? user.getEmail() : user.getName();
    }

}
