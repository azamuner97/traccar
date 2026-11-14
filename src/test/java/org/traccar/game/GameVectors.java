package org.traccar.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The shared test vectors in {@code src/test/resources/game-vectors}: the timetable cell grammar and the ping
 * policy. The Tool's suite runs the same files against its own code ({@code tests/.../test_shared_*_vectors.py}).
 */
final class GameVectors {

    private GameVectors() {
    }

    static JsonNode load(String name) throws Exception {
        try (InputStream input = GameVectors.class.getResourceAsStream("/game-vectors/" + name)) {
            if (input == null) {
                throw new IllegalStateException("Missing test vectors " + name);
            }
            return new ObjectMapper().readTree(input);
        }
    }

    static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        if (array != null) {
            array.forEach(item -> result.add(item.asText()));
        }
        return result;
    }

    /** A case's state over the file's defaults. */
    static JsonNode state(JsonNode document, String defaults, JsonNode item) {
        var merged = ((com.fasterxml.jackson.databind.node.ObjectNode) document.get(defaults)).deepCopy();
        item.path("state").fields().forEachRemaining(field -> merged.set(field.getKey(), field.getValue()));
        return merged;
    }

}
