package com.akashic.futura.natives;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeLoaderTest {

    @TempDir
    Path tmp;

    @Test
    void mapsPlatforms() {
        assertEquals("linux-x86_64", NativeLoader.platform("Linux", "amd64"));
        assertEquals("windows-x86_64", NativeLoader.platform("Windows 10", "amd64"));
        assertEquals("windows-x86_64", NativeLoader.platform("Windows 11", "x86_64"));
        assertNull(NativeLoader.platform("Mac OS X", "aarch64"));
        assertNull(NativeLoader.platform("Linux", "aarch64"));
        assertNull(NativeLoader.platform("Windows 10", "x86"));
        assertEquals("futura3d.dll", NativeLoader.libraryName("windows-x86_64"));
        assertEquals("libfutura3d.so", NativeLoader.libraryName("linux-x86_64"));
    }

    /** Both shipped libraries are in the jar resources and match their checksums; extraction is idempotent. */
    @Test
    void extractsTheShippedLibraries() throws IOException {
        for (String platform : new String[] { "linux-x86_64", "windows-x86_64" }) {
            File first = NativeLoader.extract(platform, tmp.toFile());
            File again = NativeLoader.extract(platform, tmp.toFile());
            assertEquals(first, again);
            assertTrue(first.length() > 100_000, platform);
            assertTrue(
                first.getName()
                    .matches("(lib)?futura3d-[0-9a-f]{16}\\.(so|dll)"),
                first.getName());
        }
    }

    /**
     * The real library on this machine (Linux x86_64 in CI): the envelope of upstream's frozen vector decrypts (its
     * plaintext is random bytes, so the container then fails), and garbage is refused, both as IOException.
     */
    @Test
    void decodesThroughJni() throws IOException {
        assumeTrue("linux-x86_64".equals(NativeLoader.platform()));
        System.setProperty("futura3d.native.dir", tmp.toString());
        assertTrue(LegacyNative.available(), NativeLoader.detail());
        byte[] vector = Files.readAllBytes(Paths.get("native/test/data/legacy_v3_dynamic_vector.ysm"));
        IOException container = assertThrows(IOException.class, () -> LegacyNative.convert(vector));
        assertTrue(
            container.getMessage()
                .startsWith("Legacy container"),
            container.getMessage());
        IOException envelope = assertThrows(IOException.class, () -> LegacyNative.convert(new byte[] { 1, 2, 3 }));
        assertTrue(
            envelope.getMessage()
                .startsWith("Legacy envelope"),
            envelope.getMessage());
    }
}
