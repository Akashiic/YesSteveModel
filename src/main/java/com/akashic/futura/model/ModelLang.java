/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * the language files of src/main/java/com/elfmcys/ysm/format/parser/RawModelAssembler.java (writeLanguageFiles),
 * the lookup of src/main/java/com/elfmcys/ysm/format/schema/model/views/ModelInfoView.java (translate) and the keys
 * used by src/main/java/com/elfmcys/ysm/client/gui/AnimationRouletteScreen.java, ModelInfoScreen.java,
 * button/AuthorButton.java and button/CatalogTextureButton.java, Apache License 2.0.
 * Changes: no protobuf info; locales are matched in lower case, so the 1.7.10 language code ("pt_BR") finds
 * "pt_br.json"; a broken language file is skipped instead of failing the model.
 */
package com.akashic.futura.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * A model's own translations: every {@code .json} file under {@code files.language_path} (default {@code lang/}), a
 * flat object of string keys to string values, by locale (the file's path under that folder without {@code .json},
 * in lower case: {@code en_us}, {@code zh_cn}). Upstream does not use the game's language files for these texts.
 * No Minecraft classes.
 *
 * <p>
 * A text is looked up in the current locale, then in {@code en_us}, then the {@code ysm.json} text is used (upstream's
 * {@code ModelInfoView.translate}). The keys are upstream's: {@code metadata.name}, {@code metadata.tips},
 * {@code metadata.authors.<n>.name}, {@code properties.extra_animation.<key>} (its {@code .desc} is the roulette's
 * tooltip), {@code properties.extra_animation_buttons.<id>.config_forms.<n>.title}/{@code .description}/
 * {@code .labels.<m>} and {@code files.player.texture.<stem>}.
 */
public final class ModelLang {

    /** Upstream {@code LanguageManager.DEFAULT_LANGUAGE_CODE}: the fallback locale. */
    public static final String DEFAULT_LOCALE = "en_us";
    /** Upstream's folder when {@code files.language_path} is blank. */
    public static final String DEFAULT_PATH = "lang";
    /** Largest language file read (a model's lang files are a few KB). */
    static final int MAX_FILE_BYTES = 1 << 20;

    public static final ModelLang EMPTY = new ModelLang(Collections.<String, Map<String, String>>emptyMap());

    private final Map<String, Map<String, String>> locales;

    private ModelLang(Map<String, Map<String, String>> locales) {
        this.locales = locales;
    }

