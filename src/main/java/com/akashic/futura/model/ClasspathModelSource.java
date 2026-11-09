package com.akashic.futura.model;

import java.io.IOException;
import java.io.InputStream;

/** A model bundled in the mod, e.g. {@code /assets/futura3d/builtin/default/}. */
public final class ClasspathModelSource implements ModelSource {

    private final String base;

    /** @param base absolute resource folder, ending with {@code /} */
    public ClasspathModelSource(String base) {
        this.base = base.endsWith("/") ? base : base + "/";
    }

    /** The resource folder, ending with {@code /}. */
    public String base() {
        return base;
    }

    @Override
    public String name() {
        return base;
    }

    @Override
    public InputStream open(String path) throws IOException {
        return ClasspathModelSource.class.getResourceAsStream(base + ModelSource.clean(path));
    }
}
