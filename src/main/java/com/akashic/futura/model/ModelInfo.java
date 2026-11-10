package com.akashic.futura.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.akashic.futura.format.FormatGson;
import com.akashic.futura.format.pojo.manifest.ModelManifest;
import com.akashic.futura.format.pojo.manifest.metadata.ModelAuthor;
import com.akashic.futura.format.pojo.manifest.models.PBRTextureSet;
import com.google.gson.JsonParseException;

/**
 * What the model picker shows of a model, from its {@code ysm.json} and its language files (no geometry): name,
 * authors, licence, texture names and the preview animation, and their translations ({@link #lang}). No Minecraft
 * classes.
 */
public final class ModelInfo {

    /** How freely a licence lets the model be used, for the picker's colour. */
    public enum LicenseKind {
        /** CC0 and other public-domain dedications. */
        PUBLIC_DOMAIN,
        /** Free licences that allow commercial use (CC BY, CC BY-SA, MIT...). */
        OPEN,
        /** Free, but not for commercial use (any CC ...-NC-...). */
        NON_COMMERCIAL,
        /** All Rights Reserved, no-derivatives, or a licence that is not recognised. */
        RESTRICTED
    }

    public final String name;
    public final String tips;
    public final List<String> authors;
    public final String license;
    public final String licenseDesc;
    /** Texture names (file stems), in manifest order; never empty for a valid model. */
    public final List<String> textures;
    /** The texture drawn when none is chosen (a stem of {@link #textures}). */
    public final String defaultTexture;
    public final String previewAnimation;
    public final boolean free;
    public final boolean disablePreviewRotation;
    /** The model's own translations ({@code lang/*.json}); {@link ModelLang#EMPTY} when it has none. */
    public final ModelLang lang;
    /** For each of {@link #authors}, its index in the manifest's author list (the translation keys use it). */
    private final int[] authorIndices;

    private ModelInfo(String name, String tips, List<String> authors, int[] authorIndices, String license,
        String licenseDesc, List<String> textures, String defaultTexture, String previewAnimation, boolean free,
        boolean disablePreviewRotation, ModelLang lang) {
        this.name = name;
        this.tips = tips;
        this.authors = Collections.unmodifiableList(authors);
        this.license = license;
        this.licenseDesc = licenseDesc;
        this.textures = Collections.unmodifiableList(textures);
        this.defaultTexture = defaultTexture;
        this.previewAnimation = previewAnimation;
        this.free = free;
        this.disablePreviewRotation = disablePreviewRotation;
        this.authorIndices = authorIndices;
        this.lang = lang;
    }

