package com.akashic.futura.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.akashic.futura.model.ModelInfo.LicenseKind;

/** The picker's view of a manifest, the licence classes, and the built-in index. */
class ModelInfoTest {

    private static final Path BUILTIN = Paths.get("src/main/resources/assets/futura3d/builtin");

    @Test
    void readsTheDefaultModelsManifest() throws IOException {
        ModelInfo info = ModelInfo.read(
            new FolderModelSource(
                BUILTIN.resolve("default")
                    .toFile()));
        assertEquals("Default", info.name);
        assertEquals("CC 0", info.license);
        assertEquals(LicenseKind.PUBLIC_DOMAIN, info.licenseKind());
        assertEquals("CC0", info.shortLicense());
        assertEquals(Arrays.asList("default", "blue"), info.textures);
        assertEquals("default", info.defaultTexture);
        assertEquals("blue", info.texture("blue"));
        assertEquals("default", info.texture("nothing"));
        assertEquals("default", info.texture(null));
        assertEquals("gui", info.previewAnimation);
        assertFalse(info.authors.isEmpty());
    }

    @Test
    void theDefaultTextureFollowsTheManifest() throws IOException {
        ModelInfo boy = ModelInfo.read(
            new FolderModelSource(
                BUILTIN.resolve("misc/3_default_boy")
                    .toFile()));
        assertEquals("blue", boy.defaultTexture);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(
            "ysm.json",
            "{\"spec\":2,\"files\":{\"player\":{\"texture\":[\"t/a.png\",\"t/b.png\"]}}}"
                .getBytes(StandardCharsets.UTF_8));
        ModelInfo bare = ModelInfo.read(new MemoryModelSource("bare", files));
        assertEquals("bare", bare.name, "no metadata: the fallback name");
        assertEquals("All Rights Reserved", bare.license);
        assertEquals(LicenseKind.RESTRICTED, bare.licenseKind());
        assertEquals("ARR", bare.shortLicense());
        assertEquals("a", bare.defaultTexture);
    }

    @Test
    void everyBuiltinModelHasItsLicenceRecognised() throws IOException {
        for (String folder : BuiltinIndex.read()) {
            ModelInfo info = ModelInfo.read(
                new FolderModelSource(
                    BUILTIN.resolve(folder)
                        .toFile()));
            assertTrue(
                info.licenseKind() == LicenseKind.PUBLIC_DOMAIN || info.licenseKind() == LicenseKind.NON_COMMERCIAL,
                folder + ": " + info.license);
            assertFalse(info.textures.isEmpty(), folder);
            assertTrue(info.textures.contains(info.defaultTexture), folder);
        }
    }

    @Test
    void classifiesLicences() {
        assertEquals(LicenseKind.PUBLIC_DOMAIN, ModelInfo.licenseKind("CC0 1.0"));
        assertEquals(LicenseKind.PUBLIC_DOMAIN, ModelInfo.licenseKind("cc-0"));
        assertEquals(LicenseKind.PUBLIC_DOMAIN, ModelInfo.licenseKind("Public Domain"));
        assertEquals(LicenseKind.OPEN, ModelInfo.licenseKind("CC BY 4.0"));
        assertEquals(LicenseKind.OPEN, ModelInfo.licenseKind("CC BY-SA 4.0"));
        assertEquals(LicenseKind.OPEN, ModelInfo.licenseKind("MIT"));
        assertEquals(LicenseKind.NON_COMMERCIAL, ModelInfo.licenseKind("CC BY-NC-SA 4.0"));
        assertEquals(LicenseKind.NON_COMMERCIAL, ModelInfo.licenseKind("CC BY-NC 4.0"));
        assertEquals(LicenseKind.RESTRICTED, ModelInfo.licenseKind("CC BY-NC-ND 4.0"));
        assertEquals(LicenseKind.RESTRICTED, ModelInfo.licenseKind("All Rights Reserved"));
        assertEquals(LicenseKind.RESTRICTED, ModelInfo.licenseKind("my own terms"));
        assertEquals(LicenseKind.RESTRICTED, ModelInfo.licenseKind(null));
        assertEquals("BY-NC-SA", ModelInfo.shortLicense("CC BY-NC-SA 4.0"));
        assertEquals("CC0", ModelInfo.shortLicense("CC 0"));
        assertEquals("MIT", ModelInfo.shortLicense("MIT"));
    }

    @Test
    void theBuiltinIndexListsEveryBuiltinModelOnceDefaultFirst() throws IOException {
        List<String> folders;
        try (Stream<Path> files = Files.walk(BUILTIN)) {
            folders = files.filter(
                p -> p.getFileName()
                    .toString()
                    .equals("ysm.json"))
                .map(
                    p -> BUILTIN.relativize(p.getParent())
                        .toString()
                        .replace('\\', '/'))
                .sorted()
                .collect(Collectors.toList());
        }
        List<String> index = BuiltinIndex.read();
        assertEquals("default", index.get(0));
        List<String> sorted = new ArrayList<>(index);
        Collections.sort(sorted);
        assertEquals(folders, sorted, "index.txt and the folders differ");
    }

    @Test
    void theIndexSkipsCommentsBlanksAndPathsOutside() throws IOException {
        assertEquals(
            Arrays.asList("a", "b/c"),
            BuiltinIndex.parse(new StringReader("# comment\n\na\n../x\nb/c/\na\n")));
    }
}
