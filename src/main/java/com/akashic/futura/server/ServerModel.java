package com.akashic.futura.server;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.akashic.futura.net.CatalogEntry;
import com.akashic.futura.net.Sha256;

/** A model the server loaded and validated, with its files in memory. */
public final class ServerModel {

    /** The folder or zip name without {@code .zip}: what commands and the saved selections use. */
    public final String name;
    /** In {@code models/auth}: selecting it needs a grant. */
    public final boolean restricted;
    /** The catalog entry, unlocked (the per-player catalog sets the flag). */
    public final CatalogEntry entry;
    /** Texture stems the model has (what a selection's texture must be one of). */
    public final List<String> textures;
    /** File contents by hash. */
    final Map<Sha256, byte[]> content;

    ServerModel(String name, boolean restricted, CatalogEntry entry, List<String> textures,
        Map<Sha256, byte[]> content) {
        this.name = name;
        this.restricted = restricted;
        this.entry = entry;
        this.textures = Collections.unmodifiableList(textures);
        this.content = Collections.unmodifiableMap(content);
    }

    public Sha256 id() {
        return entry.id;
    }

    public String key() {
        return entry.key();
    }

    @Override
    public String toString() {
        return name + (restricted ? " (restricted)" : "") + ": " + entry;
    }
}
