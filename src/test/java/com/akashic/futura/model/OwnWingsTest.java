package com.akashic.futura.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

class OwnWingsTest {

    /** {@code wine_fox/21_saint} shows its own {@code wings} bone with {@code ysm.has_elytra}; the default has none. */
    @Test
    void aModelThatAsksHasElytraDrawsItsOwnWings() throws IOException {
        assertTrue(load("wine_fox/21_saint").ownWings());
        assertFalse(load("default").ownWings());
        assertFalse(load("misc/2_steve").ownWings());
    }

    private static LoadedModel load(String folder) throws IOException {
        return ModelLoader.load(new ClasspathModelSource("/assets/futura3d/builtin/" + folder + "/"));
    }
}
