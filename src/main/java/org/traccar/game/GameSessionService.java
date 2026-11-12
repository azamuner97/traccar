/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Game sessions of data model 2: identity, the aggregate revision, and versioned settings.
 *
 * <p>The settings revision guards configuration edits, which include the session name. The aggregate revision
 * advances with every committed change of the session and is for operators only (plan §2.4).
 */
@Singleton
public class GameSessionService {

    public static final int MAX_SETTINGS_LENGTH = 8 * 1024 * 1024;

    /**
     * The game-session API version of data model 2. Model 1 answered 4, so a Tool built for model 1 stops at
     * connection to this server.
     */
    public static final int API_VERSION = 5;
    /** The operators' WebSocket invalidation of the session list. */
    public static final String SESSIONS_INVALIDATION = "game-sessions";

    public record Settings(int schemaVersion, long revision, JsonNode settings, Instant updatedAt) {
    }

    public record Session(long id, String name, int schemaVersion, long revision, Instant createdAt,
                          Instant updatedAt, Settings settings) {
    }

    public record Summary(long id, String name, int schemaVersion, long revision, long settingsRevision,
                          Instant createdAt, Instant updatedAt) {
    }

    private final GameTransactions transactions;
    private final ObjectMapper objectMapper;
    private final GamePublisher publisher;

    @Inject
    public GameSessionService(GameTransactions transactions, ObjectMapper objectMapper, GamePublisher publisher) {
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.publisher = publisher;
    }

    public GameSessionService(GameTransactions transactions, ObjectMapper objectMapper) {
        this(transactions, objectMapper, GamePublisher.NONE);
    }

    /** A session name: also the spectator login, so no path separators. */
    public static String name(String value) {
        GameErrors.text(value, 128, "game session name");
        if (value.contains("/") || value.contains("\\")) {
            throw GameErrors.invalid("Invalid game session name");
        }
        return value;
    }

    private static int schemaVersion(int value) {
        if (value < 1) {
            throw GameErrors.invalid("Invalid schema version");
        }
        return value;
    }

    private String serialize(JsonNode settings) throws JsonProcessingException {
        if (settings == null || !settings.isObject()) {
            throw GameErrors.invalid("Settings must be a JSON object");
        }
        String serialized = objectMapper.writeValueAsString(settings);
        if (serialized.length() > MAX_SETTINGS_LENGTH) {
            throw GameErrors.invalid("Settings exceed the size limit");
        }
        return serialized;
    }

    private static Session session(ObjectMapper objectMapper, GameSessionRepository.Row row)
            throws JsonProcessingException {
        return new Session(row.id(), row.name(), row.schemaVersion(), row.revision(), row.createdAt(),
                row.updatedAt(), new Settings(row.settingsSchemaVersion(), row.settingsRevision(),
                        objectMapper.readTree(row.settings()), row.settingsUpdatedAt()));
    }

    /** A session with its settings, inside a transaction; 404 when it does not exist. */
    static Session read(GameTransaction transaction, ObjectMapper objectMapper, long id) throws Exception {
        return session(objectMapper, GameSessionRepository.find(transaction, id)
                .orElseThrow(() -> GameErrors.notFound("Game session not found")));
    }

    private Session read(GameTransaction transaction, long id) throws Exception {
        return read(transaction, objectMapper, id);
    }

    private static void nameFree(GameTransaction transaction, String name, long except) throws Exception {
        if (GameSessionRepository.named(transaction, name, except).isPresent()) {
            throw GameErrors.conflict("A game session with this name already exists");
        }
    }

    public List<Summary> list() throws Exception {
        return transactions.read(transaction -> {
            List<Summary> result = new ArrayList<>();
            for (GameSessionRepository.Row row : GameSessionRepository.list(transaction)) {
                result.add(new Summary(row.id(), row.name(), row.schemaVersion(), row.revision(),
                        row.settingsRevision(), row.createdAt(), row.updatedAt()));
            }
            return result;
        });
    }

