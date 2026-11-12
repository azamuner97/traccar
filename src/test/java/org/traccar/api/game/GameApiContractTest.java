package org.traccar.api.game;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The game paths of {@code openapi.yaml} ({@code /game-*}) describe exactly the endpoints of the game resources of
 * data model 2 and the controller lease, and every reference in the document resolves.
 */
public class GameApiContractTest {

    private static final java.nio.file.Path SPEC = java.nio.file.Path.of("openapi.yaml");
    private static final List<Class<?>> RESOURCES = List.of(GameSessionResource.class, GameStatusResource.class,
            GameRequestResource.class, GameJokerResource.class, GameTimetableResource.class,
            GameNotificationResource.class, GameProximityResource.class, GameControllerResource.class);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec() throws Exception {
        try (Reader reader = Files.newBufferedReader(SPEC)) {
            return (Map<String, Object>) new Yaml().load(reader);
        }
    }

    private static String method(Method method) {
        if (method.isAnnotationPresent(GET.class)) {
            return "get";
        } else if (method.isAnnotationPresent(POST.class)) {
            return "post";
        } else if (method.isAnnotationPresent(PUT.class)) {
            return "put";
        } else if (method.isAnnotationPresent(DELETE.class)) {
            return "delete";
        }
        return null;
    }

    private static Set<String> implemented() {
        Set<String> result = new TreeSet<>();
        for (Class<?> resource : RESOURCES) {
            String root = "/" + resource.getAnnotation(Path.class).value();
            for (Method method : resource.getDeclaredMethods()) {
                String verb = method(method);
                if (verb != null) {
                    Path path = method.getAnnotation(Path.class);
                    result.add(verb + " " + (path == null ? root : root + "/" + path.value()));
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> documented(Map<String, Object> spec) {
        Set<String> result = new TreeSet<>();
        for (var entry : ((Map<String, Object>) spec.get("paths")).entrySet()) {
            if (!entry.getKey().startsWith("/game-")) {
                continue;
            }
            for (String key : ((Map<String, Object>) entry.getValue()).keySet()) {
                if (Set.of("get", "post", "put", "delete").contains(key)) {
                    result.add(key + " " + entry.getKey());
                }
            }
        }
        return result;
    }

    private static void references(Object node, List<String> result) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if ("$ref".equals(key)) {
                    result.add((String) value);
                } else {
                    references(value, result);
                }
            });
        } else if (node instanceof List<?> list) {
            list.forEach(item -> references(item, result));
        }
    }

    @SuppressWarnings("unchecked")
    private static Object resolve(Map<String, Object> spec, String reference) {
        Object node = spec;
        for (String part : reference.substring(2).split("/")) {
            node = node instanceof Map<?, ?> map ? ((Map<String, Object>) map).get(part) : null;
        }
        return node;
    }

    @Test
    void everyEndpointIsDocumentedAndNothingElse() throws Exception {
        assertEquals(implemented(), documented(spec()));
    }

    @Test
    void everyReferenceResolves() throws Exception {
        Map<String, Object> spec = spec();
        List<String> references = new ArrayList<>();
        references(spec, references);
        assertFalse(references.isEmpty());
        for (String reference : references) {
            assertTrue(reference.startsWith("#/"), reference);
            assertNotNull(resolve(spec, reference), reference);
        }
    }

}
