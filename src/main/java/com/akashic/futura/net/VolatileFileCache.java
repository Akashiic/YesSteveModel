package com.akashic.futura.net;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Files kept only in memory: the files of models the server marks {@link CatalogEntry#memoryOnly} (converted from
 * legacy {@code .ysm}), which never go to the disk cache. Read by the model loading threads while the network thread
 * stores, hence concurrent.
 */
public final class VolatileFileCache implements FileCache {

    private final Map<Sha256, byte[]> files = new ConcurrentHashMap<>();

    @Override
    public boolean has(Sha256 hash, int size) {
        byte[] data = files.get(hash);
        return data != null && data.length == size;
    }

    @Override
    public byte[] read(Sha256 hash) {
        return files.get(hash);
    }

    @Override
    public void store(Sha256 hash, byte[] data) {
        files.put(hash, data);
    }

    /** Drops every file whose hash is not kept. */
    public void retainAll(Set<Sha256> keep) {
        files.keySet()
            .retainAll(keep);
    }

    public int size() {
        return files.size();
    }

    public void clear() {
        files.clear();
    }
}