    /**
     * @param locales locale (any case) to its entries; copied
     */
    public static ModelLang of(Map<String, Map<String, String>> locales) {
        Map<String, Map<String, String>> copy = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> e : locales.entrySet()) {
            copy.put(normalise(e.getKey()), Collections.unmodifiableMap(new HashMap<>(e.getValue())));
        }
        return copy.isEmpty() ? EMPTY : new ModelLang(Collections.unmodifiableMap(copy));
    }

    /**
     * Reads the language files of a model. A source that cannot list its files has none; a file that is not a JSON
     * object is skipped; values that are not strings are ignored.
     *
     * @param languagePath {@code files.language_path}; blank for {@code lang}
     */
    public static ModelLang read(ModelSource source, String languagePath) throws IOException {
        String folder = languagePath == null || languagePath.trim()
            .isEmpty() ? DEFAULT_PATH : languagePath.trim();
        folder = ModelSource.clean(folder);
        String prefix = folder.isEmpty() || folder.endsWith("/") ? folder : folder + "/";
        List<String> files = ModelSource.filesUnder(source.list(), folder, ".json");
        Map<String, Map<String, String>> locales = new HashMap<>();
        for (String path : files) {
            String relative = path.startsWith(prefix) ? path.substring(prefix.length()) : path;
            String locale = normalise(relative.substring(0, relative.length() - ".json".length()));
            Map<String, String> entries = readFile(source, path);
            if (entries != null) {
                Map<String, String> existing = locales.get(locale);
                if (existing == null) {
                    locales.put(locale, entries);
                } else {
                    // Two files for one locale in different cases: the later (upstream's order) wins per key.
                    existing.putAll(entries);
                }
            }
        }
        return locales.isEmpty() ? EMPTY : of(locales);
    }

    /** {@link #read}, or {@link #EMPTY} if the source cannot be read. */
    public static ModelLang readQuietly(ModelSource source, String languagePath) {
        try {
            return read(source, languagePath);
        } catch (IOException | RuntimeException e) {
            return EMPTY;
        }
    }

    private static Map<String, String> readFile(ModelSource source, String path) throws IOException {
        byte[] bytes;
        try (InputStream in = source.open(path)) {
            if (in == null) {
                return null;
            }
            bytes = readLimited(in);
        }
        if (bytes == null) {
            return null;
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        try (Reader reader = new java.io.StringReader(text)) {
            JsonElement root = new JsonParser().parse(reader);
            if (root == null || !root.isJsonObject()) {
                return null;
            }
            JsonObject object = root.getAsJsonObject();
            Map<String, String> entries = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : object.entrySet()) {
                JsonElement v = e.getValue();
                if (v != null && v.isJsonPrimitive()
                    && v.getAsJsonPrimitive()
                        .isString()) {
                    entries.put(e.getKey(), v.getAsString());
                }
            }
            return entries;
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > MAX_FILE_BYTES) {
                return null;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * The model locale for a game language code: 1.7.10's {@code GameSettings.language} ({@code en_US},
     * {@code pt_BR}) in lower case, as 1.20's language codes and the model files are; {@link #DEFAULT_LOCALE} for
     * none.
     */
    public static String locale(String gameLanguage) {
        if (gameLanguage == null || gameLanguage.trim()
            .isEmpty()) {
            return DEFAULT_LOCALE;
        }
        return normalise(gameLanguage);
    }

    private static String normalise(String locale) {
        return locale.trim()
            .replace('-', '_')
            .toLowerCase(Locale.ROOT);
    }

    public boolean isEmpty() {
        return locales.isEmpty();
    }

    /** The locales of the model's files, sorted. */
    public Set<String> locales() {
        return Collections.unmodifiableSet(new TreeSet<>(locales.keySet()));
    }

    /**
     * Upstream's lookup: the text in {@code locale}, else in {@link #DEFAULT_LOCALE}, else null.
     *
     * @param locale a model locale ({@link #locale(String)})
     */
    public String translate(String locale, String key) {
        if (key == null) {
            return null;
        }
        String value = lookup(locale == null ? DEFAULT_LOCALE : normalise(locale), key);
        return value != null ? value : lookup(DEFAULT_LOCALE, key);
    }

    private String lookup(String locale, String key) {
        Map<String, String> entries = locales.get(locale);
        return entries == null ? null : entries.get(key);
    }

    /** {@link #translate}, or {@code fallback} (the {@code ysm.json} text) when no file has the key. */
    public String get(String locale, String key, String fallback) {
        String value = translate(locale, key);
        return value != null ? value : fallback;
    }

    /** {@link #get}, but a blank translation also gives the fallback (names must not vanish). */
    public String nonBlank(String locale, String key, String fallback) {
        String value = translate(locale, key);
        return value != null && !value.trim()
            .isEmpty() ? value : fallback;
    }

    // ---- Upstream's keys ----

    public static final String NAME = "metadata.name";
    public static final String TIPS = "metadata.tips";

    /** {@code metadata.authors.<index>.name}, index in the manifest's author list. */
    public static String authorName(int index) {
        return "metadata.authors." + index + ".name";
    }

    /** The label of a roulette entry: {@code properties.extra_animation.<key>} (also {@code #id} sub-pages). */
    public static String extraAnimation(String key) {
        return "properties.extra_animation." + key;
    }

    /** The roulette tooltip of an entry: {@code properties.extra_animation.<key>.desc}. */
    public static String extraAnimationDesc(String key) {
        return "properties.extra_animation." + key + ".desc";
    }

    /**
     * A config form's title; {@code form} counts only the forms upstream shows (checkbox, range, radio), as its
     * {@code ConfigActionRoute} index does.
     */
    public static String configTitle(String buttonId, int form) {
        return "properties.extra_animation_buttons." + buttonId + ".config_forms." + form + ".title";
    }

    public static String configDescription(String buttonId, int form) {
        return "properties.extra_animation_buttons." + buttonId + ".config_forms." + form + ".description";
    }

    public static String configLabel(String buttonId, int form, int label) {
        return "properties.extra_animation_buttons." + buttonId + ".config_forms." + form + ".labels." + label;
    }

    /** A texture's display name: {@code files.player.texture.<stem>}. */
    public static String texture(String stem) {
        return "files.player.texture." + stem;
    }
}
