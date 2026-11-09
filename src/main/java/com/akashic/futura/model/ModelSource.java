package com.akashic.futura.model;

import java.io.IOException;
import java.io.InputStream;

/** Where a model's files come from: a folder, a zip, the mod's own resources, or bytes received from a server. */
public interface ModelSource {

    /** A short name for logs and errors. */
    String name();

    /**
     * @param path relative path inside the model, with {@code /} separators (as written in {@code ysm.json})
     * @return the file's content, or {@code null} if there is no such file
     */
    InputStream open(String path) throws IOException;

    /** Normalises a path from a manifest: backslashes, a leading {@code ./}, and no escaping the model. */
    static String clean(String path) throws IOException {
        String p = path.replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.startsWith("/") || p.equals("..") || p.startsWith("../") || p.contains("/../") || p.endsWith("/..")) {
            throw new IOException("Path escapes the model: " + path);
        }
        return p;
    }
}
