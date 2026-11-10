package com.akashic.futura.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.akashic.futura.net.CatalogEntry;
import com.akashic.futura.net.FileRef;
import com.akashic.futura.net.ModelLimits;
import com.akashic.futura.net.Sha256;

class ModelRepositoryTest {

    @TempDir
    Path tmp;

    private ModelRepository.Snapshot scan(ModelLimits limits) {
        return new ModelRepository(limits, 512L << 20).scan(
            tmp.resolve("models")
                .toFile(),
            1);
    }

    private static boolean logged(ModelRepository.Snapshot s, String start, String contains) {
        for (String line : s.log) {
            if (line.startsWith(start) && line.contains(contains)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void readsFoldersAndZipsPublicAndRestricted() throws IOException {
        Path models = tmp.resolve("models");
        ServerTestSupport.copyFolder("misc/1_alex", models.resolve("alex"));
        ServerTestSupport.zipFolder("misc/2_steve", models.resolve("auth/steve.zip"), "");
        // a zip whose files sit in one top folder
        ServerTestSupport.zipFolder("misc/3_default_boy", models.resolve("boy.zip"), "default_boy/");
        Files.write(models.resolve("secret.ysm"), new byte[] { 1, 2, 3 });
        Files.write(models.resolve("notes.txt"), "hi".getBytes());
        Files.createDirectories(models.resolve("has space"));

        ModelRepository.Snapshot s = scan(new ModelLimits());
        assertEquals(3, s.models.size(), String.valueOf(s.log));
        ServerModel alex = s.byName("ALEX");
        assertNotNull(alex);
        assertFalse(alex.restricted);
        assertTrue(s.byName("steve").restricted);
        assertNotNull(s.byName("boy"));
        assertTrue(logged(s, "Skipped", "secret.ysm: not a .ysm"));
        assertTrue(logged(s, "Skipped", "notes.txt"));
        assertTrue(logged(s, "Skipped", "has space"));

        // the entry describes the files; the id is their canonical hash; the content is in memory
        CatalogEntry e = alex.entry;
        assertEquals(e.id, CatalogEntry.computeId(e.files));
        assertTrue(e.name.startsWith("Alex"));
        assertEquals("CC 0", e.license);
        assertEquals(3, e.authors.size());
        assertEquals(java.util.Collections.singletonList("gsl"), alex.textures);
        for (FileRef f : e.files) {
            byte[] data = s.content(f.hash);
            assertNotNull(data, f.path);
            assertEquals(f.size, data.length);
            assertEquals(f.hash, Sha256.of(data));
        }
        assertTrue(
            e.files.stream()
                .anyMatch(f -> f.path.equals("ysm.json")));
        assertSame(alex, s.byId(e.id));
        assertSame(
            alex,
            s.find(
                e.id.hex()
                    .substring(0, 10)));

        // scanning again gives the same ids; a zip of the same files as a folder is the same model
        ModelRepository.Snapshot again = scan(new ModelLimits());
        assertEquals(
            e.id,
            again.byName("alex")
                .id());
        ServerTestSupport.zipFolder("misc/1_alex", models.resolve("alex_copy.zip"), "");
        ModelRepository.Snapshot withCopy = scan(new ModelLimits());
        assertTrue(logged(withCopy, "Skipped", "the same files as alex"));
    }

    @Test
    void refusesZipSlipAndBombs() throws IOException {
        Path models = tmp.resolve("models");
        ServerTestSupport.zipWith("misc/1_alex", models.resolve("slip.zip"), "../../evil.json", "{}".getBytes());
        // 20 MiB of zeros compress to a few KiB
        ServerTestSupport.zipWith("misc/1_alex", models.resolve("bomb.zip"), "textures/bomb.png", new byte[20 << 20]);
        ModelLimits limits = new ModelLimits();
        limits.maxFileBytes = 1 << 20;
        ModelRepository.Snapshot s = scan(limits);
        assertTrue(s.models.isEmpty(), String.valueOf(s.log));
        assertTrue(logged(s, "Skipped slip.zip", "escapes"));
        assertTrue(logged(s, "Skipped bomb.zip", "over"));
    }

    @Test
    void refusesModelsOverTheLimitsAndBrokenModels() throws IOException {
        Path models = tmp.resolve("models");
        ServerTestSupport.copyFolder("misc/1_alex", models.resolve("alex"));
        ServerTestSupport.copyFolder("misc/1_alex", models.resolve("spec1"));
        Files.write(models.resolve("spec1/ysm.json"), "{\"spec\": 1}".getBytes(StandardCharsets.UTF_8));
        ServerTestSupport.copyFolder("misc/1_alex", models.resolve("nogeo"));
        Files.delete(models.resolve("nogeo/models/main.json"));
        Files.createDirectories(models.resolve("empty"));
        ModelLimits limits = new ModelLimits();
        ModelRepository.Snapshot s = scan(limits);
        assertEquals(1, s.models.size(), String.valueOf(s.log));
        assertTrue(logged(s, "Skipped spec1", "spec 2"));
        assertTrue(logged(s, "Skipped nogeo", "missing"));
        assertTrue(logged(s, "Skipped empty", "no ysm.json"));

        limits.maxCubes = 4;
        assertTrue(scan(limits).models.isEmpty());
        ModelRepository small = new ModelRepository(new ModelLimits(), 1000);
        assertTrue(small.scan(models.toFile(), 2).models.isEmpty());
    }

    @Test
    void leavesOutOtherFilesAndDoesNotFollowLinks() throws IOException {
        Path models = tmp.resolve("models");
        Path alex = models.resolve("alex");
        ServerTestSupport.copyFolder("misc/1_alex", alex);
        Files.write(alex.resolve("source.bbmodel"), "{}".getBytes());
        Files.write(alex.resolve(".hidden.json"), "{}".getBytes());
        Path outside = tmp.resolve("outside.json");
        Files.write(outside, "{\"secret\":1}".getBytes());
        boolean linked;
        try {
            Files.createSymbolicLink(alex.resolve("link.json"), outside);
            linked = true;
        } catch (UnsupportedOperationException | IOException e) {
            linked = false;
        }
        ModelRepository.Snapshot s = scan(new ModelLimits());
        List<String> paths = new ArrayList<>();
        for (FileRef f : s.byName("alex").entry.files) {
            paths.add(f.path);
        }
        assertFalse(paths.contains("source.bbmodel"));
        assertFalse(paths.contains(".hidden.json"));
        if (linked) {
            assertFalse(paths.contains("link.json"));
        }
        assertNull(s.byName("missing"));
    }

    /**
     * Legacy .ysm files: a v2 bundle (pure Java) and an encrypted v3 (the native loader, here the synthetic export of
     * native/test/native_test.cc through a stub decoder) are converted in memory and marked memory only; clients
     * before protocol 3 are neither offered them nor sent their own files.
     */
    @Test
    void readsLegacyYsmAsMemoryOnly() throws IOException {
        Path models = tmp.resolve("models");
        Files.createDirectories(models);
        ServerTestSupport.copyFolder("misc/2_steve", models.resolve("steve"));
        Files.write(
            models.resolve("alex.ysm"),
            com.akashic.futura.model.legacy.LegacyTestSupport
                .bundle(2, com.akashic.futura.model.legacy.LegacyTestSupport.builtin("misc/1_alex"), 9));
        Files.write(
            models.resolve("synthetic.ysm"),
            new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'Y', 'S', 'G', 'P', 0, 3, 0, 0, 0 });
        byte[] export = com.akashic.futura.model.legacy.LegacyTestSupport.syntheticExport();
        ModelRepository.Snapshot s = new ModelRepository(new ModelLimits(), 512L << 20, file -> export)
            .scan(models.toFile(), 1);
        assertEquals(3, s.models.size(), String.valueOf(s.log));
        ServerModel alex = s.byName("alex");
        ServerModel synthetic = s.byName("synthetic");
        assertTrue(alex.entry.memoryOnly);
        assertTrue(synthetic.entry.memoryOnly);
        assertFalse(s.byName("steve").entry.memoryOnly);
        assertTrue(logged(s, "Loaded", "memory only"));
        assertEquals(java.util.Collections.singletonList("skin"), synthetic.textures);

        assertEquals(
            3,
            s.offered(3)
                .size());
        assertEquals(
            1,
            s.offered(2)
                .size());
        FileRef ysmJson = null;
        for (FileRef f : synthetic.entry.files) {
            if (f.path.equals("ysm.json")) {
                ysmJson = f;
            }
        }
        assertNotNull(ysmJson);
        assertNotNull(s.content(ysmJson.hash, 3));
        assertNull(s.content(ysmJson.hash, 2));
        // a file the open model also has stays available to everyone
        for (FileRef f : s.byName("steve").entry.files) {
            assertNotNull(s.content(f.hash, 2), f.path);
        }
    }

    /** Without the native loader an encrypted .ysm is skipped with the reason; bundles still load. */
    @Test
    void skipsEncryptedYsmWithoutTheNativeLoader() throws IOException {
        Path models = tmp.resolve("models");
        Files.createDirectories(models);
        Files.write(
            models.resolve("v3.ysm"),
            new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'Y', 'S', 'G', 'P', 0, 3, 0, 0, 0 });
        ModelRepository.Snapshot s = new ModelRepository(
            new ModelLimits(),
            512L << 20,
            file -> { throw new IOException("native loader unavailable (UNSUPPORTED_PLATFORM: test)"); })
                .scan(models.toFile(), 1);
        assertEquals(0, s.models.size());
        assertTrue(logged(s, "Skipped v3.ysm", "native loader unavailable"));
    }
}
