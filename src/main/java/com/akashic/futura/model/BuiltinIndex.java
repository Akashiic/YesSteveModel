package com.akashic.futura.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The built-in models bundled in the mod: {@code /assets/futura3d/builtin/index.txt} lists their folders in display
 * order (a jar cannot be listed portably at run time). No Minecraft classes.
 */
public final class BuiltinIndex {

    public static final String BASE = "/assets/futura3d/builtin/";
    public static final String FILE = BASE + "index.txt";

    private BuiltinIndex() {}

    /** The folders listed in the bundled index, or an empty list if it is missing. */
    public static List<String> read() throws IOException {
        try (InputStream in = BuiltinIndex.class.getResourceAsStream(FILE)) {
            if (in == null) {
                return new ArrayList<>();
            }
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /** One folder per line; blank lines and {@code #} comments are skipped, as are paths that leave the folder. */
    public static List<String> parse(Reader reader) throws IOException {
        Set<String> folders = new LinkedHashSet<>();
        BufferedReader lines = new BufferedReader(reader);
        String line;
        while ((line = lines.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                String folder = ModelSource.clean(line);
                while (folder.endsWith("/")) {
                    folder = folder.substring(0, folder.length() - 1);
                }
                if (!folder.isEmpty()) {
                    folders.add(folder);
                }
            } catch (IOException e) {
                // a bad line is skipped
            }
        }
        return new ArrayList<>(folders);
    }

    /** The source of a built-in model folder. */
    public static ClasspathModelSource source(String folder) {
        return new ClasspathModelSource(BASE + folder + "/");
    }
}
