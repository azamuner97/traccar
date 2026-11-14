package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ping engine's decision (plan B3) against the shared vectors {@code ping-policy.json}; the Tool's suite runs
 * the cases its {@code OutgoingPositionPolicy} covers. {@link GamePingEngineTest} runs the same cases through the
 * engine against a database.
 */
public class GamePingPolicyTest {

    static GamePingEngine.State state(JsonNode state) {
        return new GamePingEngine.State(state.get("pastNotAfter").asBoolean(), state.get("active").asBoolean(),
                state.get("tracked").asBoolean(), state.get("caught").asBoolean(), state.get("onBreak").asBoolean(),
                state.get("otherPlayers").asBoolean(), state.get("otherSupporters").asBoolean(),
                new LinkedHashSet<>(GameVectors.strings(state.get("mirrors"))),
                !state.get("position").asText().equals("none"), state.get("position").asText().equals("stale"),
                state.get("jokerFake").asBoolean());
    }

    @Test
    void everyCaseDecidesAsExpected() throws Exception {
        JsonNode document = GameVectors.load("ping-policy.json");
        assertTrue(document.get("cases").size() >= 40);
        for (JsonNode item : document.get("cases")) {
            String name = item.get("name").asText();
            JsonNode intent = item.get("intent");
            List<String> fake = GameVectors.strings(intent.path("fake").path("audiences"));
            var decision = GamePingEngine.decide(GameVectors.strings(intent.get("audiences")), fake,
                    state(GameVectors.state(document, "stateDefaults", item)));
            JsonNode expected = item.get("expected");
            assertEquals(expected.get("status").asText(), decision.status(), name);
            assertEquals(expected.get("reason").isNull() ? null : expected.get("reason").asText(), decision.reason(),
                    name);
            assertEquals(GameVectors.strings(expected.get("real")), decision.real(), name);
            assertEquals(GameVectors.strings(expected.get("fake")), decision.fake(), name);
            assertEquals(GameVectors.strings(expected.get("withheld")), decision.withheld(), name);
            assertEquals(expected.get("jokerFakeUsed").asBoolean(), decision.jokerFakeUsed(), name);
            assertEquals(expected.get("stale").asBoolean(), decision.stale(), name);
        }
    }

}
