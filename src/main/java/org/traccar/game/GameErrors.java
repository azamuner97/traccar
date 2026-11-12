/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;

import java.sql.SQLException;

/** Errors of the game services of data model 2, as the HTTP status that the REST layer answers with. */
public final class GameErrors {

    public static final int CONFLICT = 409;

    /** MySQL reports a violated CHECK constraint with this vendor code and SQLState HY000. */
    private static final int MYSQL_CHECK_VIOLATION = 3819;

    private GameErrors() {
    }

    /** A request that conflicts with the stored state or a game rule. */
    public static WebApplicationException conflict(String message) {
        return new WebApplicationException(message, CONFLICT);
    }

    /** A write based on an outdated revision of a revisioned record. */
    public static WebApplicationException revisionConflict(String label, long current) {
        return conflict(label + " revision conflict: current revision is " + current);
    }

    public static NotFoundException notFound(String message) {
        return new NotFoundException(message);
    }

    public static BadRequestException invalid(String message) {
        return new BadRequestException(message);
    }

    /** Unique, foreign key and CHECK violations on H2 and MySQL. */
    public static boolean constraintViolation(SQLException error) {
        for (SQLException current = error; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.startsWith("23") || current.getErrorCode() == MYSQL_CHECK_VIOLATION) {
                return true;
            }
        }
        return false;
    }

    /** Validates a display text: not blank, at most {@code limit} characters, no control characters. */
    public static String text(String value, int limit, String label) {
        if (value == null || value.isBlank() || value.length() > limit
                || value.chars().anyMatch(character -> character < 32 || character == 127)) {
            throw invalid("Invalid " + label);
        }
        return value;
    }

}
