/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import java.time.Instant;
import java.util.List;

/**
 * One kind of server execution (plan B2): timetable slots, live uploads and relays, or break timers.
 * The execution task asks every runner which work is due for the running session and runs it in order on the
 * execution thread; the task owns the order and the gate (the game must run).
 *
 * @param <T> the runner's work item
 */
public interface GameExecutionRunner<T> {

    /** The work due at {@code now} for the running session, in the order it should run. */
    List<T> due(long sessionId, Instant now) throws Exception;

    /** Runs one item. It may start waits that continue later on the execution thread. */
    void run(long sessionId, T item) throws Exception;

}
