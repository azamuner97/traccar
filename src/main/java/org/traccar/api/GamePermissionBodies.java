/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.traccar.model.Permission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads {@code /permissions} bodies and path numbers exactly as Traccar's resources do, so the game filters judge
 * the change those resources will make: values are coerced like any {@code Long} (also from strings), keys resolve
 * like {@link Permission#getKeyClass} (the key without its last two characters, matched case-insensitively against
 * the model class name, the first key being the owner), and path IDs parse like Jersey's {@code long} parameters
 * (leading zeros and signs included). Only the classes game sessions own are resolved here.
 */
final class GamePermissionBodies {

    /** One permission of a body: the kind ({@code users}, {@code devices}, {@code groups}, {@code geofences},
     * or {@code null} for any other class) and ID of its owner and its property; {@code user} marks an owner of
     * class {@code User} itself. */
    record Link(String ownerKind, long ownerId, String propertyKind, long propertyId, boolean user) {
    }

    private static final TypeReference<List<LinkedHashMap<String, Long>>> LIST = new TypeReference<>() { };
    private static final TypeReference<LinkedHashMap<String, Long>> ITEM = new TypeReference<>() { };

    private static final Map<String, String> KINDS = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    static {
        KINDS.put("User", "users");
        KINDS.put("ManagedUser", "users");
        KINDS.put("Device", "devices");
        KINDS.put("Group", "groups");
        KINDS.put("Geofence", "geofences");
    }

    private GamePermissionBodies() {
    }

    private static String kind(String key) {
        return key != null && key.length() >= 2 ? KINDS.get(key.substring(0, key.length() - 2)) : null;
    }

    /**
     * The permissions a body names, as {@code PermissionsResource} reads them. Items it cannot read (not two keys,
     * a missing value) are left out: the resource refuses those without storing anything.
     */
    static List<Link> read(ObjectMapper objectMapper, JsonNode tree) {
        List<LinkedHashMap<String, Long>> items = new ArrayList<>();
        try {
            if (tree.isArray()) {
                items.addAll(objectMapper.convertValue(tree, LIST));
            } else if (tree.isObject()) {
                items.add(objectMapper.convertValue(tree, ITEM));
            }
        } catch (IllegalArgumentException unreadable) {
            return List.of();
        }
        List<Link> result = new ArrayList<>();
        for (LinkedHashMap<String, Long> item : items) {
            if (item == null || item.size() != 2) {
                continue;
            }
            var entries = new ArrayList<>(item.entrySet());
            var owner = entries.get(0);
            var property = entries.get(1);
            if (owner.getValue() == null || property.getValue() == null) {
                continue;
            }
            String ownerName = owner.getKey().length() >= 2
                    ? owner.getKey().substring(0, owner.getKey().length() - 2) : "";
            result.add(new Link(kind(owner.getKey()), owner.getValue(), kind(property.getKey()),
                    property.getValue(), "User".equalsIgnoreCase(ownerName)));
        }
        return result;
    }

    /** A path segment as Jersey converts it to a {@code long} parameter, or {@code null}. */
    static Long number(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

}
