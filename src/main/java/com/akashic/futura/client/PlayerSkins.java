package com.akashic.futura.client;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.akashic.futura.Futura3D;
import com.akashic.futura.client.render.GeometryRenderer;
import com.akashic.futura.client.render.ModelTexture;
import com.akashic.futura.geo.FacePartition;
import com.akashic.futura.model.SkinImages;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The default look of a player who chose no model: his own Minecraft skin on the Steve or Alex model (the built-in
 * {@code misc/2_steve} and {@code misc/1_alex}, YSM skeletons whose UVs follow the 64x64 skin layout), Alex when the
 * skin is slim. The skin texture comes from the player's profile ({@code textures} property, read without the network
 * like vanilla does); the image itself is downloaded here, because 1.7.10 crops what it downloads to the old 64x32
 * layout, and normalized by {@link SkinImages}. Without a skin (an offline-mode server without a skin plugin) the
 * player gets the Steve or Alex model with its own texture, chosen by UUID like Minecraft 1.8+.
 */
public final class PlayerSkins {

    public static final PlayerSkins INSTANCE = new PlayerSkins();

    /** The built-in models the skins are drawn on. */
    public static final String STEVE = ModelRegistry.BUILTIN + "misc/2_steve";
    public static final String ALEX = ModelRegistry.BUILTIN + "misc/1_alex";

    /** Entries of players not drawn for this long are freed. */
    private static final long FORGET_MS = 60_000;
    private static final int MAX_SKIN_BYTES = 1 << 20;

    private final Map<UUID, Entry> entries = new HashMap<>();
    private ExecutorService worker;
    /** Set by the harness for comparisons; null follows {@code own_skin_default} in the client options. */
    private Boolean override;
    private long ticks;

    private static final class Entry {

        final UUID player;
        /** Set by the worker: whether the skin is slim, and its normalized 64x64 image (null: no skin). */
        volatile boolean resolved;
        volatile boolean slim;
        volatile BufferedImage image;
        /** Render thread only: the uploaded skin and the renderer for the model it was baked against. */
        ModelTexture texture;
        ClientModel bakedFor;
        GeometryRenderer renderer;
        long lastSeen;

        Entry(UUID player) {
            this.player = player;
            this.slim = SkinImages.defaultSlim(player);
        }

        void release() {
            if (texture != null) {
                texture.release();
                texture = null;
            }
            if (renderer != null) {
                renderer.delete();
                renderer = null;
            }
            bakedFor = null;
        }
    }

    private PlayerSkins() {}

    /** Off: players without a model get the YSM default model instead; null: the client option decides. */
    public void setEnabled(Boolean enabled) {
        this.override = enabled;
    }

    public boolean enabled() {
        return override != null ? override : ClientOptions.INSTANCE.ownSkinDefault();
    }

    /** The model a player without a chosen model is drawn with: Steve, or Alex for a slim skin. */
    public String modelKey(EntityPlayer player) {
        if (!enabled() || player == null) {
            return ModelRegistry.DEFAULT;
        }
        return entry(player).slim ? ALEX : STEVE;
    }

    /** Whether the player's own skin is ready to draw on {@code model}. */
    public boolean hasSkin(EntityPlayer player, ClientModel model) {
        return enabled() && player != null
            && model != null
            && (STEVE.equals(model.key) || ALEX.equals(model.key))
            && entry(player).image != null;
    }

    /** The player's skin texture (render thread: uploads it on first use), or null if he has none yet. */
    public ResourceLocation texture(EntityPlayer player) {
        Entry e = entry(player);
        BufferedImage image = e.image;
        if (image == null) {
            return null;
        }
        if (e.texture == null) {
            e.texture = new ModelTexture(image);
        }
        return e.texture.location();
    }

    /** The renderer of {@code model} with the face partitions of the player's skin (render thread). */
    public GeometryRenderer renderer(EntityPlayer player, ClientModel model) {
        Entry e = entry(player);
        BufferedImage image = e.image;
        if (image == null) {
            return null;
        }
        if (e.renderer == null || e.bakedFor != model) {
            if (e.renderer != null) {
                e.renderer.delete();
            }
            FacePartition.Baked baked = FacePartition
                .bake(model.loaded.main, FacePartition.Alpha.of(image), model.loaded.manifest.properties.forceCulling);
            e.renderer = new GeometryRenderer(model.loaded.main, baked);
            e.bakedFor = model;
        }
        return e.renderer;
    }

    private Entry entry(EntityPlayer player) {
        UUID id = player.getUniqueID();
        Entry e = entries.get(id);
        if (e == null) {
            e = new Entry(id);
            entries.put(id, e);
            resolve(e, player.getGameProfile(), player == Minecraft.getMinecraft().thePlayer);
        }
        e.lastSeen = System.currentTimeMillis();
        return e;
    }

