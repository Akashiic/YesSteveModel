/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/format/parser/pojo/manifest/models/PBRTextureSet.java, Apache License 2.0.
 * Changes: package renamed; Gson adapters registered in FormatGson instead of @JsonAdapter (Minecraft 1.7.10 ships Gson
 * 2.2.4); Java 8 library APIs.
 */
package com.akashic.futura.format.pojo.manifest.models;

import java.io.IOException;

import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

public class PBRTextureSet {

    public String uv = "";
    public String normal;
    public String specular;

    public PBRTextureSet() {}

    public PBRTextureSet(String uv) {
        this.uv = uv;
    }

    public static final class Adapter extends TypeAdapter<PBRTextureSet> {

        @Override
        public PBRTextureSet read(JsonReader in) throws IOException {
            JsonToken token = in.peek();
            if (token == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            if (token == JsonToken.STRING) {
                return new PBRTextureSet(in.nextString());
            }
            if (token != JsonToken.BEGIN_OBJECT) {
                throw new JsonParseException("Expected string or object for ModelTexture but was " + token);
            }

            PBRTextureSet texture = new PBRTextureSet();
            in.beginObject();
            while (in.hasNext()) {
                switch (in.nextName()) {
                    case "uv":
                        texture.uv = in.nextString();
                        break;
                    case "normal":
                        texture.normal = readNullableString(in);
                        break;
                    case "specular":
                        texture.specular = readNullableString(in);
                        break;
                    default:
                        in.skipValue();
                        break;
                }
            }
            in.endObject();
            return texture;
        }

        @Override
        public void write(JsonWriter out, PBRTextureSet value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }

            out.beginObject();
            out.name("uv")
                .value(value.uv);
            if (value.normal != null) {
                out.name("normal")
                    .value(value.normal);
            }
            if (value.specular != null) {
                out.name("specular")
                    .value(value.specular);
            }
            out.endObject();
        }

        private static String readNullableString(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return in.nextString();
        }
    }
}
