package com.akashic.futura.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Folder, zip and memory sources: reading, missing files, path traversal, and the zip caps. */
class ModelSourcesTest {

    private static final Path DEFAULT = Paths.get("src/main/resources/assets/futura3d/builtin/default");

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String read(ModelSource source, String path) throws IOException {
        try (InputStream in = source.open(path)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, byte[]> tree(Path dir) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(p)) {
                    files.put(
                        dir.relativize(p)
                            .toString()
                            .replace(File.separatorChar, '/'),
                        Files.readAllBytes(p));
                }
            }
        }
        return files;
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    // ---- Folder ----

    @Test
    void folderReadsFilesAndAnswersNullForMissingOnes(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("textures"));
        Files.write(dir.resolve("ysm.json"), bytes("{}"));
        Files.write(dir.resolve("textures/a.png"), bytes("png"));
        FolderModelSource source = new FolderModelSource(dir.toFile());
        assertEquals("{}", read(source, "ysm.json"));
        assertEquals("png", read(source, "./textures\\a.png"));
        assertNull(read(source, "textures/b.png"));
        assertNull(read(source, "textures"), "a directory is not a file");
    }

    @Test
    void folderRefusesPathsOutside(@TempDir Path dir) throws IOException {
        Path model = Files.createDirectories(dir.resolve("model"));
        Files.write(dir.resolve("secret.txt"), bytes("no"));
        FolderModelSource source = new FolderModelSource(model.toFile());
        assertThrows(IOException.class, () -> source.open("../secret.txt"));
        assertThrows(IOException.class, () -> source.open("/etc/passwd"));
        Path link = model.resolve("link");
        try {
            Files.createSymbolicLink(link, dir.resolve("secret.txt"));
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "no symbolic links here");
        }
        assertThrows(IOException.class, () -> source.open("link"), "a link out of the folder");
    }

    @Test
    void folderNeedsADirectory(@TempDir Path dir) {
        assertThrows(
            IOException.class,
            () -> new FolderModelSource(
                dir.resolve("none")
                    .toFile()));
    }

    @Test
    void folderLoadsABuiltinModel() throws IOException {
        LoadedModel model = ModelLoader.load(new FolderModelSource(DEFAULT.toFile()));
        assertEquals("Default", model.name());
        assertEquals("textures/default.png", model.defaultTexture());
    }

    // ---- Memory ----

    @Test
    void memoryCleansPathsAndReadsContent() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("./ysm.json", bytes("{}"));
        files.put("textures\\a.png", bytes("png"));
        MemoryModelSource source = new MemoryModelSource("m", files);
        assertEquals("{}", read(source, "ysm.json"));
        assertEquals("png", read(source, "textures/a.png"));
        assertNull(read(source, "nothing"));
        assertEquals(5, source.size());
        assertTrue(
            source.files()
                .containsKey("textures/a.png"));
    }

    @Test
    void memoryRefusesPathsOutside() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("../x", bytes("x"));
        assertThrows(IOException.class, () -> new MemoryModelSource("m", files));
    }

    @Test
    void memoryLoadsACopyOfABuiltinModel() throws IOException {
        LoadedModel model = ModelLoader.load(new MemoryModelSource("copy", tree(DEFAULT)));
        assertEquals("Default", model.name());
        assertEquals(2, model.textures.size());
    }

    // ---- Zip ----

    @Test
    void zipLoadsAModelZippedWithOrWithoutItsFolder() throws IOException {
        Map<String, byte[]> flat = tree(DEFAULT);
        LoadedModel model = ModelLoader.load(new ZipModelSource("flat.zip", zip(flat)));
        assertEquals("Default", model.name());
        Map<String, byte[]> nested = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : flat.entrySet()) {
            nested.put("default/" + e.getKey(), e.getValue());
        }
        ZipModelSource source = new ZipModelSource("nested.zip", zip(nested));
        assertNotNull(source.open("ysm.json"), "the top folder is stripped");
        assertEquals(
            "Default",
            ModelLoader.load(source)
                .name());
    }

    @Test
    void zipKeepsTwoTopFoldersAsTheyAre() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a/ysm.json", bytes("{}"));
        entries.put("b/x.png", bytes("x"));
        ZipModelSource source = new ZipModelSource("two.zip", zip(entries));
        assertNull(source.open("ysm.json"));
        assertNotNull(source.open("a/ysm.json"));
    }

    @Test
    void zipRejectsPathTraversal() throws IOException {
        for (String bad : new String[] { "../evil.png", "a/../../evil.png", "/abs.png", "..\\evil.png" }) {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("ysm.json", bytes("{}"));
            entries.put(bad, bytes("x"));
            byte[] zip = zip(entries);
            assertThrows(IOException.class, () -> new ZipModelSource("bad.zip", zip), bad);
        }
    }

    @Test
    void zipStopsAtTheCapsCountingInflatedBytes() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        // 1 MiB of zeros deflates to about a kilobyte: the header size is not what is checked.
        entries.put("ysm.json", new byte[1 << 20]);
        byte[] zip = zip(entries);
        assertTrue(zip.length < 16 << 10);
        assertThrows(IOException.class, () -> new ZipModelSource("big.zip", zip, 1000, 1 << 30, 10));
        assertThrows(IOException.class, () -> new ZipModelSource("big.zip", zip, 1 << 30, 1000, 10));
        ZipModelSource fits = new ZipModelSource("ok.zip", zip, 1 << 20, 1 << 20, 10);
        assertEquals(
            1 << 20,
            fits.files()
                .get("ysm.json").length);

        Map<String, byte[]> many = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            many.put("f" + i, bytes("x"));
        }
        byte[] manyZip = zip(many);
        assertThrows(IOException.class, () -> new ZipModelSource("many.zip", manyZip, 100, 100, 4));
    }

    @Test
    void zipReadsAFileAndItsEntries(@TempDir Path dir) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("ysm.json", bytes("{\"a\":1}"));
        entries.put("textures/", new byte[0]);
        Path file = dir.resolve("m.zip");
        Files.write(file, zip(entries));
        ZipModelSource source = new ZipModelSource(file.toFile());
        assertEquals("m.zip", source.name());
        assertEquals("{\"a\":1}", read(source, "ysm.json"));
        assertArrayEquals(
            new String[] { "ysm.json" },
            source.files()
                .keySet()
                .toArray(new String[0]));
    }
}
