package com.akashic.futura.net;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.akashic.futura.format.pojo.animation.AnimationFile;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelLoader;
import com.akashic.futura.model.ModelSource;
import com.akashic.futura.model.ReplacementTarget;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/**
 * What a model may contain before anyone parses it for real: file kinds, sizes, image dimensions, JSON depth and
 * string lengths (Molang expressions live in JSON strings), bone, cube and animation counts. The server checks every
 * model on load; the client checks a server model again before registering it.
 */
public final class ModelLimits {

    /** File kinds that are transferred; anything else in a model folder or zip is left out. */
    public static final Set<String> EXTENSIONS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("json", "png", "jpg", "jpeg", "webp", "avif", "ogg", "molang", "lang", "txt")));

    public long maxModelBytes = 32L * 1024 * 1024;
    public int maxFiles = Protocol.MAX_FILES;
    public int maxFileBytes = Protocol.MAX_FILE_BYTES;
    public int maxImageSide = 4096;
    public int maxBones = 4096;
    public int maxCubes = 32768;
    public int maxAnimations = 4096;
    /** Characters of one JSON string (names, Molang expressions). */
    public int maxJsonString = 64 * 1024;
    public int maxJsonDepth = 64;
    public int maxJsonTokens = 4_000_000;
    /** Bytes of a text file that is not JSON ({@code .molang}, {@code .lang}, {@code .txt}). */
    public int maxTextBytes = 256 * 1024;

    /** Whether a file of this path is part of the model (by extension). */
    public static boolean transferable(String path) {
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot <= slash + 1) {
            return false; // no extension, or a hidden file such as ".DS_Store"
        }
        return EXTENSIONS.contains(
            path.substring(dot + 1)
                .toLowerCase(Locale.ROOT));
    }

    private static String extension(String path) {
        return path.substring(path.lastIndexOf('.') + 1)
            .toLowerCase(Locale.ROOT);
    }

    /** Checks one file by kind. */
    public void checkFile(String path, byte[] data) throws IOException {
        if (data.length > maxFileBytes) {
            throw new IOException(path + ": " + data.length + " bytes, over " + maxFileBytes);
        }
        switch (extension(path)) {
            case "json":
                checkJson(path, data);
                break;
            case "png":
                checkPng(path, data);
                break;
            case "jpg":
            case "jpeg":
                checkJpeg(path, data);
                break;
            case "molang":
            case "lang":
            case "txt":
                if (data.length > maxTextBytes) {
                    throw new IOException(path + ": " + data.length + " bytes of text, over " + maxTextBytes);
                }
                break;
            default:
                break;
        }
    }

    /** Streams through the JSON without building it: depth, token count and string lengths. */
    void checkJson(String path, byte[] data) throws IOException {
        try (JsonReader in = new JsonReader(
            new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            in.setLenient(true); // as Gson.fromJson reads it
            int depth = 0;
            int tokens = 0;
            while (true) {
                JsonToken token = in.peek();
                if (++tokens > maxJsonTokens) {
                    throw new IOException(path + ": more than " + maxJsonTokens + " JSON tokens");
                }
                switch (token) {
                    case BEGIN_ARRAY:
                        in.beginArray();
                        depth++;
                        break;
                    case BEGIN_OBJECT:
                        in.beginObject();
                        depth++;
                        break;
                    case END_ARRAY:
                        in.endArray();
                        depth--;
                        break;
                    case END_OBJECT:
                        in.endObject();
                        depth--;
                        break;
                    case NAME:
                        checkString(path, in.nextName());
                        break;
                    case STRING:
                    case NUMBER:
                        checkString(path, in.nextString());
                        break;
                    case BOOLEAN:
                        in.nextBoolean();
                        break;
                    case NULL:
                        in.nextNull();
                        break;
                    case END_DOCUMENT:
                        return;
                    default:
                        throw new IOException(path + ": unexpected JSON token " + token);
                }
                if (depth > maxJsonDepth) {
                    throw new IOException(path + ": JSON nested deeper than " + maxJsonDepth);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException(path + ": invalid JSON: " + e.getMessage(), e);
        }
    }

    private void checkString(String path, String s) throws IOException {
        if (s.length() > maxJsonString) {
            throw new IOException(path + ": a JSON string of " + s.length() + " characters, over " + maxJsonString);
        }
    }

    /** Width and height from the PNG header ({@code IHDR}), without decoding the image. */
    void checkPng(String path, byte[] d) throws IOException {
        byte[] signature = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
        if (d.length < 24 || !Arrays.equals(Arrays.copyOf(d, 8), signature)
            || d[12] != 'I'
            || d[13] != 'H'
            || d[14] != 'D'
            || d[15] != 'R') {
            throw new IOException(path + ": not a PNG");
        }
        checkSize(path, int32(d, 16), int32(d, 20));
    }

    /** Width and height from the first JPEG frame header (SOFn). */
    void checkJpeg(String path, byte[] d) throws IOException {
        if (d.length < 4 || (d[0] & 255) != 0xFF || (d[1] & 255) != 0xD8) {
            throw new IOException(path + ": not a JPEG");
        }
        int i = 2;
        while (i + 9 < d.length) {
            if ((d[i] & 255) != 0xFF) {
                throw new IOException(path + ": broken JPEG markers");
            }
            int marker = d[i + 1] & 255;
            if (marker == 0xFF) {
                i++;
                continue;
            }
            int length = ((d[i + 2] & 255) << 8) | (d[i + 3] & 255);
            boolean sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                int height = ((d[i + 5] & 255) << 8) | (d[i + 6] & 255);
                int width = ((d[i + 7] & 255) << 8) | (d[i + 8] & 255);
                checkSize(path, width, height);
                return;
            }
            if (length < 2) {
                throw new IOException(path + ": broken JPEG segment");
            }
            i += 2 + length;
        }
        throw new IOException(path + ": JPEG without a frame header");
    }

    private void checkSize(String path, int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > maxImageSide || height > maxImageSide) {
            throw new IOException(path + ": image " + width + "x" + height + ", over " + maxImageSide);
        }
    }

    private static int int32(byte[] d, int at) {
        return ((d[at] & 255) << 24) | ((d[at + 1] & 255) << 16) | ((d[at + 2] & 255) << 8) | (d[at + 3] & 255);
    }

    /** Only the open format's spec 2 is read. */
    public static void checkSpec(byte[] manifest) throws IOException {
        try (Reader in = new InputStreamReader(new ByteArrayInputStream(manifest), StandardCharsets.UTF_8)) {
            JsonReader reader = new JsonReader(in);
            reader.setLenient(true);
            JsonElement root = new JsonParser().parse(reader);
            JsonElement spec = root.isJsonObject() ? ((JsonObject) root).get("spec") : null;
            if (spec == null || !spec.isJsonPrimitive()
                || !spec.getAsJsonPrimitive()
                    .isNumber()
                || spec.getAsInt() != 2) {
                throw new IOException("ysm.json is not spec 2");
            }
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("ysm.json is not valid JSON: " + e.getMessage(), e);
        }
    }

    /** Bones, cubes and animations of a parsed model, its projectile and vehicle replacements included. */
    public void checkModel(LoadedModel model) throws IOException {
        int bones = model.main.bones.length + model.arm.bones.length;
        int cubes = model.main.cubeCount + model.arm.cubeCount;
        int animations = 0;
        for (ReplacementTarget target : model.replacements()) {
            bones += target.geometry.bones.length;
            cubes += target.geometry.cubeCount;
            animations += target.animation == null || target.animation.animations == null ? 0
                : target.animation.animations.size();
        }
        if (bones > maxBones) {
            throw new IOException(bones + " bones, over " + maxBones);
        }
        if (cubes > maxCubes) {
            throw new IOException(cubes + " cubes, over " + maxCubes);
        }
        for (AnimationFile file : model.animations.values()) {
            animations += file.animations == null ? 0 : file.animations.size();
        }
        if (animations > maxAnimations) {
            throw new IOException(animations + " animations, over " + maxAnimations);
        }
    }

    /**
     * Checks every file, the manifest's spec, then parses the whole model with {@link ModelLoader} and checks its
     * counts.
     *
     * @param files  every file of the model by clean path
     * @param source the same files as a source
     */
    public LoadedModel validate(Map<String, byte[]> files, ModelSource source) throws IOException {
        checkFiles(files);
        LoadedModel model;
        try {
            model = ModelLoader.load(source);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException | StackOverflowError e) {
            throw new IOException("unreadable model: " + e, e);
        }
        checkModel(model);
        return model;
    }

    /**
     * Everything but the parse: file count, sizes, each file by kind and the manifest's spec. What a client checks
     * before handing a server model to its loader.
     */
    public void checkFiles(Map<String, byte[]> files) throws IOException {
        if (files.size() > maxFiles) {
            throw new IOException(files.size() + " files, over " + maxFiles);
        }
        long total = 0;
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            total += f.getValue().length;
            checkFile(f.getKey(), f.getValue());
        }
        if (total > maxModelBytes) {
            throw new IOException(total + " bytes, over " + maxModelBytes);
        }
        byte[] manifest = files.get("ysm.json");
        if (manifest == null) {
            throw new IOException("no ysm.json");
        }
        checkSpec(manifest);
    }
}
