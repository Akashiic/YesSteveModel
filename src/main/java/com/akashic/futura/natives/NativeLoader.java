package com.akashic.futura.natives;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import com.akashic.futura.net.Sha256;

/**
 * Loads the bundled native library ({@code natives/<platform>/} in the jar: Linux x86_64 for servers, Windows x64 for
 * clients' singleplayer). The library is copied once to a directory named after its hash (a loaded DLL cannot be
 * replaced on Windows, so a new version gets a new file) and checked against the {@code .sha256} shipped beside it.
 * {@code -Dfutura3d.native.path=/abs/libfutura3d.so} loads another build instead. Nothing here needs Minecraft.
 */
public final class NativeLoader {

    /** What {@link #state()} reports. */
    public enum State {
        NOT_TRIED,
        LOADED,
        UNSUPPORTED_PLATFORM,
        FAILED
    }

    private static State state = State.NOT_TRIED;
    private static String detail = "";

    private NativeLoader() {}

    /** {@code linux-x86_64}, {@code windows-x86_64}, or null where no library is shipped. */
    public static String platform() {
        return platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static String platform(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (!x64) {
            return null;
        }
        if (os.contains("linux")) {
            return "linux-x86_64";
        }
        if (os.startsWith("windows")) {
            return "windows-x86_64";
        }
        return null;
    }

    static String libraryName(String platform) {
        return platform.startsWith("windows") ? "futura3d.dll" : "libfutura3d.so";
    }

    /** Loads the library once; later calls return the first outcome. */
    public static synchronized State load() {
        if (state != State.NOT_TRIED) {
            return state;
        }
        String override = System.getProperty("futura3d.native.path");
        try {
            if (override != null && !override.trim()
                .isEmpty()) {
                System.load(new File(override.trim()).getAbsolutePath());
                detail = override.trim();
            } else {
                String platform = platform();
                if (platform == null) {
                    state = State.UNSUPPORTED_PLATFORM;
                    detail = System.getProperty("os.name") + " " + System.getProperty("os.arch");
                    return state;
                }
                File file = extract(platform, directory());
                System.load(file.getAbsolutePath());
                detail = file.getPath();
            }
            int abi = LegacyNative.nAbiVersion();
            if (abi != LegacyNative.ABI) {
                state = State.FAILED;
                detail = "native ABI " + abi + ", expected " + LegacyNative.ABI;
                return state;
            }
            state = State.LOADED;
        } catch (IOException | UnsatisfiedLinkError | SecurityException e) {
            state = State.FAILED;
            detail = e.toString();
        }
        return state;
    }

    public static synchronized State state() {
        return state;
    }

    /** The loaded file, or why it is not loaded. */
    public static synchronized String detail() {
        return detail;
    }

    private static File directory() {
        String dir = System.getProperty("futura3d.native.dir");
        if (dir != null && !dir.trim()
            .isEmpty()) {
            return new File(dir.trim());
        }
        return new File(System.getProperty("java.io.tmpdir"), "futura3d-natives");
    }

    /** Copies the platform's library out of the jar (or reuses an identical copy) and returns the file. */
    static File extract(String platform, File dir) throws IOException {
        String name = libraryName(platform);
        String base = "/natives/" + platform + "/";
        byte[] library = resource(base + name);
        String expected = new String(resource(base + name + ".sha256"), StandardCharsets.US_ASCII).trim()
            .split("\\s+")[0];
        String actual = Sha256.of(library)
            .hex();
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException("bundled " + name + " does not match its sha256");
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        int dot = name.lastIndexOf('.');
        File target = new File(dir, name.substring(0, dot) + "-" + actual.substring(0, 16) + name.substring(dot));
        if (target.isFile() && Sha256.of(Files.readAllBytes(target.toPath()))
            .hex()
            .equalsIgnoreCase(actual)) {
            return target;
        }
        File temp = File.createTempFile(name, ".tmp", dir);
        try {
            Files.write(temp.toPath(), library);
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp.toPath());
        }
        return target;
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = NativeLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing resource " + path);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
