package com.akashic.futura.server;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Test models copied from the built-in CC0 ones. */
final class ServerTestSupport {

    static final Path BUILTIN = Paths.get("src/main/resources/assets/futura3d/builtin");

    private ServerTestSupport() {}

    /** Copies a built-in model folder. */
    static void copyFolder(String builtin, Path target) throws IOException {
        Path from = BUILTIN.resolve(builtin);
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.collect(Collectors.toList())) {
                Path to = target.resolve(
                    from.relativize(p)
                        .toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(p, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Zips a built-in model folder, its files under {@code prefix} (empty or ending with {@code /}). */
    static void zipFolder(String builtin, Path zip, String prefix) throws IOException {
        Path from = BUILTIN.resolve(builtin);
        Files.createDirectories(zip.getParent());
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip.toFile()));
            Stream<Path> walk = Files.walk(from)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                .sorted()
                .collect(Collectors.toList());
            for (Path p : files) {
                out.putNextEntry(
                    new ZipEntry(
                        prefix + from.relativize(p)
                            .toString()
                            .replace('\\', '/')));
                out.write(Files.readAllBytes(p));
                out.closeEntry();
            }
        }
    }

    /** Adds one entry to a new zip next to the model's entries. */
    static void zipWith(String builtin, Path zip, String extraName, byte[] extra) throws IOException {
        Path from = BUILTIN.resolve(builtin);
        Files.createDirectories(zip.getParent());
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip.toFile()));
            Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.filter(Files::isRegularFile)
                .sorted()
                .collect(Collectors.toList())) {
                out.putNextEntry(
                    new ZipEntry(
                        from.relativize(p)
                            .toString()
                            .replace('\\', '/')));
                out.write(Files.readAllBytes(p));
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry(extraName));
            out.write(extra);
            out.closeEntry();
        }
    }
}
