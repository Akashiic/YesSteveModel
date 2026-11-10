package com.akashic.futura.net;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import com.akashic.futura.model.ModelSource;

/**
 * A server model's files, read from the client's {@link FileCache} by the hashes in its catalog entry. Every read is
 * verified against the hash; a file missing from the cache or corrupt is an {@link IOException}.
 */
public final class CachedModelSource implements ModelSource {

    private final String name;
    private final FileCache cache;
    private final Map<String, FileRef> files = new HashMap<>();

    public CachedModelSource(CatalogEntry entry, FileCache cache) {
        this.name = "server:" + entry.name
            + " ("
            + entry.id.hex()
                .substring(0, 12)
            + ")";
        this.cache = cache;
        for (FileRef f : entry.files) {
            files.put(f.path, f);
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public InputStream open(String path) throws IOException {
        FileRef ref = files.get(ModelSource.clean(path));
        if (ref == null) {
            return null;
        }
        byte[] data = cache.read(ref.hash);
        if (data == null) {
            throw new IOException(name + ": " + ref.path + " is missing from the cache or corrupt");
        }
        return new ByteArrayInputStream(data);
    }

    /** The catalog's file list: where the model's {@code functions/} and {@code sounds/} are found. */
    @Override
    public java.util.List<String> list() {
        java.util.List<String> paths = new java.util.ArrayList<>(files.keySet());
        java.util.Collections.sort(paths);
        return paths;
    }
}
