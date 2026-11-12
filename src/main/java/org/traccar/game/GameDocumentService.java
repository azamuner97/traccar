/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Revisioned documents of a session that only operators read: generated credentials (encrypted with the server's
 * key, as in model 1), geofence plans, and component checkpoints.
 *
 * <p>Every write names the revision it was based on (0 for a document that does not exist yet). Retrying the write
 * that already succeeded (expected revision + 1, identical content) answers the current revision; any other
 * mismatch answers 409. Checkpoints hold internal recovery information only: losing them changes no game outcome
 * (plan §2.1), so they may also be deleted.
 */
@Singleton
public class GameDocumentService {

    public static final int MAX_DOCUMENT_LENGTH = 8 * 1024 * 1024;

    public record Document(long revision, JsonNode document) {
    }

    public record Checkpoint(String component, int schemaVersion, long revision, JsonNode document,
                             Instant updatedAt) {
    }

    private final GameTransactions transactions;
    private final ObjectMapper objectMapper;
    private final GameCredentialCipher cipher;

    @Inject
    public GameDocumentService(GameTransactions transactions, ObjectMapper objectMapper,
                               GameCredentialCipher cipher) {
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.cipher = cipher;
    }

    /** Serializes a JSON object document, refusing anything else and documents over the size limit. */
    static String serialize(ObjectMapper objectMapper, JsonNode document, String label) throws Exception {
        if (document == null || !document.isObject()) {
            throw GameErrors.invalid(label + " must be a JSON object");
        }
        String serialized = objectMapper.writeValueAsString(document);
        if (serialized.length() > MAX_DOCUMENT_LENGTH) {
            throw GameErrors.invalid(label + " exceeds the size limit");
        }
        return serialized;
    }

    static void sessionExists(GameTransaction transaction, long sessionId) throws Exception {
        if (GameSessionRepository.find(transaction, sessionId).isEmpty()) {
            throw GameErrors.notFound("Game session not found");
        }
    }

    // Credentials -------------------------------------------------------------------------------------------

    /** The decrypted credentials document; 503 when the server's key is missing or wrong. */
    public Document credentials(long sessionId) throws Exception {
        return transactions.read(transaction -> {
            sessionExists(transaction, sessionId);
            Optional<GameDocumentRepository.Stored> stored = GameDocumentRepository.credentials(transaction, sessionId);
            return stored.isEmpty() ? new Document(0, JsonNodeFactory.instance.objectNode())
                    : new Document(stored.get().revision(),
                            objectMapper.readTree(cipher.decrypt(stored.get().text())));
        });
    }

