package com.akashic.futura.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * The local player's model choice kept between game sessions, in a small properties file ({@code model} and
 * {@code texture}). No Minecraft classes. Reading a missing or broken file gives no choice.
 */
final class LocalChoiceStore {

    private final File file;

    LocalChoiceStore(File file) {
        this.file = file;
    }

    File file() {
        return file;
    }

    /** @return {@code {key, texture}} (either may be null), or null if nothing is saved */
    String[] load() {
        if (!file.isFile()) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        String key = blankToNull(p.getProperty("model"));
        String texture = blankToNull(p.getProperty("texture"));
        return key == null && texture == null ? null : new String[] { key, texture };
    }

    /** Saves a choice; both null deletes the file (the default model). */
    void save(String key, String texture) throws IOException {
        if (key == null && texture == null) {
            if (file.isFile() && !file.delete()) {
                throw new IOException("Could not delete " + file);
            }
            return;
        }
        File dir = file.getAbsoluteFile()
            .getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir);
        }
        Properties p = new Properties();
        if (key != null) {
            p.setProperty("model", key);
        }
        if (texture != null) {
            p.setProperty("texture", texture);
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            p.store(writer, "3D Futura Engine: the model chosen in the picker when no server handles it");
            writer.flush();
        }
        if (file.exists() && !file.delete() || !tmp.renameTo(file)) {
            throw new IOException("Could not write " + file);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.trim()
            .isEmpty() ? null : s.trim();
    }
}
