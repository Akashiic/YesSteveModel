package com.akashic.futura.net;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * {@link FileCache} in a folder ({@code <mcDataDir>/futura3d/cache}): one file per hash, named by its 64 hex digits,
 * written to a temporary file and moved into place, verified on every read.
 */
public final class DiskFileCache implements FileCache {

    private final File dir;

    public DiskFileCache(File dir) {
        this.dir = dir;
    }

    public File dir() {
        return dir;
    }

    private File file(Sha256 hash) {
        return new File(dir, hash.hex());
    }

    @Override
    public boolean has(Sha256 hash, int size) {
        File f = file(hash);
        return f.isFile() && f.length() == size;
    }

    @Override
    public byte[] read(Sha256 hash) throws IOException {
        File f = file(hash);
        if (!f.isFile() || f.length() > Protocol.MAX_FILE_BYTES) {
            return null;
        }
        byte[] data = Files.readAllBytes(f.toPath());
        if (!Sha256.of(data)
            .equals(hash)) {
            Files.deleteIfExists(f.toPath());
            return null;
        }
        return data;
    }

    @Override
    public void store(Sha256 hash, byte[] data) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        File target = file(hash);
        File tmp = File.createTempFile(
            hash.hex()
                .substring(0, 16),
            ".part",
            dir);
        try {
            Files.write(tmp.toPath(), data);
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
}
