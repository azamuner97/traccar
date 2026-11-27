/*
 * Copyright 2026 Traccar Tool contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.traccar.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One timetable cell, parsed by the grammar of the Tool's {@code parse_timetable_instruction}
 * ({@code domain/timetable.py}; Claude outputs/timetable-instructions-effects-plan.md, 4):
 * <ul>
 * <li>blank or whitespace: no ping ({@link Kind#NONE}). Changesets store a blank cell as no entry, so a stored entry
 * is never blank;</li>
 * <li>{@code p}: the real position to hunters, players and supporters;</li>
 * <li>{@code b}: the real position to players and supporters (break mode);</li>
 * <li>a target code ({@code 2H}, {@code 2S}, {@code 2S2H}, {@code 2P}, {@code 2P2H}, {@code 2P2S},
 * {@code 2P2S2H}): the real position to the code's audiences;</li>
 * <li>{@code lat,lon} or {@code lat,lon|code}: the coordinate as a fake to the code's audiences (default
 * {@code 2H}; {@code p} names all three), and the real position to the remaining audiences;</li>
 * <li>anything else is invalid, including {@code lat,lon|b}.</li>
 * </ul>
 * Content is trimmed. Unlike the Tool, the server also accepts lowercase target codes and stores every valid cell
 * in its canonical spelling ({@code P} → {@code p}, {@code 2h} → {@code 2H}, {@code 47.1 , 8.5 | 2p} →
 * {@code 47.1,8.5|2P}, {@code 47.1,8.5|P} → {@code 47.1,8.5|p}); coordinate numbers keep their digits.
 */
