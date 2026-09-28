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

public final class DrawingUtil {

    public static final String ATTRIBUTE_USER_ROLE = "traccarToolUserRole";
    public static final String ATTRIBUTE_SESSION_DEVICE_ID = "traccarToolSessionDeviceId";
    public static final String ROLE_SPECTATOR = "spectator";

    public static final int MAX_TEXT_LENGTH = 200;
    public static final int MAX_COORDINATES = 1000;

    private static final Set<String> TYPES = Set.of(
            "line", "arrow", "polygon", "rectangle", "circle", "text");
    private static final Pattern COLOR_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private DrawingUtil() {
    }

    public static String getRole(User user) {
        Object value = user.getAttributes().get(ATTRIBUTE_USER_ROLE);
        return value != null ? value.toString() : null;
    }

    public static Long getSessionDeviceId(User user) {
        Object value = user.getAttributes().get(ATTRIBUTE_SESSION_DEVICE_ID);
        if (value instanceof Number number) {
            return number.longValue() > 0 ? number.longValue() : null;
        }
        if (value != null) {
            try {
                long parsed = Long.parseLong(value.toString());
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                // Invalid protected marker fails closed.
            }
        }
        return null;
    }

    public static boolean isSpectator(User user) {
        return ROLE_SPECTATOR.equals(getRole(user));
    }

    public static boolean canCreateOrEdit(User user) {
        return user.getAdministrator() || !user.getDisableDrawings() && !isSpectator(user);
    }

    public static boolean canView(User user, Drawing drawing) {
        if (user.getAdministrator() || drawing.getOwnerId() == user.getId()) {
            return true;
        }
        Long sessionDeviceId = getSessionDeviceId(user);
        return isSpectator(user) && sessionDeviceId != null
                && sessionDeviceId.equals(drawing.getSessionDeviceId());
    }

    public static boolean canEdit(User user, Drawing drawing) {
        return canCreateOrEdit(user) && drawing.getOwnerId() == user.getId();
    }

    public static boolean canDelete(User user, Drawing drawing) {
        return user.getAdministrator() || canEdit(user, drawing);
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
        } else {
            drawing.setText(null);
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
