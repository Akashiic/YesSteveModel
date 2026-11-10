package com.akashic.futura.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.MovingSound;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.audio.SoundManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

import com.akashic.futura.Futura3D;
import com.akashic.futura.anim.SoundBackend;
import com.akashic.futura.model.OggKind;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import paulscode.sound.SoundSystem;
import paulscode.sound.SoundSystemConfig;

/**
 * Plays model sounds through 1.7.10's sound system. A model's Ogg Vorbis files are not resource-pack sounds, so they go
 * straight to paulscode's {@link SoundSystem} (the one inside {@link SoundManager}) with a URL that serves the bytes
 * read from the model; a name with {@code :} is a vanilla sound event, played as a moving {@link ISound}. Sounds follow
 * the player that started them, use the "Players" volume, stop when he goes away, and are limited per player and in
 * total. Ogg Opus files (what upstream's converter writes) are skipped with a log line: 1.7.10 only decodes Vorbis.
 */
public final class ModelSounds {

    public static final ModelSounds INSTANCE = new ModelSounds();

    /** Sounds one player may have at once (upstream has no limit). */
    public static final int MAX_PER_PLAYER = 6;
    /** Model sounds at once in total (paulscode has 28 normal and 4 streaming channels, shared with vanilla). */
    public static final int MAX_TOTAL = 16;
    /** Files above this stream from the URL instead of being decoded into memory. */
    private static final int STREAM_ABOVE = 256 * 1024;
    /** Model sound files larger than this are not played. */
    private static final int MAX_FILE = 16 << 20;

    private final List<Playing> playing = new ArrayList<>();
    private final Map<ClientModel, Map<String, byte[]>> bytes = new IdentityHashMap<>();
    private final Map<ClientModel, Set<String>> loadedIds = new IdentityHashMap<>();
    private final Set<String> warned = new HashSet<>();
    private final Map<ClientModel, Integer> modelIds = new IdentityHashMap<>();
    private int nextModelId;
    private int nextSource;
    private int tick;
    private Field managerField;
    private Field systemField;
    private Field loadedField;
    private boolean reflectionFailed;
    /** Sounds the sound system accepted since start-up (the harness reads it: headless has no audio). */
    private int started;

