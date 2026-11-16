/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The device list of a session (game execution plan, phase 5): the desired list of trackers, edited in Tool's grid
 * editor and applied by its device setup. It is one revisioned document, {@code {"devices": [row, ...]}}, whose
 * rows are {@code {"name", "deviceIdentifier", "role", "roleNumber"}}.
 *
 * <p>The document is the desired list, not the applied one: saving it changes no member, tracker or Traccar
 * device. Device setup reads it and applies it; the session's applied roster stays in its settings and member
 * rows.
 *
 * <p>Every write is validated as a whole: names are non-empty and unique (ignoring case, because Tool matches
 * display names that way), device identifiers are non-empty and unique, {@code role} is player, supporter or
 * hunter, role numbers are positive and unique per role, and at least one row is a player. Names and identifiers
 * are stored trimmed. A malformed document (not the shape above) answers 400; rule violations answer 422 with
 * every failing row ({@link Rejected}). Revisions work like the geofence plans': a write names the revision it was
 * based on (0 when none exists yet); retrying the write that already succeeded answers the stored list, any other
 * mismatch 409.
 */
@Singleton
public class GameDeviceListService {

    public static final int MAX_DEVICES = 1000;
    public static final int MAX_NAME_LENGTH = 128;
    public static final int MAX_IDENTIFIER_LENGTH = 128;
    public static final List<String> ROLES = List.of("player", "supporter", "hunter");

    private static final String DEVICES = "devices";
    private static final Set<String> FIELDS = Set.of("name", "deviceIdentifier", "role", "roleNumber");

    /** A stored device list; revision 0 (and no update time) when the session has none yet. */
    public record DeviceList(long revision, Instant updatedAt, JsonNode document) {
    }

    /**
     * One failing row of a rejected list: its {@code index} in {@code devices} (absent for list-wide rules), the
     * {@code field} it concerns, a {@code code} and an English {@code message}.
     */
    public record RowError(Integer index, String field, String code, String message) {
    }

    /** A rejected device list: answered with 422 and every failing row. */
    public static final class Rejected extends RuntimeException {
        private final transient List<RowError> errors;

        public Rejected(List<RowError> errors) {
            super("The device list was rejected");
            this.errors = List.copyOf(errors);
        }

        public List<RowError> errors() {
            return errors;
        }
    }

    private final GameTransactions transactions;
    private final ObjectMapper objectMapper;

