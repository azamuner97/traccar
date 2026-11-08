/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.database;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts generated game-account passwords with a key that only exists in server configuration.
 * A missing or wrong key fails credential operations with HTTP 503 and leaves every other game-session
 * operation working.
 */
@Singleton
public class GameCredentialCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final byte[] key;
    private final String keyError;

    @Inject
    public GameCredentialCipher(Config config) {
        this(config.getString(Keys.GAME_SESSIONS_CREDENTIAL_KEY));
    }

    public GameCredentialCipher(String encodedKey) {
        byte[] decoded = null;
        String error = null;
        if (encodedKey == null || encodedKey.isBlank()) {
            error = "Game session credential key is not configured (gameSessions.credentialKey)";
        } else {
            try {
                decoded = Base64.getDecoder().decode(encodedKey.trim());
                if (decoded.length != 32) {
                    error = "Game session credential key must be 32 bytes encoded as Base64";
                    decoded = null;
                }
            } catch (IllegalArgumentException invalid) {
                error = "Game session credential key is not valid Base64";
            }
        }
        this.key = decoded;
        this.keyError = error;
    }

    private static WebApplicationException unavailable(String message) {
        return new WebApplicationException(message, Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(message).type(MediaType.TEXT_PLAIN).build());
    }

    private byte[] requireKey() {
        if (key == null) {
            throw unavailable(keyError);
        }
        return key;
    }

    public String encrypt(String plaintext) {
        byte[] secret = requireKey();
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Credential encryption failed", error);
        }
    }

    public String decrypt(String stored) {
        byte[] secret = requireKey();
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw unavailable("Stored game session credentials have an unknown format");
        }
        try {
            byte[] data = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (data.length <= IV_LENGTH) {
                throw unavailable("Stored game session credentials are truncated");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"),
                    new GCMParameterSpec(TAG_BITS, data, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(data, IV_LENGTH, data.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (AEADBadTagException wrongKey) {
            throw unavailable("Game session credentials cannot be decrypted with the configured key");
        } catch (IllegalArgumentException | GeneralSecurityException error) {
            throw unavailable("Stored game session credentials cannot be read");
        }
    }
}