public record TimetableInstruction(Kind kind, String canonical, List<String> audiences, Double latitude,
                                   Double longitude, List<String> fakeAudiences, String error) {

    public enum Kind { NONE, REAL, COORDINATE, INVALID }

    public static final String HUNTERS = "hunters";
    public static final String PLAYERS = "players";
    public static final String SUPPORTERS = "supporters";
    /** Audiences in delivery order: hunters first, then players, then supporters (the Tool's order). */
    public static final List<String> AUDIENCES = List.of(HUNTERS, PLAYERS, SUPPORTERS);
    public static final int MAX_LENGTH = 512;

    /** The real ping to everyone, and the coordinate target naming everyone. */
    public static final String ALL = "p";

    private static final String SEPARATOR = "|";
    private static final Pattern COMPONENT = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");
    /** The target codes, players first: each names its audiences. */
    private static final Map<String, List<String>> CODES = codes();

    private static Map<String, List<String>> codes() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (int mask = 1; mask < 8; mask++) {
            boolean players = (mask & 4) != 0;
            boolean supporters = (mask & 2) != 0;
            boolean hunters = (mask & 1) != 0;
            result.put(code(hunters, players, supporters), ordered(hunters, players, supporters));
        }
        return Collections.unmodifiableMap(result);
    }

    private static String code(boolean hunters, boolean players, boolean supporters) {
        return (players ? "2P" : "") + (supporters ? "2S" : "") + (hunters ? "2H" : "");
    }

    private static List<String> ordered(boolean hunters, boolean players, boolean supporters) {
        List<String> result = new ArrayList<>();
        if (hunters) {
            result.add(HUNTERS);
        }
        if (players) {
            result.add(PLAYERS);
        }
        if (supporters) {
            result.add(SUPPORTERS);
        }
        return List.copyOf(result);
    }

    /** The target code of a set of audiences, players first, or {@code -} for none (the Tool's target_code). */
    public static String code(List<String> audiences) {
        String code = code(audiences.contains(HUNTERS), audiences.contains(PLAYERS), audiences.contains(SUPPORTERS));
        return code.isEmpty() ? "-" : code;
    }

    /** The audiences of a target code in any letter case, or null when it is not a target code. */
    public static List<String> audiencesOf(String code) {
        return code == null ? null : CODES.get(code.trim().toUpperCase(Locale.ROOT));
    }

    private static TimetableInstruction invalid(String value, String reason) {
        return new TimetableInstruction(Kind.INVALID, value, List.of(), null, null, List.of(),
                "invalid timetable coordinate '" + value + "': " + reason);
    }

    private static TimetableInstruction real(String canonical, List<String> audiences) {
        return new TimetableInstruction(Kind.REAL, canonical, audiences, null, null, List.of(), null);
    }

    /** Parses one cell; null reads as blank (no ping). */
    public static TimetableInstruction parse(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() > MAX_LENGTH) {
            return new TimetableInstruction(Kind.INVALID, normalized, List.of(), null, null, List.of(),
                    "a timetable cell has at most " + MAX_LENGTH + " characters");
        }
        if (normalized.isEmpty()) {
            return new TimetableInstruction(Kind.NONE, "", List.of(), null, null, List.of(), null);
        }
        if (normalized.equalsIgnoreCase(ALL)) {
            return real(ALL, AUDIENCES);
        }
        if (normalized.equalsIgnoreCase("b")) {
            return real("b", List.of(PLAYERS, SUPPORTERS));
        }
        List<String> target = audiencesOf(normalized);
        if (target != null) {
            return real(normalized.toUpperCase(Locale.ROOT), target);
        }
        if (!normalized.contains(",")) {
            return new TimetableInstruction(Kind.INVALID, normalized, List.of(), null, null, List.of(),
                    "timetable value '" + normalized + "' is not supported");
        }
        String[] parts = normalized.split(Pattern.quote(SEPARATOR), -1);
        if (parts.length > 2) {
            return invalid(normalized, "use at most one '|' target separator");
        }
        String targetValue = "2H";
        boolean explicitTarget = parts.length == 2;
        if (explicitTarget) {
            targetValue = parts[1].strip();
            if (targetValue.isEmpty()) {
                return invalid(normalized, "the target code after '|' is empty");
            }
            if (targetValue.equalsIgnoreCase("b")) {
                return invalid(normalized, "break mode 'b' cannot be used as a coordinate target");
            }
        }
        String[] coordinate = parts[0].strip().split(",", -1);
        if (coordinate.length != 2 || coordinate[0].isBlank() || coordinate[1].isBlank()) {
            return invalid(normalized, "use latitude,longitude with exactly one comma");
        }
        String latitudeText = coordinate[0].strip();
        String longitudeText = coordinate[1].strip();
        if (!COMPONENT.matcher(latitudeText).matches() || !COMPONENT.matcher(longitudeText).matches()) {
            return invalid(normalized, "latitude and longitude must be decimal numbers using '.'");
        }
        double latitude = Double.parseDouble(latitudeText);
        double longitude = Double.parseDouble(longitudeText);
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            return invalid(normalized, "latitude and longitude must be finite numbers");
        }
        if (latitude < -90 || latitude > 90) {
            return invalid(normalized, "latitude must be between -90 and 90");
        }
        if (longitude < -180 || longitude > 180) {
            return invalid(normalized, "longitude must be between -180 and 180");
        }
        boolean all = targetValue.equalsIgnoreCase(ALL);
        List<String> fake = all ? AUDIENCES : audiencesOf(targetValue);
        if (fake == null) {
            return invalid(normalized, "target code '" + targetValue + "' is not supported");
        }
        List<String> rest = new ArrayList<>();
        for (String audience : AUDIENCES) {
            if (!fake.contains(audience)) {
                rest.add(audience);
            }
        }
        String canonical = latitudeText + "," + longitudeText
                + (explicitTarget ? SEPARATOR + (all ? ALL : targetValue.toUpperCase(Locale.ROOT)) : "");
        return new TimetableInstruction(Kind.COORDINATE, canonical, List.copyOf(rest), latitude, longitude, fake,
                null);
    }

    public boolean valid() {
        return kind != Kind.INVALID;
    }

    /** Whether the cell sends nothing: blank. */
    public boolean none() {
        return kind == Kind.NONE;
    }

    /**
     * Whether a fake-ping joker can replace this cell: it sends the real position to the hunters ({@code p} or a
     * target code with hunters). Not blank, {@code b}, coordinates or codes without hunters.
     */
    public boolean fakeEligible() {
        return kind == Kind.REAL && audiences.contains(HUNTERS);
    }

    /** Whether hunters receive a position from this cell: real, or an operator coordinate's fake or real part. */
    public boolean hunterFacing() {
        return kind == Kind.REAL && audiences.contains(HUNTERS) || kind == Kind.COORDINATE;
    }

    public static boolean fakeEligible(String content) {
        return parse(content).fakeEligible();
    }

}
