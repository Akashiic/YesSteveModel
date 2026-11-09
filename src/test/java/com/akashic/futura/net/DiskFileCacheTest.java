package com.akashic.futura.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskFileCacheTest {

    @TempDir
    Path tmp;

    @Test
    void storesVerifiesAndDropsCorruptFiles() throws IOException {
        DiskFileCache cache = new DiskFileCache(
            tmp.resolve("cache")
                .toFile());
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        Sha256 h = Sha256.of(data);
        assertFalse(cache.has(h, 5));
        assertNull(cache.read(h));
        cache.store(h, data);
        assertTrue(cache.has(h, 5));
        assertFalse(cache.has(h, 6));
        assertArrayEquals(data, cache.read(h));
        File[] left = cache.dir()
            .listFiles();
        assertEquals(1, left.length, "no temporary files left");
        assertEquals(h.hex(), left[0].getName());

        // same size, other bytes: refused and deleted
        Files.write(left[0].toPath(), "jello".getBytes(StandardCharsets.UTF_8));
        assertTrue(cache.has(h, 5));
        assertNull(cache.read(h));
        assertFalse(left[0].exists());
    }

    @Test
    void cachedSourceReadsByManifestPath() throws IOException {
        DiskFileCache cache = new DiskFileCache(tmp.toFile());
        byte[] manifest = "{\"spec\":2}".getBytes(StandardCharsets.UTF_8);
        byte[] tex = { 1, 2, 3 };
        List<FileRef> files = Arrays.asList(
            new FileRef("ysm.json", manifest.length, Sha256.of(manifest)),
            new FileRef("textures/a.png", tex.length, Sha256.of(tex)));
        CatalogEntry entry = new CatalogEntry(
            CatalogEntry.computeId(files),
            "m",
            "",
            "",
            Arrays.<String>asList(),
            false,
            false,
            files);
        cache.store(Sha256.of(manifest), manifest);
        CachedModelSource source = new CachedModelSource(entry, cache);
        try (InputStream in = source.open("./ysm.json")) {
            assertArrayEquals(manifest, readAll(in));
        }
        assertNull(source.open("textures/missing.png"));
        // listed but not in the cache
        assertThrows(IOException.class, () -> source.open("textures\\a.png"));
        cache.store(Sha256.of(tex), tex);
        try (InputStream in = source.open("textures\\a.png")) {
            assertArrayEquals(tex, readAll(in));
        }
        assertThrows(IOException.class, () -> source.open("../ysm.json"));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[256];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
