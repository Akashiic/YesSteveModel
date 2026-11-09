package com.akashic.futura.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalChoiceStoreTest {

    @Test
    void savesAndLoadsTheChoice(@TempDir Path dir) throws IOException {
        LocalChoiceStore store = new LocalChoiceStore(
            dir.resolve("config/futura3d-client.properties")
                .toFile());
        assertNull(store.load(), "nothing saved yet");
        store.save("builtin:wine_fox/18_wedding", null);
        assertArrayEquals(new String[] { "builtin:wine_fox/18_wedding", null }, store.load());
        store.save("server:hero", "red");
        assertArrayEquals(new String[] { "server:hero", "red" }, store.load());
        // Survives a new store on the same file (a restart).
        assertArrayEquals(new String[] { "server:hero", "red" }, new LocalChoiceStore(store.file()).load());
    }

    @Test
    void theDefaultModelDeletesTheFile(@TempDir Path dir) throws IOException {
        LocalChoiceStore store = new LocalChoiceStore(
            dir.resolve("c.properties")
                .toFile());
        store.save("builtin:default", "blue");
        store.save(null, null);
        assertFalse(
            store.file()
                .exists());
        assertNull(store.load());
    }

    @Test
    void aBrokenOrEmptyFileIsNoChoice(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("c.properties");
        Files.write(file, "model=\ntexture=   \n".getBytes(StandardCharsets.UTF_8));
        assertNull(new LocalChoiceStore(file.toFile()).load());
        Files.write(file, new byte[] { (byte) 0xff, '\\', 'u', 'z' });
        assertNull(new LocalChoiceStore(file.toFile()).load());
    }
}