    private ModelSounds() {}

    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(INSTANCE);
    }

    /** The sound backend of a player drawn with a model. */
    public SoundBackend backend(Entity player, ClientModel model) {
        return (sound, volume, pitch, loop) -> play(player, model, sound, volume, pitch, loop);
    }

    /** How many sounds the sound system took since start-up. */
    public int started() {
        return started;
    }

    /** Sounds playing now. */
    public int playing() {
        return playing.size();
    }

    private abstract static class Playing implements SoundBackend.SoundHandle {

        final Entity entity;
        final boolean loop;
        final int startTick;
        boolean stopped;

        Playing(Entity entity, boolean loop, int startTick) {
            this.entity = entity;
            this.loop = loop;
            this.startTick = startTick;
        }

        @Override
        public boolean isStopped() {
            return stopped;
        }

        /** @return false once it ended by itself */
        abstract boolean update(int tick);

        abstract void halt();

        @Override
        public void stop() {
            if (!stopped) {
                stopped = true;
                halt();
            }
        }
    }

    private SoundBackend.SoundHandle play(Entity entity, ClientModel model, String sound, float volume, float pitch,
        boolean loop) {
        if (entity == null || sound == null || sound.isEmpty()) {
            return null;
        }
        int mine = 0;
        for (Playing p : playing) {
            if (p.entity == entity && !p.stopped) {
                mine++;
            }
        }
        if (mine >= MAX_PER_PLAYER || playing.size() >= MAX_TOTAL) {
            return null;
        }
        Playing handle = sound.indexOf(':') >= 0 ? vanilla(entity, sound, volume, pitch, loop)
            : model(entity, model, sound, volume, pitch, loop);
        if (handle != null) {
            playing.add(handle);
            started++;
        }
        return handle;
    }

    // ---- vanilla sound events ----

    /** A vanilla sound event that follows an entity. */
    private static final class EntitySound extends MovingSound {

        private final Entity entity;

        EntitySound(ResourceLocation location, Entity entity, float volume, float pitch, boolean loop) {
            super(location);
            this.entity = entity;
            this.volume = volume;
            this.field_147663_c = pitch;
            this.repeat = loop;
            follow();
        }

        private void follow() {
            xPosF = (float) entity.posX;
            yPosF = (float) entity.posY;
            zPosF = (float) entity.posZ;
        }

        @Override
        public void update() {
            if (entity.isDead) {
                donePlaying = true;
            } else {
                follow();
            }
        }

        void finish() {
            donePlaying = true;
        }
    }

    private Playing vanilla(Entity entity, String sound, float volume, float pitch, boolean loop) {
        SoundHandler handler = Minecraft.getMinecraft()
            .getSoundHandler();
        if (handler == null) {
            return null;
        }
        ResourceLocation location = new ResourceLocation(sound);
        if (handler.getSound(location) == null) {
            warnOnce("vanilla:" + sound, "Unknown sound event {} asked by a model", sound);
            return null;
        }
        EntitySound instance = new EntitySound(location, entity, Math.min(volume, 16f), clampPitch(pitch), loop);
        handler.playSound(instance);
        if (warned.add("started:" + sound)) {
            Futura3D.LOG.info("Vanilla sound {} asked by a model handed to the sound handler", sound);
        }
        return new Playing(entity, loop, tick) {

            @Override
            boolean update(int now) {
                return now - startTick < 10 || handler.isSoundPlaying(instance);
            }

            @Override
            void halt() {
                instance.finish();
                handler.stopSound(instance);
            }
        };
    }

    // ---- model files through paulscode ----

    private Playing model(Entity entity, ClientModel model, String name, float volume, float pitch, boolean loop) {
        if (model == null || model.released()) {
            return null;
        }
        String path = model.loaded.sounds.get(name);
        if (path == null) {
            warnOnce(model.key + "/" + name, "Model {} has no sound {}", model.key, name);
            return null;
        }
        byte[] data = read(model, name, path);
        if (data == null) {
            return null;
        }
        SoundSystem system = soundSystem();
        if (system == null) {
            return null;
        }
        float category = Minecraft.getMinecraft().gameSettings.getSoundLevel(SoundCategory.PLAYERS);
        float gain = Math.max(0, Math.min(1, volume * category));
        String identifier = "futura3d_" + modelId(model) + "_" + Integer.toHexString(name.hashCode()) + ".ogg";
        String source = "futura3d_" + (nextSource++);
        URL url;
        try {
            url = SoundBytesUrl.of(identifier, data);
        } catch (IOException e) {
            return null;
        }
        float distance = 16f * Math.max(volume, 1f);
        float x = (float) entity.posX, y = (float) entity.posY, z = (float) entity.posZ;
        if (data.length > STREAM_ABOVE) {
            system.newStreamingSource(
                false,
                source,
                url,
                identifier,
                loop,
                x,
                y,
                z,
                SoundSystemConfig.ATTENUATION_LINEAR,
                distance);
        } else {
            system.newSource(
                false,
                source,
                url,
                identifier,
                loop,
                x,
                y,
                z,
                SoundSystemConfig.ATTENUATION_LINEAR,
                distance);
            loadedIds.computeIfAbsent(model, k -> new HashSet<>())
                .add(identifier);
        }
        system.setPitch(source, clampPitch(pitch));
        system.setVolume(source, gain);
        system.play(source);
        if (warned.add("started:" + model.key + "/" + name)) {
            // once per sound and model: the sound system took it (headless runs have no audio to hear)
            Futura3D.LOG.info(
                "Model sound {} of {} handed to the sound system as {} ({} bytes, {})",
                name,
                model.key,
                source,
                data.length,
                data.length > STREAM_ABOVE ? "streamed" : "decoded");
        }
        return new Playing(entity, loop, tick) {

            @Override
            boolean update(int now) {
                SoundSystem s = soundSystem();
                if (s == null) {
                    return false;
                }
                if (!loop && now - startTick > 10 && !s.playing(source)) {
                    s.removeSource(source);
                    return false;
                }
                s.setPosition(source, (float) entity.posX, (float) entity.posY, (float) entity.posZ);
                return true;
            }

            @Override
            void halt() {
                SoundSystem s = soundSystem();
                if (s != null) {
                    s.stop(source);
                    s.removeSource(source);
                }
            }
        };
    }

    private static float clampPitch(float pitch) {
        // paulscode/OpenAL accept 0.5 to 2 (upstream clamps to 0.001..1000)
        return Math.max(0.5f, Math.min(2f, pitch));
    }

    private int modelId(ClientModel model) {
        Integer id = modelIds.get(model);
        if (id == null) {
            id = nextModelId++;
            modelIds.put(model, id);
        }
        return id;
    }

    /** The file's bytes, cached per model; null (logged once) when unreadable, too large or not Ogg Vorbis. */
    private byte[] read(ClientModel model, String name, String path) {
        Map<String, byte[]> cache = bytes.computeIfAbsent(model, k -> new HashMap<>());
        if (cache.containsKey(name)) {
            return cache.get(name);
        }
        byte[] data = null;
        try (InputStream in = model.loaded.source.open(path)) {
            if (in != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0 && out.size() <= MAX_FILE) {
                    out.write(buffer, 0, n);
                }
                data = out.size() <= MAX_FILE ? out.toByteArray() : null;
            }
        } catch (IOException e) {
            Futura3D.LOG.warn("Could not read sound {} of {}: {}", path, model.key, e.toString());
        }
        if (data != null) {
            String kind = OggKind.of(data);
            if (!OggKind.VORBIS.equals(kind)) {
                Futura3D.LOG.info(
                    "Skipped sound {} of {}: {} (only Ogg Vorbis plays on 1.7.10)",
                    path,
                    model.key,
                    OggKind.OPUS.equals(kind) ? "Ogg Opus" : "not an Ogg Vorbis file");
                data = null;
            }
        }
        cache.put(name, data);
        return data;
    }

    private void warnOnce(String key, String message, Object... args) {
        if (warned.add(key)) {
            Futura3D.LOG.warn(message, args);
        }
    }

    /** The sound system inside the sound manager, or null while sounds are off. */
    private SoundSystem soundSystem() {
        if (reflectionFailed) {
            return null;
        }
        try {
            if (managerField == null) {
                managerField = ReflectionHelper.findField(SoundHandler.class, "sndManager", "field_147694_f");
                systemField = ReflectionHelper.findField(SoundManager.class, "sndSystem", "field_148620_e");
                loadedField = ReflectionHelper.findField(SoundManager.class, "loaded", "field_148617_f");
            }
            SoundHandler handler = Minecraft.getMinecraft()
                .getSoundHandler();
            Object manager = handler == null ? null : managerField.get(handler);
            if (manager == null || !loadedField.getBoolean(manager)) {
                return null;
            }
            return (SoundSystem) systemField.get(manager);
        } catch (ReflectiveOperationException | RuntimeException e) {
            reflectionFailed = true;
            Futura3D.LOG.error("Model sounds disabled: cannot reach the sound system", e);
            return null;
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tick++;
        Minecraft mc = Minecraft.getMinecraft();
        for (Iterator<Playing> it = playing.iterator(); it.hasNext();) {
            Playing p = it.next();
            if (p.stopped) {
                it.remove();
            } else if (p.entity.isDead || mc.theWorld == null || p.entity.worldObj != mc.theWorld || !p.update(tick)) {
                p.stop();
                it.remove();
            }
        }
        if (tick % 200 == 0) {
            forgetReleasedModels();
        }
    }

    /** Frees the decoded buffers and bytes of models that were released. */
    private void forgetReleasedModels() {
        SoundSystem system = soundSystem();
        for (Iterator<Map.Entry<ClientModel, Map<String, byte[]>>> it = bytes.entrySet()
            .iterator(); it.hasNext();) {
            ClientModel model = it.next()
                .getKey();
            if (model.released()) {
                it.remove();
                modelIds.remove(model);
                Set<String> ids = loadedIds.remove(model);
                if (system != null && ids != null) {
                    for (String id : ids) {
                        system.unloadSound(id);
                    }
                }
            }
        }
    }
}
