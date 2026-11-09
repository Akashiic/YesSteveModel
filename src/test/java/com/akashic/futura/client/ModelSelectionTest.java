package com.akashic.futura.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The selection contract without a game running (no local player): remote selections and the handler. */
class ModelSelectionTest {

    private final ModelSelection selection = ModelSelection.INSTANCE;
    private java.util.function.Supplier<UUID> realLocalPlayer;

    @org.junit.jupiter.api.BeforeEach
    void noGame() {
        realLocalPlayer = selection.localPlayer;
        selection.localPlayer = () -> null;
    }

    @AfterEach
    void clean() {
        selection.localPlayer = realLocalPlayer;
        selection.setHandler(null);
        selection.clearRemote();
    }

    @Test
    void selectsPerPlayerAndForgetsOnClear() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        selection.select(a, "server:hero", "red");
        selection.select(b, null, "blue");
        assertEquals("server:hero", selection.keyOf(a));
        assertEquals("red", selection.textureOf(a));
        assertNull(selection.keyOf(b), "the default model, with a texture of it");
        assertEquals("blue", selection.textureOf(b));
        assertNull(selection.keyOf(UUID.randomUUID()));
        selection.select(a, null, null);
        assertNull(selection.keyOf(a));
        selection.select(b, "builtin:wine_fox/18_wedding", null);
        selection.clearRemote();
        assertNull(selection.keyOf(b));
        assertNull(selection.keyOf(null));
    }

    @Test
    void aHandlerGetsTheLocalRequestsAndNothingIsApplied() {
        List<String> sent = new ArrayList<>();
        selection.setHandler((key, texture) -> sent.add(key + "/" + texture));
        assertTrue(selection.hasHandler());
        selection.requestLocal("server:hero", null);
        selection.requestLocal(null, null);
        assertEquals(2, sent.size());
        assertEquals("server:hero/null", sent.get(0));
        assertEquals("null/null", sent.get(1));
    }
}
