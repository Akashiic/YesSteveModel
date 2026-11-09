package com.akashic.futura.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerStoreTest {

    @TempDir
    Path tmp;

    @Test
    void savesAndRestores() throws IOException {
        UUID p = UUID.randomUUID();
        PlayerStore store = new PlayerStore(tmp.toFile());
        PlayerStore.Record r = store.get(p);
        r.model = "alex";
        r.texture = "gsl";
        r.grants.add("steve");
        r.grants.add("STEVE");
        store.save(p);
        File[] files = tmp.toFile()
            .listFiles();
        assertEquals(1, files.length, "no temporary file left");
        assertEquals(p + ".json", files[0].getName());

        PlayerStore.Record back = new PlayerStore(tmp.toFile()).get(p);
        assertEquals("alex", back.model);
        assertEquals("gsl", back.texture);
        assertNull(back.builtin);
        assertEquals(Arrays.asList("steve"), back.grants);
        assertTrue(back.hasGrant("Steve"));

        // an empty record removes the file
        back.model = null;
        back.texture = null;
        back.grants.clear();
        PlayerStore again = new PlayerStore(tmp.toFile());
        again.get(p).model = null;
        again.get(p).texture = null;
        again.get(p).grants.clear();
        again.save(p);
        assertFalse(new File(tmp.toFile(), p + ".json").exists());
    }

    @Test
    void setsAsideUnreadableFiles() throws IOException {
        UUID p = UUID.randomUUID();
        Files.write(tmp.resolve(p + ".json"), "{ not json".getBytes());
        PlayerStore store = new PlayerStore(tmp.toFile());
        PlayerStore.Record r = store.get(p);
        assertNull(r.model);
        assertEquals(
            1,
            store.drainProblems()
                .size());
        assertTrue(Files.exists(tmp.resolve(p + ".json.bad")));
    }
}