    /** Reads {@code ysm.json} and the language files of a source. */
    public static ModelInfo read(ModelSource source) throws IOException {
        try (InputStream in = source.open("ysm.json")) {
            if (in == null) {
                throw new IOException(source.name() + ": missing ysm.json");
            }
            ModelManifest manifest = FormatGson.create()
                .fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), ModelManifest.class);
            if (manifest == null) {
                throw new IOException(source.name() + ": empty ysm.json");
            }
            manifest.normalize();
            return of(manifest, source.name(), ModelLang.readQuietly(source, manifest.files.languagePath));
        } catch (JsonParseException e) {
            throw new IOException(source.name() + ": invalid ysm.json: " + e.getMessage(), e);
        }
    }

    /**
     * @param manifest     a normalised manifest
     * @param fallbackName shown when the manifest has no name
     */
    public static ModelInfo of(ModelManifest manifest, String fallbackName) {
        return of(manifest, fallbackName, ModelLang.EMPTY);
    }

    /**
     * @param manifest     a normalised manifest
     * @param fallbackName shown when the manifest has no name
     * @param lang         the model's language files
     */
    public static ModelInfo of(ModelManifest manifest, String fallbackName, ModelLang lang) {
        String name = fallbackName;
        String tips = "";
        String license = "All Rights Reserved";
        String licenseDesc = "";
        List<String> authors = new ArrayList<>();
        List<Integer> authorIndices = new ArrayList<>();
        if (manifest.metadata != null) {
            if (manifest.metadata.name != null && !manifest.metadata.name.trim()
                .isEmpty()) {
                name = manifest.metadata.name.trim();
            }
            tips = manifest.metadata.tips == null ? "" : manifest.metadata.tips;
            if (manifest.metadata.license != null) {
                if (manifest.metadata.license.type != null && !manifest.metadata.license.type.trim()
                    .isEmpty()) {
                    license = manifest.metadata.license.type.trim();
                }
                licenseDesc = manifest.metadata.license.desc == null ? "" : manifest.metadata.license.desc;
            }
            if (manifest.metadata.authors != null) {
                for (int i = 0; i < manifest.metadata.authors.size(); i++) {
                    ModelAuthor author = manifest.metadata.authors.get(i);
                    if (author != null && author.name != null
                        && !author.name.trim()
                            .isEmpty()) {
                        authors.add(author.name.trim());
                        authorIndices.add(i);
                    }
                }
            }
        }
        List<String> textures = new ArrayList<>();
        for (PBRTextureSet texture : manifest.files.player.texture) {
            if (texture != null && texture.uv != null && !texture.uv.isEmpty()) {
                textures.add(LoadedModel.stem(texture.uv));
            }
        }
        String wanted = manifest.properties.defaultTexture;
        String defaultTexture = textures.isEmpty() ? "" : textures.get(0);
        if (wanted != null && textures.contains(wanted)) {
            defaultTexture = wanted;
        }
        int[] indices = new int[authorIndices.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = authorIndices.get(i);
        }
        return new ModelInfo(
            name,
            tips,
            authors,
            indices,
            license,
            licenseDesc,
            textures,
            defaultTexture,
            manifest.properties.previewAnimation == null ? "" : manifest.properties.previewAnimation,
            manifest.properties.free,
            manifest.properties.disablePreviewRotation,
            lang == null ? ModelLang.EMPTY : lang);
    }

    /** The model's name in a locale ({@code metadata.name}), else the manifest's. */
    public String name(String locale) {
        return lang.nonBlank(locale, ModelLang.NAME, name);
    }

    /** The model's tips in a locale ({@code metadata.tips}), else the manifest's (may be empty). */
    public String tips(String locale) {
        return lang.get(locale, ModelLang.TIPS, tips);
    }

    /** The authors' names in a locale ({@code metadata.authors.<n>.name}), else the manifest's. */
    public List<String> authors(String locale) {
        List<String> out = new ArrayList<>(authors.size());
        for (int i = 0; i < authors.size(); i++) {
            out.add(lang.nonBlank(locale, ModelLang.authorName(authorIndices[i]), authors.get(i)));
        }
        return out;
    }

    /** A texture's display name in a locale ({@code files.player.texture.<stem>}), else the stem. */
    public String textureName(String stem, String locale) {
        return lang.nonBlank(locale, ModelLang.texture(stem), stem);
    }

    /** A texture name of this model, or the default one when {@code wanted} is null or unknown. */
    public String texture(String wanted) {
        return wanted != null && textures.contains(wanted) ? wanted : defaultTexture;
    }

    public LicenseKind licenseKind() {
        return licenseKind(license);
    }

    /** A few characters for a small label: {@code CC0}, {@code BY-NC-SA}, {@code ARR}, or the licence trimmed. */
    public String shortLicense() {
        return shortLicense(license);
    }

    public static String shortLicense(String license) {
        if (license == null || license.trim()
            .isEmpty()) {
            return "ARR";
        }
        String l = license.trim();
        String upper = l.toUpperCase(Locale.ROOT);
        String compact = upper.replace(" ", "")
            .replace("-", "")
            .replace("_", "");
        if (compact.startsWith("CC0")) {
            return "CC0";
        }
        if (compact.startsWith("ALLRIGHTSRESERVED")) {
            return "ARR";
        }
        if (upper.startsWith("CC ") || upper.startsWith("CC-")) {
            // "CC BY-NC-SA 4.0" -> "BY-NC-SA"
            String rest = l.substring(3)
                .trim();
            int space = rest.indexOf(' ');
            return (space < 0 ? rest : rest.substring(0, space)).toUpperCase(Locale.ROOT);
        }
        return l.length() <= 10 ? l : l.substring(0, 9) + "…";
    }

    /** Classifies a licence name as written in manifests ({@code CC 0}, {@code CC BY-NC-SA 4.0}, {@code MIT}...). */
    public static LicenseKind licenseKind(String license) {
        if (license == null) {
            return LicenseKind.RESTRICTED;
        }
        String l = license.toUpperCase(Locale.ROOT)
            .replace('_', ' ')
            .replace('-', ' ')
            .replaceAll("\\s+", " ")
            .trim();
        String compact = l.replace(" ", "");
        if (compact.startsWith("CC0") || compact.contains("PUBLICDOMAIN") || compact.equals("UNLICENSE")) {
            return LicenseKind.PUBLIC_DOMAIN;
        }
        boolean cc = compact.startsWith("CC") || l.contains("CREATIVE COMMONS");
        if (cc) {
            if (l.contains(" ND") || l.contains("NODERIVATIVES")) {
                return LicenseKind.RESTRICTED;
            }
            if (l.contains(" NC") || l.contains("NONCOMMERCIAL")) {
                return LicenseKind.NON_COMMERCIAL;
            }
            if (l.contains(" BY")) {
                return LicenseKind.OPEN;
            }
            return LicenseKind.RESTRICTED;
        }
        for (String open : new String[] { "MIT", "APACHE", "BSD", "ZLIB", "ISC", "MPL", "LGPL", "GPL" }) {
            if (compact.startsWith(open)) {
                return LicenseKind.OPEN;
            }
        }
        return LicenseKind.RESTRICTED;
    }
}
