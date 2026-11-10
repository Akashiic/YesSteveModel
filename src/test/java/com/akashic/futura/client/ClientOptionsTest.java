package com.akashic.futura.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The client options file: defaults written on first start, values read back, broken values ignored. */
class ClientOptionsTest {

    @TempDir
    Path dir;

    @Test
    void writesTheDefaultsAndReadsChanges() throws IOException {
        File file = dir.resolve("futura3d/client.properties")
            .toFile();
        ClientOptions options = new ClientOptions(null);
        options.init(file);
        assertTrue(file.isFile(), "defaults written");
        assertTrue(options.viewLocatorCamera(), "the camera follows the model by default");
        assertFalse(options.printRouletteMessage(), "upstream's PrintAnimationRouletteMsg is off by default");
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("view_locator_camera=true"), text);

        Files.write(
            file.toPath(),
            "view_locator_camera = off\nprint_animation_roulette_msg=yes\n".getBytes(StandardCharsets.UTF_8));
        options.reload();
        assertFalse(options.viewLocatorCamera());
        assertTrue(options.printRouletteMessage());

        Files.write(file.toPath(), "view_locator_camera=maybe\n".getBytes(StandardCharsets.UTF_8));
        options.reload();
        assertTrue(options.viewLocatorCamera(), "an unknown value: the default");
        assertFalse(options.printRouletteMessage(), "a missing key: the default");
    }

    @Test
    void anExistingFileIsNotOverwritten() throws IOException {
        File file = dir.resolve("client.properties")
            .toFile();
        Files.write(file.toPath(), "view_locator_camera=false\n".getBytes(StandardCharsets.UTF_8));
        ClientOptions options = new ClientOptions(null);
        options.init(file);
        assertFalse(options.viewLocatorCamera());
        assertEquals(
            "view_locator_camera=false\n",
            new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    void parsesBooleans() {
        assertTrue(ClientOptions.bool("TRUE", false));
        assertTrue(ClientOptions.bool(" 1 ", false));
        assertFalse(ClientOptions.bool("no", true));
        assertTrue(ClientOptions.bool(null, true));
        assertFalse(ClientOptions.bool("", false));
    }
}