    private void resolve(Entry e, GameProfile profile, boolean local) {
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "futura3d skins");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
        }
        worker.execute(() -> {
            try {
                MinecraftProfileTexture skin = skinOf(profile, local);
                if (skin == null) {
                    return; // no skin: the model's own texture, Steve or Alex by UUID
                }
                e.slim = SkinImages.slim(skin.getMetadata("model"));
                e.image = download(skin);
            } catch (Exception ex) {
                Futura3D.LOG.warn("Could not read the skin of {}: {}", profile.getName(), ex.toString());
            } finally {
                e.resolved = true;
            }
        });
    }

    private static MinecraftProfileTexture skinOf(GameProfile profile, boolean local) {
        com.mojang.authlib.minecraft.MinecraftSessionService sessions = Minecraft.getMinecraft()
            .func_152347_ac();
        Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> textures;
        try {
            textures = sessions.getTextures(profile, false);
        } catch (RuntimeException e) {
            textures = new HashMap<>();
        }
        if (textures.isEmpty() && local) {
            // as vanilla's SkinManager: the local player's profile may lack its properties
            textures = sessions.getTextures(sessions.fillProfileProperties(profile, false), false);
        }
        return textures.get(MinecraftProfileTexture.Type.SKIN);
    }

    /** The skin image, from our cache ({@code futura3d/skins/<hash>.png}) or downloaded, normalized to 64x64. */
    private static BufferedImage download(MinecraftProfileTexture skin) throws IOException {
        String hash = skin.getHash();
        File dir = new File(Minecraft.getMinecraft().mcDataDir, "futura3d/skins");
        File cached = hash != null && hash.matches("[0-9a-fA-F]{8,128}") ? new File(dir, hash + ".png") : null;
        byte[] bytes = null;
        if (cached != null && cached.isFile()) {
            bytes = Files.readAllBytes(cached.toPath());
        }
        if (bytes == null) {
            HttpURLConnection connection = (HttpURLConnection) new URL(skin.getUrl()).openConnection(
                Minecraft.getMinecraft()
                    .getProxy());
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setInstanceFollowRedirects(true);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() + n > MAX_SKIN_BYTES) {
                        throw new IOException("skin over " + MAX_SKIN_BYTES + " bytes");
                    }
                    out.write(buf, 0, n);
                }
                bytes = out.toByteArray();
            } finally {
                connection.disconnect();
            }
            if (cached != null && (dir.isDirectory() || dir.mkdirs())) {
                File temp = new File(dir, hash + ".tmp");
                Files.write(temp.toPath(), bytes);
                Files.move(temp.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        if (image == null) {
            throw new IOException("not an image");
        }
        int w = image.getWidth();
        int h = image.getHeight();
        int[] pixels = SkinImages.normalize(image.getRGB(0, 0, w, h, null, 0, w), w, h);
        if (pixels == null) {
            throw new IOException("not a 64x64 or 64x32 skin: " + w + "x" + h);
        }
        BufferedImage out = new BufferedImage(SkinImages.SIZE, SkinImages.SIZE, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, SkinImages.SIZE, SkinImages.SIZE, pixels, 0, SkinImages.SIZE);
        return out;
    }

    /** Frees the skins of players not drawn for a minute (render thread). */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++ticks % 200 != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Iterator<Entry> it = entries.values()
            .iterator(); it.hasNext();) {
            Entry e = it.next();
            if (now - e.lastSeen > FORGET_MS) {
                e.release();
                it.remove();
            }
        }
    }

    /**
     * Development (the render harness): gives a player a skin without his profile, or takes it away ({@code null}:
     * no skin, the Steve/Alex model's own texture). The image must be 64x64 or 64x32.
     */
    public void setTestSkin(EntityPlayer player, BufferedImage image, boolean slim) {
        Entry e = entry(player);
        e.release();
        e.slim = image == null ? SkinImages.defaultSlim(player.getUniqueID()) : slim;
        if (image == null) {
            e.image = null;
            return;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        int[] pixels = SkinImages.normalize(image.getRGB(0, 0, w, h, null, 0, w), w, h);
        BufferedImage out = new BufferedImage(SkinImages.SIZE, SkinImages.SIZE, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, SkinImages.SIZE, SkinImages.SIZE, pixels, 0, SkinImages.SIZE);
        e.image = out;
    }

    /** Frees every skin (models released, or leaving the world). */
    public void clear() {
        for (Entry e : entries.values()) {
            e.release();
        }
        entries.clear();
    }
}