    @Inject
    public GameDeviceListService(GameTransactions transactions, ObjectMapper objectMapper) {
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    public DeviceList get(long sessionId) throws Exception {
        return transactions.read(transaction -> {
            GameDocumentService.sessionExists(transaction, sessionId);
            return read(transaction, sessionId);
        });
    }

    private DeviceList read(GameTransaction transaction, long sessionId) throws Exception {
        Optional<GameDocumentRepository.StoredAt> stored = GameDocumentRepository.deviceList(transaction, sessionId);
        if (stored.isEmpty()) {
            ObjectNode empty = JsonNodeFactory.instance.objectNode();
            empty.putArray(DEVICES);
            return new DeviceList(0, null, empty);
        }
        return new DeviceList(stored.get().revision(), stored.get().updatedAt(),
                objectMapper.readTree(stored.get().text()));
    }

    /** Validates and stores the list based on {@code expectedRevision}; answers the stored list. */
    public DeviceList update(long sessionId, long expectedRevision, JsonNode document) throws Exception {
        ObjectNode canonical = canonical(document);
        String serialized = objectMapper.writeValueAsString(canonical);
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            DeviceList current = read(transaction, sessionId);
            if (expectedRevision != current.revision()) {
                if (expectedRevision + 1 == current.revision() && current.document().equals(canonical)) {
                    return current;
                }
                throw GameErrors.revisionConflict("Device list", current.revision());
            }
            long revision = current.revision() + 1;
            GameDocumentRepository.putDeviceList(transaction, sessionId, revision, serialized);
            transaction.bumpSession();
            return new DeviceList(revision, transaction.now(), canonical);
        });
    }

    // Validation ----------------------------------------------------------------------------------------------

    /** The document as stored (names and identifiers trimmed); 400 when malformed, {@link Rejected} on rules. */
    static ObjectNode canonical(JsonNode document) {
        if (document == null || !document.isObject()) {
            throw GameErrors.invalid("The device list must be a JSON object");
        }
        for (Iterator<String> names = document.fieldNames(); names.hasNext();) {
            String name = names.next();
            if (!DEVICES.equals(name)) {
                throw GameErrors.invalid("Unknown device list property '" + name + "'");
            }
        }
        JsonNode rows = document.get(DEVICES);
        if (rows == null || !rows.isArray()) {
            throw GameErrors.invalid("devices must be an array");
        }
        List<RowError> errors = new ArrayList<>();
        if (rows.size() > MAX_DEVICES) {
            errors.add(new RowError(null, null, "tooManyDevices",
                    "A device list holds at most " + MAX_DEVICES + " devices."));
            throw new Rejected(errors);
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ArrayNode devices = result.putArray(DEVICES);
        Map<String, Integer> names = new HashMap<>();
        Map<String, Integer> identifiers = new HashMap<>();
        Map<String, Integer> numbers = new HashMap<>();
        boolean player = false;
        for (int index = 0; index < rows.size(); index++) {
            JsonNode row = rows.get(index);
            if (!row.isObject()) {
                throw GameErrors.invalid("Device " + (index + 1) + " must be a JSON object");
            }
            for (Iterator<String> fields = row.fieldNames(); fields.hasNext();) {
                String field = fields.next();
                if (!FIELDS.contains(field)) {
                    throw GameErrors.invalid("Unknown property '" + field + "' of device " + (index + 1));
                }
            }
            ObjectNode device = devices.addObject();

            String name = text(row.get("name"), index, "name", "Name", MAX_NAME_LENGTH, errors);
            if (name != null) {
                device.put("name", name);
                Integer other = names.putIfAbsent(name.toLowerCase(Locale.ROOT), index);
                if (other != null) {
                    errors.add(new RowError(index, "name", "duplicateName",
                            "Another device is named '" + name + "' (row " + (other + 1) + ")."));
                }
            }

            String identifier = text(row.get("deviceIdentifier"), index, "deviceIdentifier", "Device identifier",
                    MAX_IDENTIFIER_LENGTH, errors);
            if (identifier != null) {
                device.put("deviceIdentifier", identifier);
                Integer other = identifiers.putIfAbsent(identifier, index);
                if (other != null) {
                    errors.add(new RowError(index, "deviceIdentifier", "duplicateDeviceIdentifier",
                            "Device identifier '" + identifier + "' is listed twice (row " + (other + 1) + ")."));
                }
            }

            JsonNode roleNode = row.get("role");
            String role = roleNode != null && roleNode.isTextual() ? roleNode.asText() : null;
            if (role == null || !ROLES.contains(role)) {
                errors.add(new RowError(index, "role", roleNode == null || roleNode.isNull()
                        || roleNode.isTextual() && roleNode.asText().isBlank() ? "missingRole" : "invalidRole",
                        "The group must be player, supporter or hunter."));
                role = null;
            } else {
                device.put("role", role);
                player |= "player".equals(role);
            }

            JsonNode numberNode = row.get("roleNumber");
            Integer number = null;
            if (numberNode == null || numberNode.isNull()) {
                errors.add(new RowError(index, "roleNumber", "missingRoleNumber", "The role number is missing."));
            } else if (!numberNode.isIntegralNumber() || !numberNode.canConvertToInt() || numberNode.asInt() < 1) {
                errors.add(new RowError(index, "roleNumber", "invalidRoleNumber",
                        "The role number must be a positive whole number."));
            } else {
                number = numberNode.asInt();
                device.put("roleNumber", number);
            }
            if (role != null && number != null) {
                Integer other = numbers.putIfAbsent(role + "#" + number, index);
                if (other != null) {
                    errors.add(new RowError(index, "roleNumber", "duplicateRoleNumber",
                            Character.toUpperCase(role.charAt(0)) + role.substring(1) + " " + number
                                    + " is listed twice (row " + (other + 1) + ")."));
                }
            }
        }
        if (!player) {
            errors.add(new RowError(null, "role", "noPlayer", "The device list needs at least one player."));
        }
        if (!errors.isEmpty()) {
            throw new Rejected(errors);
        }
        return result;
    }

    /** A trimmed text field, or null after recording why it is missing or invalid. */
    private static String text(JsonNode node, int index, String field, String label, int limit,
                               List<RowError> errors) {
        String code = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        if (node == null || node.isNull() || node.isTextual() && node.asText().isBlank()) {
            errors.add(new RowError(index, field, "missing" + code, "The " + lower(label) + " is empty."));
            return null;
        }
        if (!node.isTextual()) {
            errors.add(new RowError(index, field, "invalid" + code, "The " + lower(label) + " must be text."));
            return null;
        }
        String value = node.asText().strip();
        if (value.length() > limit) {
            errors.add(new RowError(index, field, "invalid" + code,
                    label + " is longer than " + limit + " characters."));
            return null;
        }
        if (value.chars().anyMatch(character -> character < 32 || character == 127)) {
            errors.add(new RowError(index, field, "invalid" + code, label + " contains control characters."));
            return null;
        }
        return value;
    }

    private static String lower(String label) {
        return Character.toLowerCase(label.charAt(0)) + label.substring(1);
    }

}
