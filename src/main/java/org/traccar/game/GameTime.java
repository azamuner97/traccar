/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Game timestamps of data model 2: UTC {@code DATETIME(3)} columns.
 *
 * <p>Values travel as {@link LocalDateTime} at UTC, which neither the MySQL driver nor H2 converts, so the stored
 * wall clock is UTC whatever the time zone of the JVM or the database session. Milliseconds are truncated here so a
 * value reads back exactly as written; MySQL would otherwise round sub-millisecond digits.
 */
public final class GameTime {

    private GameTime() {
    }

    /** The earliest and latest instants game data accepts. */
    public static final Instant MIN = Instant.parse("1970-01-01T00:00:01Z");
    public static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999Z");

    /**
     * Parses an ISO-8601 instant from API input (such as {@code 2026-11-09T14:00:00Z}) and truncates it to
     * milliseconds; answers 400 for a missing, malformed or out-of-range value.
     */
    public static Instant parse(String value, String label) {
        if (value == null) {
            throw GameErrors.invalid("Invalid " + label);
        }
        Instant parsed;
        try {
            parsed = Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw GameErrors.invalid("Invalid " + label);
        }
        if (parsed.isBefore(MIN) || parsed.isAfter(MAX)) {
            throw GameErrors.invalid(label + " is outside the supported range");
        }
        return truncate(parsed);
    }

    /** Like {@link #parse}, but null stays null. */
    public static Instant parseOptional(String value, String label) {
        return value == null ? null : parse(value, label);
    }

    /** ISO-8601 text of an instant, or null. */
    public static String text(Instant value) {
        return value == null ? null : value.toString();
    }

    public static Instant truncate(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MILLIS);
    }

    public static void set(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setObject(index, LocalDateTime.ofInstant(truncate(value), ZoneOffset.UTC));
        }
    }

    public static Instant get(ResultSet rows, String column) throws SQLException {
        LocalDateTime value = rows.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

}