    public long updateCredentials(long sessionId, long expectedRevision, JsonNode credentials) throws Exception {
        String serialized = serialize(objectMapper, credentials, "Credentials");
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            Optional<GameDocumentRepository.Stored> stored = GameDocumentRepository.credentials(transaction, sessionId);
            long current = stored.map(GameDocumentRepository.Stored::revision).orElse(0L);
            if (expectedRevision != current) {
                if (expectedRevision + 1 == current
                        && objectMapper.readTree(cipher.decrypt(stored.get().text())).equals(credentials)) {
                    return current;
                }
                throw GameErrors.revisionConflict("Credentials", current);
            }
            GameDocumentRepository.putCredentials(transaction, sessionId, current + 1, cipher.encrypt(serialized));
            transaction.bumpSession();
            return current + 1;
        });
    }

    // Geofence plans ----------------------------------------------------------------------------------------

    public Document geofencePlans(long sessionId) throws Exception {
        return transactions.read(transaction -> {
            sessionExists(transaction, sessionId);
            return geofencePlans(transaction, objectMapper, sessionId);
        });
    }

    /** The geofence plans, or revision 0 with an empty document, inside a transaction. */
    static Document geofencePlans(GameTransaction transaction, ObjectMapper objectMapper, long sessionId)
            throws Exception {
        Optional<GameDocumentRepository.Stored> stored = GameDocumentRepository.geofencePlans(transaction, sessionId);
        return stored.isEmpty() ? new Document(0, JsonNodeFactory.instance.objectNode())
                : new Document(stored.get().revision(), objectMapper.readTree(stored.get().text()));
    }

    public long updateGeofencePlans(long sessionId, long expectedRevision, JsonNode data) throws Exception {
        String serialized = serialize(objectMapper, data, "Geofence plans");
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            Optional<GameDocumentRepository.Stored> stored =
                    GameDocumentRepository.geofencePlans(transaction, sessionId);
            long current = stored.map(GameDocumentRepository.Stored::revision).orElse(0L);
            if (expectedRevision != current) {
                if (expectedRevision + 1 == current && objectMapper.readTree(stored.get().text()).equals(data)) {
                    return current;
                }
                throw GameErrors.revisionConflict("Geofence plans", current);
            }
            GameDocumentRepository.putGeofencePlans(transaction, sessionId, current + 1, serialized);
            transaction.bumpSession();
            return current + 1;
        });
    }

    // Checkpoints -------------------------------------------------------------------------------------------

    /** A checkpoint component name: letters, digits, '_' and '-'. */
    public static String component(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,32}")) {
            throw GameErrors.invalid("Invalid checkpoint component");
        }
        return value;
    }

    private Checkpoint checkpoint(GameDocumentRepository.CheckpointRow row) throws Exception {
        return checkpoint(objectMapper, row);
    }

    private static Checkpoint checkpoint(ObjectMapper objectMapper, GameDocumentRepository.CheckpointRow row)
            throws Exception {
        return new Checkpoint(row.component(), row.schemaVersion(), row.revision(),
                objectMapper.readTree(row.document()), row.updatedAt());
    }

    /** A checkpoint, or revision 0 with an empty document when the component has none. */
    public Checkpoint checkpoint(long sessionId, String component) throws Exception {
        String name = component(component);
        return transactions.read(transaction -> {
            sessionExists(transaction, sessionId);
            Optional<GameDocumentRepository.CheckpointRow> row =
                    GameDocumentRepository.checkpoint(transaction, sessionId, name);
            return row.isPresent() ? checkpoint(row.get())
                    : new Checkpoint(name, 0, 0, JsonNodeFactory.instance.objectNode(), null);
        });
    }

    public List<Checkpoint> checkpoints(long sessionId) throws Exception {
        return transactions.read(transaction -> {
            sessionExists(transaction, sessionId);
            return checkpoints(transaction, objectMapper, sessionId);
        });
    }

    /** Every checkpoint of a session, inside a transaction. */
    static List<Checkpoint> checkpoints(GameTransaction transaction, ObjectMapper objectMapper, long sessionId)
            throws Exception {
        List<Checkpoint> result = new ArrayList<>();
        for (var row : GameDocumentRepository.checkpoints(transaction, sessionId)) {
            result.add(checkpoint(objectMapper, row));
        }
        return result;
    }

    public Checkpoint putCheckpoint(long sessionId, String component, long expectedRevision, int schemaVersion,
                                    JsonNode document) throws Exception {
        return transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            Checkpoint result = putCheckpoint(transaction, objectMapper, sessionId, component, expectedRevision,
                    schemaVersion, document);
            transaction.bumpSession();
            return result;
        });
    }

    /** Writes a checkpoint inside a transaction that holds the session lock (also from effects deliveries). */
    static Checkpoint putCheckpoint(GameTransaction transaction, ObjectMapper objectMapper, long sessionId,
                                    String component, long expectedRevision, int schemaVersion, JsonNode document)
            throws Exception {
        String name = component(component);
        if (schemaVersion < 1) {
            throw GameErrors.invalid("Invalid checkpoint schema version");
        }
        String serialized = serialize(objectMapper, document, "Checkpoint");
        Optional<GameDocumentRepository.CheckpointRow> stored =
                GameDocumentRepository.checkpoint(transaction, sessionId, name);
        long current = stored.map(GameDocumentRepository.CheckpointRow::revision).orElse(0L);
        if (expectedRevision != current) {
            if (expectedRevision + 1 == current && stored.get().schemaVersion() == schemaVersion
                    && objectMapper.readTree(stored.get().document()).equals(document)) {
                return new Checkpoint(name, schemaVersion, current, document, stored.get().updatedAt());
            }
            throw GameErrors.revisionConflict("Checkpoint " + name, current);
        }
        GameDocumentRepository.putCheckpoint(transaction, sessionId, name, current + 1, schemaVersion, serialized);
        return new Checkpoint(name, schemaVersion, current + 1, document, transaction.now());
    }

    /** Deletes a checkpoint; deleting one that does not exist does nothing. */
    public void deleteCheckpoint(long sessionId, String component) throws Exception {
        String name = component(component);
        transactions.write(transaction -> {
            transaction.lockSession(sessionId);
            if (GameDocumentRepository.deleteCheckpoint(transaction, sessionId, name) > 0) {
                transaction.bumpSession();
            }
            return null;
        });
    }

}
