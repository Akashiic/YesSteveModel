package com.akashic.futura.net;

/** One file of a model: its path inside the model, size and SHA-256. */
public final class FileRef {

    /** Clean relative path ({@code /} separators, as {@code ModelSource.clean} gives it). */
    public final String path;
    public final int size;
    public final Sha256 hash;

    public FileRef(String path, int size, Sha256 hash) {
        this.path = path;
        this.size = size;
        this.hash = hash;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FileRef)) {
            return false;
        }
        FileRef f = (FileRef) o;
        return path.equals(f.path) && size == f.size && hash.equals(f.hash);
    }

    @Override
    public int hashCode() {
        return path.hashCode() * 31 + hash.hashCode();
    }

    @Override
    public String toString() {
        return path + "," + size + "," + hash.hex();
    }
}
