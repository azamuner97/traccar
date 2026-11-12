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

    private static DrawingUtil.Membership member(String role, long session) {
        return new DrawingUtil.Membership(session, role);
    }

    private User user(long id, boolean enabled) {
        User user = new User();
        user.setId(id);
        user.setDisableDrawings(!enabled);
        return user;
    }

    @Test
    public void testDrawingAccessPolicy() {
        Drawing drawing = new Drawing();
        drawing.setOwnerId(10);
        drawing.setGameSessionId(100L);

        User owner = user(10, true);
        var hunter = member("hunter", 100);
        assertTrue(DrawingUtil.canView(owner, hunter, drawing));
        assertTrue(DrawingUtil.canEdit(owner, hunter, drawing));
        assertTrue(DrawingUtil.canDelete(owner, hunter, drawing));

        User disabledOwner = user(10, false);
        assertTrue(DrawingUtil.canView(disabledOwner, hunter, drawing));
        assertFalse(DrawingUtil.canEdit(disabledOwner, hunter, drawing));
        assertFalse(DrawingUtil.canDelete(disabledOwner, hunter, drawing));

        User unrelated = user(11, true);
        assertFalse(DrawingUtil.canView(unrelated, member("player", 100), drawing));
        assertFalse(DrawingUtil.canEdit(unrelated, member("player", 100), drawing));

        User spectator = user(12, false);
        var watching = member("spectator", 100);
        assertTrue(DrawingUtil.canView(spectator, watching, drawing));
        assertFalse(DrawingUtil.canCreateOrEdit(spectator, watching));
        assertFalse(DrawingUtil.canEdit(spectator, watching, drawing));
        assertFalse(DrawingUtil.canView(user(13, false), member("spectator", 101), drawing));
        // An account without membership is no spectator, whatever its attributes say.
        User marked = user(14, true);
        marked.getAttributes().put("traccarToolUserRole", "spectator");
        marked.getAttributes().put("traccarToolSessionDeviceId", 100);
        assertFalse(DrawingUtil.canView(marked, null, drawing));
        assertTrue(DrawingUtil.canCreateOrEdit(marked, null));

        User administrator = user(15, false);
        administrator.setAdministrator(true);
        assertTrue(DrawingUtil.canView(administrator, null, drawing));
        assertTrue(DrawingUtil.canCreateOrEdit(administrator, null));
        assertFalse(DrawingUtil.canEdit(administrator, null, drawing));
        assertTrue(DrawingUtil.canDelete(administrator, null, drawing));
    }

    @Test
    public void testSharingIsSameRoleSameSessionAndRequiresBothAccounts() {
        for (String role : new String[] {"player", "supporter", "hunter"}) {
            User owner = user(10, true);
            User peer = user(11, true);
            var ownerMember = member(role, 100);
            var peerMember = member(role, 100);
            Drawing drawing = new Drawing();
            drawing.setOwnerId(10);
            drawing.setGameSessionId(100L);
            owner.getAttributes().put(DrawingUtil.ATTRIBUTE_SHARE_GROUP_DRAWINGS, true);
            assertFalse(DrawingUtil.canView(peer, peerMember, drawing, owner, ownerMember));
            peer.getAttributes().put(DrawingUtil.ATTRIBUTE_SHARE_GROUP_DRAWINGS, true);
            assertTrue(DrawingUtil.canView(peer, peerMember, drawing, owner, ownerMember));
            assertTrue(DrawingUtil.canEdit(peer, peerMember, drawing, owner, ownerMember));
            assertTrue(DrawingUtil.canDelete(peer, peerMember, drawing, owner, ownerMember));
            assertFalse(DrawingUtil.canEdit(peer, peerMember, drawing));
            assertFalse(DrawingUtil.canView(peer, peerMember, drawing, owner, null));
            owner.getAttributes().put(DrawingUtil.ATTRIBUTE_SHARE_GROUP_DRAWINGS, false);
            assertFalse(DrawingUtil.canView(peer, peerMember, drawing, owner, ownerMember));
            owner.getAttributes().put(DrawingUtil.ATTRIBUTE_SHARE_GROUP_DRAWINGS, "true");
            assertFalse(DrawingUtil.canView(peer, peerMember, drawing, owner, ownerMember));
            owner.getAttributes().put(DrawingUtil.ATTRIBUTE_SHARE_GROUP_DRAWINGS, true);
            owner.setDisableDrawings(true);
            assertFalse(DrawingUtil.canView(peer, peerMember, drawing, owner, ownerMember));
            owner.setDisableDrawings(false);
            peer.setDisableDrawings(true);
            assertFalse(DrawingUtil.canView(peer, peerMember, drawing, owner, ownerMember));
            peer.setDisableDrawings(false);
            assertFalse(DrawingUtil.canEdit(peer, member(role, 101), drawing, owner, ownerMember));
            // The spectator sees every drawing of its session, shared or not, and edits none.
            assertTrue(DrawingUtil.canView(peer, member("spectator", 100), drawing, owner, ownerMember));
            assertFalse(DrawingUtil.canEdit(peer, member("spectator", 100), drawing, owner, ownerMember));
            assertFalse(DrawingUtil.canView(peer, null, drawing, owner, ownerMember));
            drawing.setGameSessionId(101L);
            assertFalse(DrawingUtil.canView(peer, peerMember, drawing, owner, ownerMember));
            drawing.setGameSessionId(null);
            assertFalse(DrawingUtil.canView(peer, peerMember, drawing, owner, ownerMember));
        }
    }

}
