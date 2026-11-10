package com.akashic.futura.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.akashic.futura.format.pojo.manifest.metadata.ModelAuthor;
import com.akashic.futura.format.pojo.manifest.metadata.ModelMetadata;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelSource;
import com.akashic.futura.model.legacy.LegacyLayout;
import com.akashic.futura.model.legacy.LegacyYsm;
import com.akashic.futura.natives.LegacyNative;
import com.akashic.futura.net.CatalogEntry;
import com.akashic.futura.net.FileRef;
import com.akashic.futura.net.Message;
import com.akashic.futura.net.MessageCodec;
import com.akashic.futura.net.ModelLimits;
import com.akashic.futura.net.Protocol;
import com.akashic.futura.net.Sha256;

/**
 * Reads the server's models: {@code models/} (anyone may select them) and {@code models/auth/} (selecting needs a
 * grant), each a folder or a {@code .zip} in the open authoring format ({@code ysm.json} spec 2, or the 1.x layout),
 * or a legacy {@code .ysm} file. Every model is read into memory within {@link ModelLimits}, then parsed with the real
 * loader; anything else is skipped with a line in {@link Snapshot#log}. A {@code .ysm} is converted in memory (v1/v2
 * bundles in Java, encrypted v3 through the native loader) and marked {@link CatalogEntry#memoryOnly}: its files stay
 * in memory on the server and on clients. No Minecraft classes.
 */
public final class ModelRepository {

    /** The restricted subfolder. */
    public static final String AUTH = "auth";
    /** Zip entries looked at (kept or not) before a zip is refused. */
    public static final int MAX_ZIP_ENTRIES = 4096;
    /** Folder depth walked inside a model folder. */
    public static final int MAX_DEPTH = 16;

    /** Upstream's {@code kLegacySourceByteLimit}: the largest {@code .ysm} file read. */
    public static final int MAX_YSM_BYTES = 69_206_016;

    private final ModelLimits limits;
    private final long maxTotalBytes;
    private final LegacyYsm.V3Decoder v3;

    public ModelRepository(ModelLimits limits, long maxTotalBytes) {
        this(limits, maxTotalBytes, LegacyNative::convert);
    }

    /** @param v3 decodes encrypted v3 {@code .ysm} files (the native loader; a stub in tests) */
    public ModelRepository(ModelLimits limits, long maxTotalBytes, LegacyYsm.V3Decoder v3) {
        this.limits = limits;
        this.maxTotalBytes = maxTotalBytes;
        this.v3 = v3;
    }

    /** The models of one scan. Immutable. */
    public static final class Snapshot {

        public final int revision;
        /** By name. */
        public final List<ServerModel> models;
        /** What was loaded and skipped, for the log. */
        public final List<String> log;
        private final Map<String, ServerModel> byName = new HashMap<>();
        private final Map<Sha256, ServerModel> byId = new HashMap<>();
        private final Map<Sha256, byte[]> content = new HashMap<>();
        /** Hashes no model offered to every client has: only memory-only (legacy .ysm) models use them. */
        private final java.util.Set<Sha256> memoryOnly = new java.util.HashSet<>();
        private final long totalBytes;

        Snapshot(int revision, List<ServerModel> models, List<String> log) {
            this.revision = revision;
            List<ServerModel> sorted = new ArrayList<>(models);
            sorted.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            this.models = Collections.unmodifiableList(sorted);
            this.log = Collections.unmodifiableList(new ArrayList<>(log));
            long total = 0;
            for (ServerModel m : sorted) {
                byName.put(m.name.toLowerCase(Locale.ROOT), m);
                byId.put(m.id(), m);
                for (Map.Entry<Sha256, byte[]> f : m.content.entrySet()) {
                    if (content.put(f.getKey(), f.getValue()) == null) {
                        total += f.getValue().length;
                    }
                }
            }
            this.totalBytes = total;
            java.util.Set<Sha256> shareable = new java.util.HashSet<>();
            for (ServerModel m : sorted) {
                (m.entry.memoryOnly ? memoryOnly : shareable).addAll(m.content.keySet());
            }
            memoryOnly.removeAll(shareable);
        }

        public static Snapshot empty() {
            return new Snapshot(0, Collections.<ServerModel>emptyList(), Collections.<String>emptyList());
        }

        /** By name, ignoring case. */
        public ServerModel byName(String name) {
            return name == null ? null : byName.get(name.toLowerCase(Locale.ROOT));
        }

