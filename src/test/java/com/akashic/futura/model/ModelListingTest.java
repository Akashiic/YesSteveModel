package com.akashic.futura.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Listing a model's files: what its functions and sounds are found from. */
class ModelListingTest {

    @Test
    void filesUnderAFolderInUpstreamOrder() {
        List<String> files = Arrays.asList(
            "ysm.json",
            "functions/b@player_init.molang",
            "functions/sub/z.molang",
            "functions/a.molang",
            "functions/sub/a.MOLANG",
            "functions/readme.txt",
            "functionsx/no.molang");
        assertEquals(
            Arrays.asList(
                "functions/a.molang",
                "functions/b@player_init.molang",
                "functions/sub/a.MOLANG",
                "functions/sub/z.molang"),
            ModelSource.filesUnder(files, "functions/", ".molang"));
        assertEquals(
            Arrays.asList("functions/readme.txt"),
            ModelSource.filesUnder(Collections.singletonList("functions/readme.txt"), "\\functions", ""));
        assertEquals("sub/a@x", ModelLoader.relativeName("functions", "functions/sub/a@x.molang"));
        assertEquals("A Cup Of Tea", ModelLoader.relativeName("sounds/", "sounds/A Cup Of Tea.ogg"));
        assertEquals("noext", ModelLoader.relativeName("sounds", "sounds/noext"));
    }

    @Test
    void builtinModelListsItsFolder() throws IOException {
        List<String> files = BuiltinIndex.source("wine_fox/21_saint")
            .list();
        assertTrue(files.contains("ysm.json"));
        assertTrue(files.contains("functions/ArrowShoot@player_ctrl_use.molang"), files.toString());
        assertTrue(files.contains("sounds/atk1.ogg"));
    }

    @Test
    void modelInAJarListsItsEntries(@TempDir Path dir) throws IOException {
        File jar = dir.resolve("mod.jar")
            .toFile();
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            for (String name : new String[] { "assets/m/x/ysm.json", "assets/m/x/sounds/", "assets/m/x/sounds/a.ogg",
                "assets/m/x/functions/f@player_init.molang", "assets/m/y/ysm.json" }) {
                out.putNextEntry(new JarEntry(name));
                if (!name.endsWith("/")) {
                    out.write("{}".getBytes(StandardCharsets.UTF_8));
                }
                out.closeEntry();
            }
        }
        URL manifest = new URL("jar:" + jar.toURI() + "!/assets/m/x/ysm.json");
        List<String> files = ClasspathModelSource.list(manifest, "/assets/m/x/");
        Collections.sort(files);
        assertEquals(Arrays.asList("functions/f@player_init.molang", "sounds/a.ogg", "ysm.json"), files);
    }

    @Test
    void folderAndMemorySourcesList(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("functions/sub"));
        Files.write(dir.resolve("functions/sub/a.molang"), new byte[0]);
        Files.write(dir.resolve("ysm.json"), new byte[0]);
        List<String> files = new FolderModelSource(dir.toFile()).list();
        Collections.sort(files);
        assertEquals(Arrays.asList("functions/sub/a.molang", "ysm.json"), files);
        Map<String, byte[]> memory = new LinkedHashMap<>();
        memory.put("ysm.json", new byte[0]);
        memory.put("sounds\\b.ogg", new byte[0]);
        List<String> listed = new MemoryModelSource("m", memory).list();
        Collections.sort(listed);
        assertEquals(Arrays.asList("sounds/b.ogg", "ysm.json"), listed);
    }
}
