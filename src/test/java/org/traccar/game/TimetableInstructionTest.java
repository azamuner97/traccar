package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The timetable cell grammar (plan 1.2) against the shared vectors {@code timetable-instructions.json}, which the
 * Tool's suite runs against {@code parse_timetable_instruction}.
 */
public class TimetableInstructionTest {

    @Test
    void everyVectorParsesAsTheToolDoes() throws Exception {
        JsonNode cases = GameVectors.load("timetable-instructions.json").get("cases");
        assertTrue(cases.size() > 30);
        for (JsonNode item : cases) {
            String input = item.get("input").asText();
            TimetableInstruction instruction = TimetableInstruction.parse(input);
            String kind = item.get("kind").asText();
            assertEquals(kind, instruction.kind().name().toLowerCase(), input);
            assertEquals(item.get("fakeEligible").asBoolean(), instruction.fakeEligible(), input);
            assertEquals(item.get("hunterFacing").asBoolean(), instruction.hunterFacing(), input);
            if (kind.equals("invalid")) {
                assertFalse(instruction.valid(), input);
                assertNotNull(instruction.error(), input);
                assertTrue(instruction.error().contains(item.get("error").asText()), input + ": " + instruction.error());
                continue;
            }
            assertEquals(item.get("canonical").asText(), instruction.canonical(), input);
            // The canonical spelling parses to itself.
            assertEquals(instruction, TimetableInstruction.parse(instruction.canonical()), input);
            assertEquals(GameVectors.strings(item.get("audiences")), instruction.audiences(), input);
            if (kind.equals("coordinate")) {
                assertEquals(item.get("coordinate").get(0).asDouble(), instruction.latitude(), input);
                assertEquals(item.get("coordinate").get(1).asDouble(), instruction.longitude(), input);
                assertEquals(GameVectors.strings(item.get("fakeAudiences")), instruction.fakeAudiences(), input);
            }
        }
    }

    @Test
    void cellsAreLimitedAndCodesNameTheirAudiences() {
        assertFalse(TimetableInstruction.parse("x".repeat(513)).valid());
        assertTrue(TimetableInstruction.parse(null).fakeEligible());
        assertEquals("2P2S2H", TimetableInstruction.code(TimetableInstruction.AUDIENCES));
        assertEquals("-", TimetableInstruction.code(List.of()));
        assertEquals("2S2H", TimetableInstruction.code(List.of("supporters", "hunters")));
    }

}
