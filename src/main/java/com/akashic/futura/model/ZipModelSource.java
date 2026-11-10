package com.akashic.futura.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A model packed in a zip, read into memory once. The sizes are counted on the inflated bytes, not taken from the
 * entry headers, so a zip bomb stops at the caps; an entry whose path leaves the model ({@code ..}, an absolute path)
 * rejects the whole zip. A zip whose files are all inside one top folder (zipping the folder instead of its content)
 * is read from that folder.
 */
public final class ZipModelSource implements ModelSource {

    /** Default cap of one inflated entry. */
    public static final long MAX_ENTRY = 32L << 20;
    /** Default cap of all inflated entries together. */
    public static final long MAX_TOTAL = 128L << 20;
    /** Default cap of the number of entries. */
    public static final int MAX_ENTRIES = 4096;

    private final MemoryModelSource files;

    public ZipModelSource(File zip) throws IOException {
        this(zip.getName(), zip, MAX_ENTRY, MAX_TOTAL, MAX_ENTRIES);
    }

    public ZipModelSource(String name, File zip, long maxEntry, long maxTotal, int maxEntries) throws IOException {
        try (InputStream in = new FileInputStream(zip)) {
            this.files = read(name, in, maxEntry, maxTotal, maxEntries);
        }
    }

    public ZipModelSource(String name, byte[] zip) throws IOException {
        this(name, zip, MAX_ENTRY, MAX_TOTAL, MAX_ENTRIES);
    }

    public ZipModelSource(String name, byte[] zip, long maxEntry, long maxTotal, int maxEntries) throws IOException {
        this.files = read(name, new ByteArrayInputStream(zip), maxEntry, maxTotal, maxEntries);
    }

    private static MemoryModelSource read(String name, InputStream raw, long maxEntry, long maxTotal, int maxEntries)
        throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String path = ModelSource.clean(entry.getName());
                if (entry.isDirectory() || path.isEmpty() || path.endsWith("/")) {
                    continue;
                }
                if (entries.size() >= maxEntries) {
                    throw new IOException(name + ": more than " + maxEntries + " entries");
                }
                if (entries.containsKey(path)) {
                    throw new IOException(name + ": duplicate entry " + path);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                long size = 0;
                int n;
                while ((n = zip.read(buffer)) > 0) {
                    size += n;
                    total += n;
                    if (size > maxEntry) {
                        throw new IOException(name + ": " + path + " is larger than " + maxEntry + " bytes");
                    }
                    if (total > maxTotal) {
                        throw new IOException(name + ": larger than " + maxTotal + " bytes");
                    }
                    out.write(buffer, 0, n);
                }
                entries.put(path, out.toByteArray());
            }
        }
        return new MemoryModelSource(name, stripTopFolder(entries));
    }

    /** {@code folder/ysm.json}, {@code folder/...} and nothing else: the files relative to {@code folder}. */
    static Map<String, byte[]> stripTopFolder(Map<String, byte[]> entries) {
        if (entries.containsKey("ysm.json") || entries.isEmpty()) {
            return entries;
        }
        String top = null;
        for (String path : entries.keySet()) {
            int slash = path.indexOf('/');
            if (slash <= 0) {
                return entries;
            }
            String first = path.substring(0, slash + 1);
            if (top == null) {
                top = first;
            } else if (!top.equals(first)) {
                return entries;
            }
        }
        if (!entries.containsKey(top + "ysm.json")) {
            return entries;
        }
        Map<String, byte[]> stripped = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            stripped.put(
                e.getKey()
                    .substring(top.length()),
                e.getValue());
        }
        return stripped;
    }

    /** The inflated files by cleaned path. */
    public Map<String, byte[]> files() {
        return files.files();
    }

    @Override
    public java.util.List<String> list() {
        return new java.util.ArrayList<>(files().keySet());
    }

    @Override
    public String name() {
        return files.name();
    }

    @Override
    public InputStream open(String path) throws IOException {
        return files.open(path);
    }
}
