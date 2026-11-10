package com.akashic.futura.model;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Where a model's files come from: a folder, a zip, the mod's own resources, or bytes received from a server. */
public interface ModelSource {

    /** A short name for logs and errors. */
    String name();

    /**
     * @param path relative path inside the model, with {@code /} separators (as written in {@code ysm.json})
     * @return the file's content, or {@code null} if there is no such file
     */
    InputStream open(String path) throws IOException;

    /**
     * Every file of the model, as clean relative paths with {@code /} separators: what the folders a manifest names
     * without listing their files ({@code functions/}, {@code sounds/}) are read from.
     *
     * @return the paths, or an empty list when the source cannot list itself (its functions and sounds are then not
     *         found)
     */
    default List<String> list() throws IOException {
        return Collections.emptyList();
    }

    /**
     * The files of {@link #list} under a folder, recursively, in upstream's order ({@code RawModelSource.collectFiles}:
     * each folder's files sorted by name, then its sub-folders sorted by name).
     *
     * @param folder a relative folder ({@code functions}, {@code sounds}); blank for the whole model
     * @param ext    a lower-case extension with its dot ({@code .molang}), or empty for every file
     * @return the paths relative to the model
     */
    static List<String> filesUnder(List<String> files, String folder, String ext) {
        String root = folder == null ? "" : folder.replace('\\', '/');
        while (root.startsWith("/")) {
            root = root.substring(1);
        }
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        String prefix = root.isEmpty() ? "" : root + "/";
        List<String> found = new ArrayList<>();
        for (String file : files) {
            if (file.startsWith(prefix) && file.length() > prefix.length()
                && file.toLowerCase(java.util.Locale.ROOT)
                    .endsWith(ext)) {
                found.add(file);
            }
        }
        found.sort((a, b) -> compareUpstream(a.substring(prefix.length()), b.substring(prefix.length())));
        return found;
    }

    /** Files of a folder before its sub-folders, each group by name. */
    static int compareUpstream(String a, String b) {
        int sa = a.indexOf('/');
        int sb = b.indexOf('/');
        if (sa < 0 && sb < 0) {
            return a.compareTo(b);
        }
        if (sa < 0) {
            return -1;
        }
        if (sb < 0) {
            return 1;
        }
        int c = a.substring(0, sa)
            .compareTo(b.substring(0, sb));
        return c != 0 ? c : compareUpstream(a.substring(sa + 1), b.substring(sb + 1));
    }

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
