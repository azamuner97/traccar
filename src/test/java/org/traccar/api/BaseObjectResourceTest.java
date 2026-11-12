package org.traccar.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BaseObjectResourceTest {

    @Test
    void anUpdateChangesOnlyTheObjectItsPathNames() {
        // The game filters judge the path's object; a body naming another object must not be applied.
        assertThrows(IllegalArgumentException.class, () -> BaseObjectResource.checkPathId(201, 7));
        assertDoesNotThrow(() -> BaseObjectResource.checkPathId(201, 201));
    }

}