        public ServerModel byId(Sha256 id) {
            return byId.get(id);
        }

        /** A file's bytes, or null if no model has it. */
        public byte[] content(Sha256 hash) {
            return content.get(hash);
        }

        /**
         * A file's bytes for a client speaking {@code version}: clients before {@link Protocol#MEMORY_ONLY_VERSION}
         * do not get the files only memory-only models use (they would write them to their disk cache).
         */
        public byte[] content(Sha256 hash, int version) {
            return !Protocol.hasMemoryOnly(version) && memoryOnly.contains(hash) ? null : content.get(hash);
        }

        /** The models offered to a client speaking {@code version}. */
        public List<ServerModel> offered(int version) {
            if (Protocol.hasMemoryOnly(version)) {
                return models;
            }
            List<ServerModel> list = new ArrayList<>();
            for (ServerModel m : models) {
                if (!m.entry.memoryOnly) {
                    list.add(m);
                }
            }
            return list;
        }

        /** Distinct bytes held in memory. */
        public long totalBytes() {
            return totalBytes;
        }

        /** A model by name, or by the start of its id (at least 8 hex digits, unambiguous). */
        public ServerModel find(String arg) {
            ServerModel m = byName(arg);
            if (m != null || arg.length() < 8) {
                return m;
            }
            String prefix = arg.toLowerCase(Locale.ROOT);
            ServerModel found = null;
            for (ServerModel candidate : models) {
                if (candidate.id()
                    .hex()
                    .startsWith(prefix)) {
                    if (found != null) {
                        return null;
                    }
                    found = candidate;
                }
            }
            return found;
        }
    }

    /** Reads every model under {@code modelsDir} (created if missing). */
    public Snapshot scan(File modelsDir, int revision) {
        List<String> log = new ArrayList<>();
        List<ServerModel> models = new ArrayList<>();
        if (!modelsDir.isDirectory() && !modelsDir.mkdirs()) {
            log.add("Cannot create " + modelsDir);
            return new Snapshot(revision, models, log);
        }
        File authDir = new File(modelsDir, AUTH);
        if (!authDir.isDirectory()) {
            authDir.mkdirs();
        }
        Map<String, ServerModel> byName = new HashMap<>();
        long[] total = { 0 };
        scanDir(modelsDir, false, models, byName, total, log);
        scanDir(authDir, true, models, byName, total, log);
        return new Snapshot(revision, models, log);
    }

