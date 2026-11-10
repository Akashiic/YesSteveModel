package com.akashic.futura.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

class ModelAccessTest {

    @Test
    void unknownLocksNothing() {
        ModelAccess a = new ModelAccess();
        assertFalse(a.locked("builtin:default"));
        assertFalse(a.locked(null));
    }

    @Test
    void listsOfSelectableOrLockedModelsInParts() {
        ModelAccess a = new ModelAccess();
        a.apply(true, true, Arrays.asList("builtin:misc/1_alex"));
        a.apply(false, true, Arrays.asList("builtin:misc/2_steve"));
        assertFalse(a.locked("builtin:misc/1_alex"));
        assertFalse(a.locked("builtin:misc/2_steve"));
        assertTrue(a.locked("builtin:default"));
        assertFalse(a.locked(null), "the own skin is never locked");

        a.apply(true, false, Collections.singletonList("builtin:default"));
        assertTrue(a.locked("builtin:default"));
        assertFalse(a.locked("builtin:wine_fox/18_wedding"), "a new list replaces the old one");

        a.clear();
        assertFalse(a.locked("builtin:default"));
    }
}
