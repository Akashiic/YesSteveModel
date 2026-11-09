package com.akashic.futura.net;

import java.util.HashMap;
import java.util.Map;

/** A {@link FileCache} in memory, for tests. */
public final class MemoryFileCache implements FileCache {

    public final Map<Sha256, byte[]> files = new HashMap<>();

    @Override
    public boolean has(Sha256 hash, int size) {
        byte[] f = files.get(hash);
        return f != null && f.length == size;
    }

    @Override
    public byte[] read(Sha256 hash) {
        byte[] f = files.get(hash);
        return f != null && Sha256.of(f)
            .equals(hash) ? f : null;
    }

    @Override
    public void store(Sha256 hash, byte[] data) {
        files.put(hash, data.clone());
    }
}