    public Session get(long id) throws Exception {
        return transactions.read(transaction -> read(transaction, id));
    }

    /** Creates a session with a unique name, at revision 1, with its settings at revision 1. */
    public Session create(String name, int schemaVersion, int settingsSchemaVersion, JsonNode settings)
            throws Exception {
        String validName = name(name);
        schemaVersion(schemaVersion);
        schemaVersion(settingsSchemaVersion);
        String serialized = serialize(settings);
        return transactions.write(transaction -> {
            nameFree(transaction, validName, 0);
            long id = GameSessionRepository.insert(transaction, validName, schemaVersion, settingsSchemaVersion,
                    serialized);
            transaction.afterCommit(() -> publisher.operators(SESSIONS_INVALIDATION));
            return read(transaction, id);
        });
    }

    /**
     * Renames a session based on the settings revision it was loaded with. A retry of the rename that already
     * succeeded answers the current state.
     */
    public Session rename(long id, long expectedSettingsRevision, String name) throws Exception {
        String validName = name(name);
        return transactions.write(transaction -> {
            transaction.lockSession(id);
            Session current = read(transaction, id);
            long revision = current.settings().revision();
            if (validName.equals(current.name())
                    && (expectedSettingsRevision == revision || expectedSettingsRevision + 1 == revision)) {
                return current;
            }
            if (expectedSettingsRevision != revision) {
                throw GameErrors.conflict("Settings revision conflict: current revision is " + revision);
            }
            nameFree(transaction, validName, id);
            GameSessionRepository.rename(transaction, id, validName);
            GameSessionRepository.advanceSettings(transaction, id, revision + 1);
            transaction.bumpSession();
            transaction.afterCommit(() -> publisher.operators(SESSIONS_INVALIDATION));
            return read(transaction, id);
        });
    }

    /**
     * Replaces the settings based on the revision they were loaded with. A retry of the update that already
     * succeeded answers the current state.
     */
    public Session updateSettings(long id, long expectedRevision, int schemaVersion, JsonNode settings)
            throws Exception {
        schemaVersion(schemaVersion);
        String serialized = serialize(settings);
        return transactions.write(transaction -> {
            transaction.lockSession(id);
            Session current = read(transaction, id);
            long revision = current.settings().revision();
            if (expectedRevision != revision) {
                if (expectedRevision + 1 == revision && current.settings().schemaVersion() == schemaVersion
                        && current.settings().settings().equals(settings)) {
                    return current;
                }
                throw GameErrors.conflict("Settings revision conflict: current revision is " + revision);
            }
            GameSessionRepository.updateSettings(transaction, id, revision + 1, schemaVersion, serialized);
            transaction.bumpSession();
            // The settings hold the limits and rules that members' status shows.
            transaction.afterCommit(() -> publisher.invalidate(
                    new GamePublisher.Invalidation(id, GamePublisher.STATUS, null)));
            return read(transaction, id);
        });
    }

    /**
     * Deletes a session and every game record of it. Managed Traccar resources stay: cleaning them up is a
     * separate operation that comes first.
     */
    public void delete(long id) throws Exception {
        transactions.write(transaction -> {
            transaction.lockSession(id);
            transaction.lockMemberStates(GameMemberRepository.memberIds(transaction, id));
            // The accounts lose their membership; no member resolves to them after the commit.
            Set<Long> accounts = Set.copyOf(transaction.list("SELECT userid FROM tc_game_session_members"
                    + " WHERE gamesessionid = ? AND userid IS NOT NULL", rows -> rows.getLong(1), id));
            GameSessionRepository.delete(transaction, id);
            if (!accounts.isEmpty()) {
                transaction.afterCommit(() -> publisher.accounts(accounts, GamePublisher.STATUS));
            }
            transaction.afterCommit(() -> publisher.operators(SESSIONS_INVALIDATION));
            return null;
        });
    }

}
