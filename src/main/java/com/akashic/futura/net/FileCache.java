package com.akashic.futura.net;

import java.io.IOException;

/** Files by content hash (the client's disk cache of server models). */
public interface FileCache {

    /** Whether a file with this hash and size is stored (not verified: {@link #read} verifies). */
    boolean has(Sha256 hash, int size);

    /**
     * The file's bytes, verified against the hash; a stored file that does not match is deleted.
     *
     * @return null if missing or corrupt
     */
    byte[] read(Sha256 hash) throws IOException;

    /** Stores a file whose hash the caller has verified. */
    void store(Sha256 hash, byte[] data) throws IOException;
}
