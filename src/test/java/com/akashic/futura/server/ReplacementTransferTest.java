package com.akashic.futura.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.MemoryModelSource;
import com.akashic.futura.model.ModelLoader;
import com.akashic.futura.model.ReplacementTarget;
import com.akashic.futura.net.FileRef;
import com.akashic.futura.net.ModelLimits;

/** Projectile and vehicle files are files of the model: the server accepts and sends them like any other. */
class ReplacementTransferTest {

    @TempDir
    Path tmp;

    @Test
    void replacementFilesAreTransferred() throws IOException {
        Path models = tmp.resolve("models");
        ServerTestSupport.copyFolder("default", models.resolve("withcarts"));
        ModelRepository.Snapshot s = new ModelRepository(new ModelLimits(), 512L << 20).scan(models.toFile(), 1);
        ServerModel model = s.byName("withcarts");
        assertNotNull(model, String.valueOf(s.log));
        Set<String> paths = new HashSet<>();
        Map<String, byte[]> files = new HashMap<>();
        for (FileRef f : model.entry.files) {
            paths.add(f.path);
            files.put(f.path, s.content(f.hash));
        }
        for (String path : new String[] { "models/arrow.json", "animations/arrow.animation.json", "textures/arrow.png",
            "models/boat.json", "animations/boat.animation.json", "textures/boat.png", "models/minecart.json",
            "textures/minecart.png", "models/fishing_bobber.json" }) {
            assertTrue(paths.contains(path), path + " in " + paths);
        }
        // what a client rebuilds from the transferred files has the same replacements
        LoadedModel received = ModelLoader.load(new MemoryModelSource("received", files));
        assertTrue(received.replacementErrors.isEmpty(), received.replacementErrors.toString());
        assertEquals(4, received.projectiles.size());
        assertEquals(3, received.vehicles.size());
        ReplacementTarget boat = received.vehicle("minecraft:boat");
        assertNotNull(boat);
        assertTrue(files.containsKey(boat.texture));
    }
}
