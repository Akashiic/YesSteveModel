package com.akashic.futura.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

/**
 * The client's options, in {@code config/futura3d/client.properties} (written with the defaults on first start, so
 * it can be found and edited). A change to the file is picked up while the game runs (checked at most once a second).
 * No Minecraft classes.
 *
 * <ul>
 * <li>{@code view_locator_camera} (default {@code true}): in first person, the camera height follows the model's
 * {@code ViewLocator} bone, or its {@code Head}.</li>
 * <li>{@code print_animation_roulette_msg} (default {@code false}): a chat line when the roulette plays an animation
 * (upstream's {@code PrintAnimationRouletteMsg}).</li>
 * <li>{@code own_skin_default} (default {@code true}): a player who chose no model is drawn with his own Minecraft
 * skin on the Steve or Alex model; {@code false} draws the YSM default model instead.</li>
 * </ul>
 */
public final class ClientOptions {

    public static final String VIEW_LOCATOR_CAMERA = "view_locator_camera";
    public static final String PRINT_ANIMATION_ROULETTE_MSG = "print_animation_roulette_msg";
    public static final String OWN_SKIN_DEFAULT = "own_skin_default";

    /** The options of the running game (defaults until {@link #init} is called). */
    public static final ClientOptions INSTANCE = new ClientOptions(null);

    private File file;
    private long loadedStamp = Long.MIN_VALUE;
    private long lastCheck;
    private volatile boolean viewLocatorCamera = true;
    private volatile boolean printRouletteMessage = false;
    private volatile boolean ownSkinDefault = true;

    ClientOptions(File file) {
        this.file = file;
    }

    /** Points at the file (writing the defaults if it is missing) and reads it. */
    public synchronized void init(File file) {
        this.file = file;
        if (!file.isFile()) {
            try {
                save();
            } catch (IOException e) {
                // Defaults stay in force; nothing else to do without a config folder.
            }
        }
        reload();
    }

    public boolean viewLocatorCamera() {
        refresh();
        return viewLocatorCamera;
    }

    public boolean ownSkinDefault() {
        refresh();
        return ownSkinDefault;
    }

    public boolean printRouletteMessage() {
        refresh();
        return printRouletteMessage;
    }

    /** Re-reads the file if it changed since the last read (at most once a second). */
    void refresh() {
        long now = System.currentTimeMillis();
        if (file == null || now - lastCheck < 1000) {
            return;
        }
        lastCheck = now;
        if (file.lastModified() != loadedStamp) {
            reload();
        }
    }

    synchronized void reload() {
        if (file == null) {
            return;
        }
        loadedStamp = file.lastModified();
        Properties p = new Properties();
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException | IllegalArgumentException e) {
                p.clear();
            }
        }
        viewLocatorCamera = bool(p.getProperty(VIEW_LOCATOR_CAMERA), true);
        printRouletteMessage = bool(p.getProperty(PRINT_ANIMATION_ROULETTE_MSG), false);
        ownSkinDefault = bool(p.getProperty(OWN_SKIN_DEFAULT), true);
    }

    synchronized void save() throws IOException {
        if (file == null) {
            return;
        }
        File dir = file.getAbsoluteFile()
            .getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir);
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            w.write("# 3D Futura Engine: client options\n");
            w.write("# In first person, the camera height follows the model (its ViewLocator bone, else its Head)\n");
            w.write(VIEW_LOCATOR_CAMERA + "=" + viewLocatorCamera + "\n");
            w.write("# A chat line when the animation roulette plays an animation\n");
            w.write(PRINT_ANIMATION_ROULETTE_MSG + "=" + printRouletteMessage + "\n");
            w.write("# Without a chosen model: your own skin on the Steve/Alex model (false: the YSM default model)\n");
            w.write(OWN_SKIN_DEFAULT + "=" + ownSkinDefault + "\n");
            w.flush();
        }
        if (file.exists() && !file.delete() || !tmp.renameTo(file)) {
            throw new IOException("Could not write " + file);
        }
        loadedStamp = file.lastModified();
    }

    static boolean bool(String value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        String v = value.trim()
            .toLowerCase(Locale.ROOT);
        switch (v) {
            case "true":
            case "on":
            case "yes":
            case "1":
                return true;
            case "false":
            case "off":
            case "no":
            case "0":
                return false;
            default:
                return fallback;
        }
    }
}
