package com.akashic.futura.model;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

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

    /**
     * Lists the folder through the URL of its {@code ysm.json}: a directory on disk (development, tests) or the
     * entries of the jar it is in (the release jar). Anything else lists nothing.
     */
    @Override
    public List<String> list() throws IOException {
        return list(ClasspathModelSource.class.getResource(base + "ysm.json"), base);
    }

    /**
     * @param manifest the URL of the model's {@code ysm.json}, or null
     * @param base     the model's resource folder, starting and ending with {@code /}
     */
    static List<String> list(URL manifest, String base) throws IOException {
        List<String> files = new ArrayList<>();
        if (manifest == null) {
            return files;
        }
        if ("file".equals(manifest.getProtocol())) {
            try {
                File dir = new File(manifest.toURI()).getParentFile();
                return new FolderModelSource(dir).list();
            } catch (URISyntaxException | IllegalArgumentException e) {
                return files;
            }
        }
        if ("jar".equals(manifest.getProtocol())) {
            URLConnection connection = manifest.openConnection();
            if (connection instanceof JarURLConnection) {
                JarURLConnection jar = (JarURLConnection) connection;
                jar.setUseCaches(true);
                String prefix = base.substring(1);
                JarFile file = jar.getJarFile();
                for (Enumeration<JarEntry> e = file.entries(); e.hasMoreElements();) {
                    JarEntry entry = e.nextElement();
                    String name = entry.getName();
                    if (!entry.isDirectory() && name.startsWith(prefix) && name.length() > prefix.length()) {
                        files.add(name.substring(prefix.length()));
                    }
                }
            }
        }
        return files;
    }
}
