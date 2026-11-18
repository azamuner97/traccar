/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import java.sql.SQLException;

/**
 * Configuration writes that the controller filter let through without the lease because their session's game was
 * not running (review SC-7). The filter decides before the write's transaction, so a game started meanwhile would
 * otherwise get the write without control. The filter marks the request thread with the session; when that thread's
 * transaction locks the session, {@link GameTransaction#lockSession} checks the game state under the lock and
 * answers 423 if the game runs now. The marker lives from the request filter to its response (or the servlet's end).
 */
public final class GameUnfencedWrites {

    /** The refusal of an unfenced write whose session's game started before it locked the session. */
    public static final String STARTED = "The game of this session runs now; this change requires the game"
            + " controller lease";

    private static final ThreadLocal<Long> SESSION = new ThreadLocal<>();

    private GameUnfencedWrites() {
    }

    /** Marks this thread's request as an unfenced write of a session that was not running. */
    public static void begin(long sessionId) {
        SESSION.set(sessionId);
    }

    /** Ends the mark of this thread's request. */
    public static void end() {
        SESSION.remove();
    }

    /** Under the session lock: refuses the marked write if the session's game runs now. */
    static void check(GameTransaction transaction, long sessionId) throws SQLException {
        Long marked = SESSION.get();
        if (marked != null && marked == sessionId && GameExecutionService.running(transaction, sessionId)) {
            throw GameControllerService.locked(STARTED);
        }
    }

}
