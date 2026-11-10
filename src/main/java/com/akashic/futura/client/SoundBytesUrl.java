package com.akashic.futura.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

/**
 * A {@code futura3d:} URL that serves bytes held in memory: how a model's sound file reaches paulscode's codecs, which
 * read sounds from URLs (vanilla's point into the resource packs). No Minecraft classes.
 */
public final class SoundBytesUrl {

    private SoundBytesUrl() {}

    /** @param identifier a name ending in {@code .ogg} or {@code .opus} (paulscode picks the codec by the extension) */
    public static URL of(String identifier, byte[] data) throws IOException {
        return new URL(null, "futura3d:" + identifier, new Handler(data));
    }

    private static final class Handler extends URLStreamHandler {

        private final byte[] data;

        Handler(byte[] data) {
            this.data = data;
        }

        @Override
        protected URLConnection openConnection(URL url) {
            return new URLConnection(url) {

                @Override
                public void connect() {}

                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream(data);
                }
            };
        }
    }
}
