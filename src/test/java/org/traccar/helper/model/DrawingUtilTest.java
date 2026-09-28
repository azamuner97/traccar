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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.traccar.model.Drawing;
import org.traccar.model.User;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DrawingUtilTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Drawing drawing(String type, String geometry) throws Exception {
        Drawing drawing = new Drawing();
        drawing.setType(type);
        drawing.setGeometry(objectMapper.readTree(geometry));
        drawing.setColor("#12abEF");
        return drawing;
    }

    @Test
    public void testValidGeometries() throws Exception {
        assertDoesNotThrow(() -> DrawingUtil.validate(drawing(
                "line", "{\"type\":\"LineString\",\"coordinates\":[[7,46],[8,47]]}")));
        assertDoesNotThrow(() -> DrawingUtil.validate(drawing(
                "arrow", "{\"type\":\"LineString\",\"coordinates\":[[7,46],[8,47]]}")));
        for (String type : new String[] {"polygon", "rectangle", "circle"}) {
            assertDoesNotThrow(() -> DrawingUtil.validate(drawing(
                    type, "{\"type\":\"Polygon\",\"coordinates\":[[[7,46],[8,46],[8,47],[7,46]]]}")));
        }
        Drawing text = drawing("text", "{\"type\":\"Point\",\"coordinates\":[7,46]}");
        text.setText("Line one\nLine two");
        for (int textSize : new int[] {12, 16, 24, 32}) {
            text.setTextSize(textSize);
            text.setTextBold(textSize == 24);
            text.setTextItalic(textSize == 32);
            assertDoesNotThrow(() -> DrawingUtil.validate(text));
            assertEquals(textSize == 24, text.getTextBold());
            assertEquals(textSize == 32, text.getTextItalic());
        }
        assertEquals("#12ABEF", text.getColor());
    }

    @Test
    public void testInvalidPayloads() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> DrawingUtil.validate(drawing(
                "unknown", "{\"type\":\"Point\",\"coordinates\":[7,46]}")));
        assertThrows(IllegalArgumentException.class, () -> DrawingUtil.validate(drawing(
                "line", "{\"type\":\"LineString\",\"coordinates\":[[181,46],[8,47]]}")));
        assertThrows(IllegalArgumentException.class, () -> DrawingUtil.validate(drawing(
                "polygon", "{\"type\":\"Polygon\",\"coordinates\":[[[7,46],[8,46],[8,47],[7,47]]]}")));
        Drawing badColor = drawing("line", "{\"type\":\"LineString\",\"coordinates\":[[7,46],[8,47]]}");
        badColor.setColor("red");
        assertThrows(IllegalArgumentException.class, () -> DrawingUtil.validate(badColor));
        Drawing longText = drawing("text", "{\"type\":\"Point\",\"coordinates\":[7,46]}");
        longText.setText("x".repeat(DrawingUtil.MAX_TEXT_LENGTH + 1));
        assertThrows(IllegalArgumentException.class, () -> DrawingUtil.validate(longText));
        Drawing invalidTextSize = drawing("text", "{\"type\":\"Point\",\"coordinates\":[7,46]}");
        invalidTextSize.setText("Label");
        invalidTextSize.setTextSize(14);
        assertThrows(IllegalArgumentException.class, () -> DrawingUtil.validate(invalidTextSize));
    }

    @Test
    public void testNonTextFormattingIsNormalized() throws Exception {
        Drawing line = drawing("line", "{\"type\":\"LineString\",\"coordinates\":[[7,46],[8,47]]}");
        line.setText("not applicable");
        line.setTextSize(32);
        line.setTextBold(true);
        line.setTextItalic(true);

        DrawingUtil.validate(line);

        assertNull(line.getText());
        assertEquals(DrawingUtil.DEFAULT_TEXT_SIZE, line.getTextSize());
        assertFalse(line.getTextBold());
        assertFalse(line.getTextItalic());
    }

    private User user(long id, String role, Long sessionDeviceId, boolean enabled) {
        User user = new User();
        user.setId(id);
        user.setDisableDrawings(!enabled);
        if (role != null) {
            user.getAttributes().put(DrawingUtil.ATTRIBUTE_USER_ROLE, role);
        }
        if (sessionDeviceId != null) {
            user.getAttributes().put(DrawingUtil.ATTRIBUTE_SESSION_DEVICE_ID, sessionDeviceId);
        }
        return user;
    }

    @Test
    public void testDrawingAccessPolicy() {
        Drawing drawing = new Drawing();
        drawing.setOwnerId(10);
        drawing.setSessionDeviceId(100L);

        User owner = user(10, "hunter", 100L, true);
        assertTrue(DrawingUtil.canView(owner, drawing));
        assertTrue(DrawingUtil.canEdit(owner, drawing));
        assertTrue(DrawingUtil.canDelete(owner, drawing));

        User disabledOwner = user(10, "hunter", 100L, false);
        assertTrue(DrawingUtil.canView(disabledOwner, drawing));
        assertFalse(DrawingUtil.canEdit(disabledOwner, drawing));
        assertFalse(DrawingUtil.canDelete(disabledOwner, drawing));

        User unrelated = user(11, "player", 100L, true);
        assertFalse(DrawingUtil.canView(unrelated, drawing));
        assertFalse(DrawingUtil.canEdit(unrelated, drawing));

        User spectator = user(12, "spectator", 100L, false);
        assertTrue(DrawingUtil.canView(spectator, drawing));
        assertFalse(DrawingUtil.canCreateOrEdit(spectator));
        assertFalse(DrawingUtil.canEdit(spectator, drawing));

        User otherSpectator = user(13, "spectator", 101L, false);
        assertFalse(DrawingUtil.canView(otherSpectator, drawing));
        User unscopedSpectator = user(14, "spectator", null, false);
        assertFalse(DrawingUtil.canView(unscopedSpectator, drawing));

        User administrator = user(15, null, null, false);
        administrator.setAdministrator(true);
        assertTrue(DrawingUtil.canView(administrator, drawing));
        assertTrue(DrawingUtil.canCreateOrEdit(administrator));
        assertFalse(DrawingUtil.canEdit(administrator, drawing));
        assertTrue(DrawingUtil.canDelete(administrator, drawing));
    }

}
