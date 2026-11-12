/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.helper.model;

import com.fasterxml.jackson.databind.JsonNode;
import org.traccar.model.Drawing;
import org.traccar.model.User;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation and access rules of map drawings. Game sharing reads membership (game data model 2): the active member
 * an account is bound to, given as a {@link Membership}. Only the sharing switch
 * ({@value #ATTRIBUTE_SHARE_GROUP_DRAWINGS}) is an account attribute, which administrators set; no user attribute
 * says who takes part in a game.
 * <ul>
 * <li>Owners see their drawings, and edit them while drawings are enabled for them.</li>
 * <li>The spectator sees every drawing of its session and edits none.</li>
 * <li>Players, supporters and hunters with sharing switched on see and edit the drawings of the members with the same
 * role in the same session who also share, made while they were members of that session.</li>
 * <li>Administrators see and delete every drawing.</li>
 * </ul>
 */
public final class DrawingUtil {

    public static final String ATTRIBUTE_SHARE_GROUP_DRAWINGS = "traccarToolShareGroupDrawings";
    public static final String ROLE_SPECTATOR = "spectator";

    public static final int MAX_TEXT_LENGTH = 200;
    public static final int MAX_COORDINATES = 1000;
    public static final int DEFAULT_TEXT_SIZE = 16;

    private static final Set<String> TYPES = Set.of(
            "line", "arrow", "polygon", "rectangle", "circle", "text");
    private static final Set<String> GROUP_ROLES = Set.of("player", "supporter", "hunter");
    private static final Set<Integer> TEXT_SIZES = Set.of(12, 16, 24, 32);
    private static final Pattern COLOR_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /** The game membership of an account: its session and role, or none ({@code null}). */
    public record Membership(long gameSessionId, String role) {
    }

    private DrawingUtil() {
    }

    public static boolean isSpectator(Membership membership) {
        return membership != null && ROLE_SPECTATOR.equals(membership.role());
    }

    public static boolean canCreateOrEdit(User user, Membership membership) {
        return user.getAdministrator() || !user.getDisableDrawings() && !isSpectator(membership);
    }

    public static boolean hasGroupSharing(User user, Membership membership) {
        return !user.getAdministrator() && !user.getDisableDrawings()
                && membership != null && GROUP_ROLES.contains(membership.role())
                && Boolean.TRUE.equals(user.getAttributes().get(ATTRIBUTE_SHARE_GROUP_DRAWINGS));
    }

    private static boolean shared(User user, Membership membership, Drawing drawing,
                                  User owner, Membership ownerMembership) {
        return owner != null && owner.getId() == drawing.getOwnerId()
                && hasGroupSharing(user, membership) && hasGroupSharing(owner, ownerMembership)
                && membership.role().equals(ownerMembership.role())
                && membership.gameSessionId() == ownerMembership.gameSessionId()
                && Long.valueOf(membership.gameSessionId()).equals(drawing.getGameSessionId());
    }

    public static boolean canView(User user, Membership membership, Drawing drawing) {
        return canView(user, membership, drawing, null, null);
    }

    public static boolean canView(User user, Membership membership, Drawing drawing,
                                  User owner, Membership ownerMembership) {
        if (user.getAdministrator() || drawing.getOwnerId() == user.getId()) {
            return true;
        }
        return isSpectator(membership) && Long.valueOf(membership.gameSessionId()).equals(drawing.getGameSessionId())
                || shared(user, membership, drawing, owner, ownerMembership);
    }

    public static boolean canEdit(User user, Membership membership, Drawing drawing) {
        return canEdit(user, membership, drawing, null, null);
    }

    public static boolean canEdit(User user, Membership membership, Drawing drawing,
                                  User owner, Membership ownerMembership) {
        return canCreateOrEdit(user, membership)
                && (drawing.getOwnerId() == user.getId() || shared(user, membership, drawing, owner, ownerMembership));
    }

    public static boolean canDelete(User user, Membership membership, Drawing drawing) {
        return canDelete(user, membership, drawing, null, null);
    }

    public static boolean canDelete(User user, Membership membership, Drawing drawing,
                                    User owner, Membership ownerMembership) {
        return user.getAdministrator() || canEdit(user, membership, drawing, owner, ownerMembership);
    }

    public static void validate(Drawing drawing) {
        if (drawing.getType() == null || !TYPES.contains(drawing.getType())) {
            throw new IllegalArgumentException("Invalid drawing type");
        }
        if (drawing.getColor() == null || !COLOR_PATTERN.matcher(drawing.getColor()).matches()) {
            throw new IllegalArgumentException("Invalid drawing color");
        }
        JsonNode geometry = drawing.getGeometry();
        if (geometry == null || !geometry.isObject() || !geometry.hasNonNull("type")
                || !geometry.hasNonNull("coordinates")) {
            throw new IllegalArgumentException("Invalid drawing geometry");
        }
        String geometryType = geometry.get("type").asText();
        switch (drawing.getType()) {
            case "line", "arrow" -> validateLineString(geometryType, geometry.get("coordinates"));
            case "polygon", "rectangle", "circle" ->
                validatePolygon(geometryType, geometry.get("coordinates"));
            case "text" -> validatePoint(geometryType, geometry.get("coordinates"));
            default -> throw new IllegalArgumentException("Invalid drawing type");
        }
        if ("text".equals(drawing.getType())) {
            if (drawing.getText() == null || drawing.getText().isBlank()
                    || drawing.getText().length() > MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("Invalid drawing text");
            }
            if (!TEXT_SIZES.contains(drawing.getTextSize())) {
                throw new IllegalArgumentException("Invalid drawing text size");
            }
        } else {
            drawing.setText(null);
            drawing.setTextSize(DEFAULT_TEXT_SIZE);
            drawing.setTextBold(false);
            drawing.setTextItalic(false);
        }
        drawing.setColor(drawing.getColor().toUpperCase());
    }

    private static void validatePoint(String geometryType, JsonNode coordinates) {
        if (!"Point".equals(geometryType)) {
            throw new IllegalArgumentException("Drawing geometry must be a Point");
        }
        validatePosition(coordinates);
    }

    private static void validateLineString(String geometryType, JsonNode coordinates) {
        if (!"LineString".equals(geometryType) || !coordinates.isArray()
                || coordinates.size() < 2 || coordinates.size() > MAX_COORDINATES) {
            throw new IllegalArgumentException("Invalid drawing line");
        }
        coordinates.forEach(DrawingUtil::validatePosition);
    }

    private static void validatePolygon(String geometryType, JsonNode coordinates) {
        if (!"Polygon".equals(geometryType) || !coordinates.isArray() || coordinates.isEmpty()) {
            throw new IllegalArgumentException("Invalid drawing polygon");
        }
        int count = 0;
        for (JsonNode ring : coordinates) {
            if (!ring.isArray() || ring.size() < 4) {
                throw new IllegalArgumentException("Invalid drawing polygon ring");
            }
            for (JsonNode position : ring) {
                validatePosition(position);
                count += 1;
                if (count > MAX_COORDINATES) {
                    throw new IllegalArgumentException("Drawing has too many coordinates");
                }
            }
            if (!ring.get(0).equals(ring.get(ring.size() - 1))) {
                throw new IllegalArgumentException("Drawing polygon ring is not closed");
            }
        }
    }

    private static void validatePosition(JsonNode position) {
        if (!position.isArray() || position.size() != 2
                || !position.get(0).isNumber() || !position.get(1).isNumber()) {
            throw new IllegalArgumentException("Invalid drawing coordinate");
        }
        double longitude = position.get(0).asDouble();
        double latitude = position.get(1).asDouble();
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Drawing coordinate is out of range");
        }
    }

}
