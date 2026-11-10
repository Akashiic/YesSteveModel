package com.akashic.futura.model;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A model unpacked in a directory (the authoring layout: {@code ysm.json} at the top). A path that resolves outside
 * the directory, through {@code ..} or a symbolic link, is refused.
 */
public final class FolderModelSource implements ModelSource {

    private final File dir;
    private final String canonical;

    public FolderModelSource(File dir) throws IOException {
        if (!dir.isDirectory()) {
            throw new IOException("Not a directory: " + dir);
        }
        this.dir = dir;
        this.canonical = dir.getCanonicalPath() + File.separator;
    }

    public File dir() {
        return dir;
    }

    @Override
    public String name() {
        return dir.getName();
    }

    @Override
    public InputStream open(String path) throws IOException {
        File file = new File(dir, ModelSource.clean(path));
        if (!file.getCanonicalPath()
            .startsWith(canonical)) {
            throw new IOException("Path escapes the model: " + path);
        }
        return file.isFile() ? new FileInputStream(file) : null;
    }

    /** Regular files under the folder, symbolic links not followed, at most 8192 and 16 levels deep. */
    @Override
    public List<String> list() throws IOException {
        List<String> files = new ArrayList<>();
        walk(dir, "", 0, files);
        return files;
    }

    private static void walk(File folder, String prefix, int depth, List<String> out) throws IOException {
        File[] children = folder.listFiles();
        if (children == null || depth > 16) {
            return;
        }
        for (File child : children) {
            if (out.size() >= 8192 || java.nio.file.Files.isSymbolicLink(child.toPath())) {
                continue;
            }
            if (child.isDirectory()) {
                walk(child, prefix + child.getName() + "/", depth + 1, out);
            } else if (child.isFile()) {
                out.add(prefix + child.getName());
            }
        }
    }
}
