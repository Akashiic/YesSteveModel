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
 * What the model picker shows of a model, from its {@code ysm.json} alone (no geometry): name, authors, licence,
 * texture names and the preview animation. No Minecraft classes.
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

    private ModelInfo(String name, String tips, List<String> authors, String license, String licenseDesc,
        List<String> textures, String defaultTexture, String previewAnimation, boolean free,
        boolean disablePreviewRotation) {
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
    }

    /** Reads only {@code ysm.json} of a source. */
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
            return of(manifest, source.name());
        } catch (JsonParseException e) {
            throw new IOException(source.name() + ": invalid ysm.json: " + e.getMessage(), e);
        }
    }

    /**
     * @param manifest     a normalised manifest
     * @param fallbackName shown when the manifest has no name
     */
    public static ModelInfo of(ModelManifest manifest, String fallbackName) {
        String name = fallbackName;
        String tips = "";
        String license = "All Rights Reserved";
        String licenseDesc = "";
        List<String> authors = new ArrayList<>();
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
                for (ModelAuthor author : manifest.metadata.authors) {
                    if (author != null && author.name != null
                        && !author.name.trim()
                            .isEmpty()) {
                        authors.add(author.name.trim());
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
        return new ModelInfo(
            name,
            tips,
            authors,
            license,
            licenseDesc,
            textures,
            defaultTexture,
            manifest.properties.previewAnimation == null ? "" : manifest.properties.previewAnimation,
            manifest.properties.free,
            manifest.properties.disablePreviewRotation);
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