    private void scanDir(File dir, boolean restricted, List<ServerModel> models, Map<String, ServerModel> byName,
        long[] total, List<String> log) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        Arrays.sort(entries);
        for (File entry : entries) {
            String fileName = entry.getName();
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (fileName.startsWith(".") || !restricted && entry.isDirectory() && lower.equals(AUTH)) {
                continue;
            }
            String label = (restricted ? AUTH + "/" : "") + fileName;
            boolean zip = entry.isFile() && lower.endsWith(".zip");
            boolean ysm = entry.isFile() && lower.endsWith(".ysm");
            if (!entry.isDirectory() && !zip && !ysm) {
                log.add("Skipped " + label + ": not a model folder, .zip or .ysm");
                continue;
            }
            String name = zip || ysm ? fileName.substring(0, fileName.length() - 4) : fileName;
            if (name.isEmpty() || !Protocol.printable(name) || name.contains(" ")) {
                log.add("Skipped " + label + ": the name has spaces or control characters");
                continue;
            }
            if (byName.containsKey(name.toLowerCase(Locale.ROOT))) {
                log.add("Skipped " + label + ": another model is already called " + name);
                continue;
            }
            try {
                ServerModel model = ysm ? readYsm(entry, name, restricted) : read(entry, name, zip, restricted);
                ServerModel same = sameFiles(models, model);
                if (same != null) {
                    log.add("Skipped " + label + ": the same files as " + same.name);
                    continue;
                }
                long added = 0;
                for (byte[] content : model.content.values()) {
                    added += content.length;
                }
                if (total[0] + added > maxTotalBytes) {
                    log.add("Skipped " + label + ": the models would hold more than " + maxTotalBytes + " bytes");
                    continue;
                }
                total[0] += added;
                models.add(model);
                byName.put(name.toLowerCase(Locale.ROOT), model);
                log.add("Loaded " + label + ": " + model.entry + (model.entry.memoryOnly ? ", memory only" : ""));
            } catch (IOException | RuntimeException e) {
                log.add("Skipped " + label + ": " + e.getMessage());
            }
        }
    }

    private static ServerModel sameFiles(List<ServerModel> models, ServerModel model) {
        for (ServerModel m : models) {
            if (m.id()
                .equals(model.id())) {
                return m;
            }
        }
        return null;
    }

    /** Reads, validates and describes one model. */
    ServerModel read(File entry, String name, boolean zip, boolean restricted) throws IOException {
        Map<String, byte[]> files = zip ? readZip(entry) : readFolder(entry.toPath());
        if (LegacyLayout.applies(files)) {
            LegacyLayout.addManifest(files);
        }
        return describe(rebase(files), name, restricted, false);
    }

    /** Reads a legacy {@code .ysm}: converted in memory, the result validated like any model, memory only. */
    ServerModel readYsm(File entry, String name, boolean restricted) throws IOException {
        long length = entry.length();
        if (length > MAX_YSM_BYTES) {
            throw new IOException("over " + MAX_YSM_BYTES + " bytes");
        }
        byte[] data = java.nio.file.Files.readAllBytes(entry.toPath());
        LegacyYsm.Kind kind = LegacyYsm.probe(data);
        Map<String, byte[]> converted = LegacyYsm.read(data, v3, limits.maxFileBytes, limits.maxModelBytes);
        Map<String, byte[]> files = new TreeMap<>();
        long[] total = { 0 };
        for (Map.Entry<String, byte[]> f : converted.entrySet()) {
            String path = ModelSource.clean(f.getKey());
            if (path.isEmpty() || ignored(path) || !ModelLimits.transferable(path)) {
                continue;
            }
            if (files.containsKey(path)) {
                throw new IOException("repeated file " + path + " in the " + kind + " .ysm");
            }
            add(files, path, f.getValue(), total);
        }
        return describe(files, name, restricted, true);
    }

    private ServerModel describe(Map<String, byte[]> files, String name, boolean restricted, boolean memoryOnly)
        throws IOException {
        MemorySource source = new MemorySource(name, files);
        LoadedModel loaded = limits.validate(files, source);

        List<FileRef> refs = new ArrayList<>();
        Map<Sha256, byte[]> content = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            Sha256 hash = Sha256.of(f.getValue());
            refs.add(new FileRef(f.getKey(), f.getValue().length, hash));
            content.put(hash, f.getValue());
        }
        Sha256 id = CatalogEntry.computeId(refs);

        ModelMetadata meta = loaded.manifest.metadata;
        String display = meta != null && meta.name != null
            && !meta.name.trim()
                .isEmpty() ? meta.name.trim() : name;
        String tips = meta != null && meta.tips != null ? meta.tips : "";
        String license = meta != null && meta.license != null && meta.license.type != null ? meta.license.type : "";
        List<String> authors = new ArrayList<>();
        if (meta != null && meta.authors != null) {
            for (ModelAuthor a : meta.authors) {
                if (a != null && a.name != null && !a.name.isEmpty() && authors.size() < Protocol.MAX_AUTHORS) {
                    authors.add(MessageCodec.truncate(oneLine(a.name), Protocol.MAX_AUTHOR_BYTES));
                }
            }
        }
        CatalogEntry entryInfo = new CatalogEntry(
            id,
            MessageCodec.truncate(oneLine(display), Protocol.MAX_NAME_BYTES),
            MessageCodec.truncate(tips, Protocol.MAX_TIPS_BYTES),
            MessageCodec.truncate(oneLine(license), Protocol.MAX_LICENSE_BYTES),
            authors,
            restricted,
            false,
            memoryOnly,
            refs);
        int encoded;
        try {
            encoded = MessageCodec.encode(new Message.ModelEntry(0, entryInfo.withLocked(true))).length;
        } catch (IllegalArgumentException e) {
            throw new IOException("its catalog entry cannot be encoded: " + e.getMessage());
        }
        if (encoded > Protocol.TARGET_PAYLOAD) {
            throw new IOException("its file list does not fit in one catalog message (" + encoded + " bytes)");
        }
        List<String> textures = new ArrayList<>();
        for (String texture : loaded.textures) {
            textures.add(stem(texture));
        }
        return new ServerModel(name, restricted, entryInfo, textures, content);
    }

    static String stem(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        return dot < 0 ? file : file.substring(0, dot);
    }

    private static String oneLine(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(Character.isISOControl(c) ? ' ' : c);
        }
        return b.toString()
            .trim();
    }

    /** Hidden files and folders, and the {@code __MACOSX} folder some zips carry. */
    static boolean ignored(String path) {
        for (String part : path.split("/")) {
            if (part.startsWith(".") || part.equals("__MACOSX")) {
                return true;
            }
        }
        return false;
    }

    private Map<String, byte[]> readFolder(Path root) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        long[] total = { 0 };
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && dir.getFileName()
                    .toString()
                    .startsWith(".") ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || attrs.isSymbolicLink()) {
                    return FileVisitResult.CONTINUE; // links are not followed out of the model folder
                }
                StringBuilder rel = new StringBuilder();
                for (Path part : root.relativize(file)) {
                    if (rel.length() > 0) {
                        rel.append('/');
                    }
                    rel.append(part.toString());
                }
                String path = ModelSource.clean(rel.toString());
                if (ignored(path) || !ModelLimits.transferable(path)) {
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.size() > limits.maxFileBytes) {
                    throw new IOException(path + " is over " + limits.maxFileBytes + " bytes");
                }
                try (InputStream in = Files.newInputStream(file)) {
                    add(files, path, readBounded(in, limits.maxFileBytes, path), total);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private Map<String, byte[]> readZip(File zip) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        long[] total = { 0 };
        try (ZipFile file = new ZipFile(zip, StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> entries = file.entries();
            int seen = 0;
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (++seen > MAX_ZIP_ENTRIES) {
                    throw new IOException("more than " + MAX_ZIP_ENTRIES + " zip entries");
                }
                if (e.isDirectory()) {
                    continue;
                }
                // A path leaving the model (zip slip) refuses the whole model.
                String path = ModelSource.clean(e.getName());
                if (path.isEmpty() || ignored(path) || !ModelLimits.transferable(path)) {
                    continue;
                }
                if (files.containsKey(path)) {
                    throw new IOException("repeated zip entry " + path);
                }
                // The declared size is a hint; what is read is counted (a zip bomb stops at the limit).
                if (e.getSize() > limits.maxFileBytes) {
                    throw new IOException(path + " is over " + limits.maxFileBytes + " bytes");
                }
                try (InputStream in = file.getInputStream(e)) {
                    add(files, path, readBounded(in, limits.maxFileBytes, path), total);
                }
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("unreadable zip (entry names must be UTF-8): " + e.getMessage(), e);
        }
        return files;
    }

    private void add(Map<String, byte[]> files, String path, byte[] data, long[] total) throws IOException {
        if (files.size() >= limits.maxFiles) {
            throw new IOException("more than " + limits.maxFiles + " files");
        }
        total[0] += data.length;
        if (total[0] > limits.maxModelBytes) {
            throw new IOException("more than " + limits.maxModelBytes + " bytes");
        }
        files.put(path, data);
    }

    static byte[] readBounded(InputStream in, int max, String path) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) {
                throw new IOException(path + " is over " + max + " bytes");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** A model packed inside one top folder ({@code name/ysm.json}) is read from that folder. */
    static Map<String, byte[]> rebase(Map<String, byte[]> files) throws IOException {
        if (files.containsKey("ysm.json")) {
            return files;
        }
        String top = null;
        for (String path : files.keySet()) {
            int slash = path.indexOf('/');
            if (slash > 0 && path.substring(slash + 1)
                .equals("ysm.json")) {
                if (top != null) {
                    throw new IOException("more than one ysm.json");
                }
                top = path.substring(0, slash + 1);
            }
        }
        if (top == null) {
            throw new IOException("no ysm.json");
        }
        Map<String, byte[]> rebased = new TreeMap<>();
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            if (f.getKey()
                .startsWith(top)) {
                rebased.put(
                    f.getKey()
                        .substring(top.length()),
                    f.getValue());
            }
        }
        return rebased;
    }

    /** The files of a model being validated. */
    private static final class MemorySource implements ModelSource {

        private final String name;
        private final Map<String, byte[]> files;

        MemorySource(String name, Map<String, byte[]> files) {
            this.name = name;
            this.files = files;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public InputStream open(String path) throws IOException {
            byte[] data = files.get(ModelSource.clean(path));
            return data == null ? null : new ByteArrayInputStream(data);
        }
    }
}
