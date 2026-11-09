package com.akashic.futura.model;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A model held in memory, path to content (files received from a server, or an unpacked zip). */
public final class MemoryModelSource implements ModelSource {

    private final String name;
    private final Map<String, byte[]> files;

    /**
     * @param files relative paths ({@code /} or {@code \} separators, cleaned with {@link ModelSource#clean}) to their
     *              content; the arrays are not copied
     * @throws IOException if a path leaves the model
     */
    public MemoryModelSource(String name, Map<String, byte[]> files) throws IOException {
        this.name = name;
        Map<String, byte[]> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            if (file.getValue() == null) {
                continue;
            }
            cleaned.put(ModelSource.clean(file.getKey()), file.getValue());
        }
        this.files = Collections.unmodifiableMap(cleaned);
    }

    /** The files by cleaned path. */
    public Map<String, byte[]> files() {
        return files;
    }

    /** Total size of the files in bytes. */
    public long size() {
        long total = 0;
        for (byte[] content : files.values()) {
            total += content.length;
        }
        return total;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public InputStream open(String path) throws IOException {
        byte[] content = files.get(ModelSource.clean(path));
        return content == null ? null : new ByteArrayInputStream(content);
    }
}
