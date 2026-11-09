package com.akashic.futura.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.api.Test;

class ModelSourceTest {

    @Test
    void cleansManifestPaths() throws IOException {
        assertEquals("models/main.json", ModelSource.clean("models/main.json"));
        assertEquals("models/main.json", ModelSource.clean("./models/main.json"));
        assertEquals("textures/a.png", ModelSource.clean("textures\\a.png"));
        assertEquals("a..b/c.png", ModelSource.clean("a..b/c.png"));
    }

    @Test
    void refusesPathsThatLeaveTheModel() {
        for (String bad : new String[] { "../x.png", "/etc/passwd", "a/../../x", "a/..", "..", "..\\x.png" }) {
            assertThrows(IOException.class, () -> ModelSource.clean(bad), bad);
        }
    }
}
