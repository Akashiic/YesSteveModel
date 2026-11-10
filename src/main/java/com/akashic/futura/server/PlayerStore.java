package com.akashic.futura.server;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * Each player's model, texture and grants, one JSON file per UUID ({@code config/futura3d/players/<uuid>.json}),
 * written to a temporary file and moved into place. No Minecraft classes.
 */
public final class PlayerStore {

    /** What is saved for one player. */
    public static final class Record {

        /** A built-in folder ({@code default}, {@code wine_fox/18_wedding}), or null. */
        public String builtin;
        /** A server model's name, or null. With both null the player uses the default model. */
        public String model;
        /** Texture stem, or null for the model's default texture. */
        public String texture;
        /** Set by an operator: the model is used even if restricted and not granted. */
        public boolean forced;
        /** Names of the restricted models the player may select. */
        public List<String> grants = new ArrayList<>();

        boolean hasGrant(String name) {
            for (String g : grants) {
                if (g.equalsIgnoreCase(name)) {
                    return true;
                }
            }
            return false;
        }

        void normalize() {
            if (grants == null) {
                grants = new ArrayList<>();
            }
            TreeSet<String> unique = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (String g : grants) {
                if (g != null && !g.isEmpty()) {
                    unique.add(g);
                }
            }
            grants = new ArrayList<>(unique);
        }

        boolean isEmpty() {
            return builtin == null && model == null && texture == null && grants.isEmpty();
        }
    }

    private final File dir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting()
        .create();
    private final Map<UUID, Record> cache = new HashMap<>();
    private final List<String> problems = new ArrayList<>();

    public PlayerStore(File dir) {
        this.dir = dir;
    }

    /** Problems met while reading (for the log); cleared on read. */
    public List<String> drainProblems() {
        List<String> out = new ArrayList<>(problems);
        problems.clear();
        return out;
    }

    private File file(UUID player) {
        return new File(dir, player + ".json");
    }

    /** The player's record (a new empty one if nothing is saved). The returned object is live: change it, then save. */
    public Record get(UUID player) {
        Record r = cache.get(player);
        if (r != null) {
            return r;
        }
        File f = file(player);
        if (f.isFile()) {
            try {
                r = gson.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), Record.class);
            } catch (IOException | JsonParseException e) {
                problems.add("Unreadable " + f + " (" + e.getMessage() + "): kept as " + f.getName() + ".bad");
                try {
                    Files.move(
                        f.toPath(),
                        new File(dir, f.getName() + ".bad").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // left in place; the next save replaces it
                }
            }
        }
        if (r == null) {
            r = new Record();
        }
        r.normalize();
        cache.put(player, r);
        return r;
    }

    /** Writes the player's record (deletes the file when the record is empty). */
    public void save(UUID player) throws IOException {
        Record r = get(player);
        r.normalize();
        File target = file(player);
        if (r.isEmpty()) {
            Files.deleteIfExists(target.toPath());
            return;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        File tmp = File.createTempFile(player.toString(), ".tmp", dir);
        try {
            Files.write(
                tmp.toPath(),
                gson.toJson(r)
                    .getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp.toPath());
        }
    }

    /** Drops a player's record from memory (on logout); it is read again from disk when needed. */
    /** Records held in memory (leak checks). */
    int cached() {
        return cache.size();
    }

    public void forget(UUID player) {
        cache.remove(player);
    }
}
