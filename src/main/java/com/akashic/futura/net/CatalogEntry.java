package com.akashic.futura.net;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A server model as the catalog describes it: its id, what the picker shows, its files, and whether the receiving
 * player may select it.
 */
public final class CatalogEntry {

    /** The model id: {@link #computeId} of {@link #files}. */
    public final Sha256 id;
    /** Display name ({@code metadata.name}, or the folder name). */
    public final String name;
    /** Description ({@code metadata.tips}). */
    public final String tips;
    /** Licence type ({@code metadata.license.type}). */
    public final String license;
    /** Author names ({@code metadata.authors[].name}). */
    public final List<String> authors;
    /** In the restricted folder ({@code models/auth}). */
    public final boolean restricted;
    /** The receiving player may see the model on others but not select it. */
    public final boolean locked;
    /**
     * The files may be kept only in memory, never in the client's disk cache: a model the server converted from a
     * legacy {@code .ysm} (protocol 3; such models are not offered to older clients).
     */
    public final boolean memoryOnly;
    /** Files, sorted by path. */
    public final List<FileRef> files;

    public CatalogEntry(Sha256 id, String name, String tips, String license, List<String> authors, boolean restricted,
        boolean locked, List<FileRef> files) {
        this(id, name, tips, license, authors, restricted, locked, false, files);
    }

    public CatalogEntry(Sha256 id, String name, String tips, String license, List<String> authors, boolean restricted,
        boolean locked, boolean memoryOnly, List<FileRef> files) {
        this.id = id;
        this.name = name;
        this.tips = tips;
        this.license = license;
        this.authors = Collections.unmodifiableList(new ArrayList<>(authors));
        this.restricted = restricted;
        this.locked = locked;
        this.memoryOnly = memoryOnly;
        List<FileRef> sorted = new ArrayList<>(files);
        sorted.sort(BY_PATH);
        this.files = Collections.unmodifiableList(sorted);
    }

    /** The same entry with another locked flag (the catalog is per player). */
    public CatalogEntry withLocked(boolean locked) {
        return locked == this.locked ? this
            : new CatalogEntry(id, name, tips, license, authors, restricted, locked, memoryOnly, files);
    }

    /** {@code server:<id>}, the key of this model in the client's registry and in player states. */
    public String key() {
        return Protocol.SERVER + id.hex();
    }

    public long totalSize() {
        long total = 0;
        for (FileRef f : files) {
            total += f.size;
        }
        return total;
    }

    public static final Comparator<FileRef> BY_PATH = Comparator.comparing(f -> f.path);

    /**
     * The model id: SHA-256 over the canonical file list, each file in path order (by UTF-16 code units, as
     * {@link String#compareTo}) as its UTF-8 path, a zero byte, its size (4 bytes, big endian) and its 32-byte hash.
     * Two servers with the same files give the same id, and a client can check an id against the file list.
     */
    public static Sha256 computeId(List<FileRef> files) {
        List<FileRef> sorted = new ArrayList<>(files);
        sorted.sort(BY_PATH);
        MessageDigest md = Sha256.digest();
        for (FileRef f : sorted) {
            md.update(f.path.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(
                new byte[] { (byte) (f.size >>> 24), (byte) (f.size >>> 16), (byte) (f.size >>> 8), (byte) f.size });
            md.update(f.hash.bytes());
        }
        return Sha256.of(md);
    }

    @Override
    public String toString() {
        return name + " ("
            + id.hex()
                .substring(0, 12)
            + ", "
            + files.size()
            + " files, "
            + totalSize()
            + " bytes)";
    }
}
