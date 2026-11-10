package com.akashic.futura.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;

import com.akashic.futura.Futura3D;
import com.akashic.futura.ModNetwork;
import com.akashic.futura.net.Message;
import com.akashic.futura.net.MessageCodec;
import com.akashic.futura.net.ProtocolException;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/**
 * The Forge side of {@link ModelServer}: one per running server (dedicated, or the integrated one of singleplayer).
 * Only common Minecraft classes: the dedicated server loads this.
 */
public final class ForgeModelServer implements ModelServer.Network, ModelServer.Log {

    private static ForgeModelServer current;
    private static File configDir;

    private final ModelServer server;

    private ForgeModelServer(File dir) {
        ModelServer.Settings settings = readSettings(new File(dir, "server.properties"));
        server = new ModelServer(new File(dir, "models"), new File(dir, "players"), this, this, settings);
    }

    /** The running server's models, or null. */
    public static ModelServer current() {
        return current == null ? null : current.server;
    }

    /** Pre-init: where {@code futura3d/} goes (the game's {@code config/}). */
    public static void setConfigDir(File config) {
        configDir = new File(config, "futura3d");
    }

    /** Init, both sides: the packet, login and tick handlers (they do nothing while no server runs). */
    public static void registerEvents() {
        Events events = new Events();
        ModNetwork.channel()
            .register(events);
        FMLCommonHandler.instance()
            .bus()
            .register(events);
        MinecraftForge.EVENT_BUS.register(events);
    }

    public static void start(FMLServerStartingEvent event) {
        if (current != null) {
            current.server.shutdown();
        }
        current = new ForgeModelServer(configDir);
        current.server.loadNow();
        event.registerServerCommand(new FuturaCommand());
    }

    public static void stop() {
        if (current != null) {
            current.server.shutdown();
            current = null;
        }
    }

    // ---- Settings ----

    private static ModelServer.Settings readSettings(File file) {
        ModelServer.Settings s = new ModelServer.Settings();
        Properties p = new Properties();
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                p.load(in);
            } catch (IOException e) {
                Futura3D.LOG.warn("Could not read {}", file, e);
            }
        }
        s.bytesPerSecond = (int) number(p, "bytesPerSecondPerPlayer", s.bytesPerSecond, 4096, 64L << 20);
        s.maxTotalBytes = number(p, "maxTotalBytes", s.maxTotalBytes, 1 << 20, 8L << 30);
        s.limits.maxModelBytes = number(p, "maxModelBytes", s.limits.maxModelBytes, 1 << 16, 64L << 20);
        s.limits.maxFileBytes = (int) number(p, "maxFileBytes", s.limits.maxFileBytes, 1 << 12, 16L << 20);
        s.limits.maxFiles = (int) number(p, "maxFiles", s.limits.maxFiles, 4, 512);
        s.limits.maxImageSide = (int) number(p, "maxImageSide", s.limits.maxImageSide, 16, 8192);
        s.limits.maxBones = (int) number(p, "maxBones", s.limits.maxBones, 16, 65536);
        s.limits.maxCubes = (int) number(p, "maxCubes", s.limits.maxCubes, 16, 1 << 20);
        if (!file.isFile()) {
            file.getParentFile()
                .mkdirs();
            Properties defaults = new Properties();
            defaults.setProperty("bytesPerSecondPerPlayer", String.valueOf(s.bytesPerSecond));
            defaults.setProperty("maxTotalBytes", String.valueOf(s.maxTotalBytes));
            defaults.setProperty("maxModelBytes", String.valueOf(s.limits.maxModelBytes));
            defaults.setProperty("maxFileBytes", String.valueOf(s.limits.maxFileBytes));
            defaults.setProperty("maxFiles", String.valueOf(s.limits.maxFiles));
            defaults.setProperty("maxImageSide", String.valueOf(s.limits.maxImageSide));
            defaults.setProperty("maxBones", String.valueOf(s.limits.maxBones));
            defaults.setProperty("maxCubes", String.valueOf(s.limits.maxCubes));
            try (OutputStream out = new FileOutputStream(file)) {
                defaults.store(out, "3D Futura Engine server: model transfer and limits (a reload reads models again)");
            } catch (IOException e) {
                Futura3D.LOG.warn("Could not write {}", file, e);
            }
        }
        return s;
    }

    private static long number(Properties p, String key, long fallback, long min, long max) {
        String v = p.getProperty(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Long.parseLong(v.trim())));
        } catch (NumberFormatException e) {
            Futura3D.LOG.warn("server.properties: {} is not a number: {}", key, v);
            return fallback;
        }
    }

    // ---- Network and log ----

    static EntityPlayerMP player(UUID id) {
        MinecraftServer mc = MinecraftServer.getServer();
        if (mc == null) {
            return null;
        }
        for (Object o : mc.getConfigurationManager().playerEntityList) {
            EntityPlayerMP p = (EntityPlayerMP) o;
            if (p.getUniqueID()
                .equals(id)) {
                return p;
            }
        }
        return null;
    }

    @Override
    public void send(UUID id, Message message) {
        EntityPlayerMP p = player(id);
        if (p != null && p.playerNetServerHandler != null) {
            ModNetwork.channel()
                .sendTo(ModNetwork.packet(message), p);
        }
    }

    @Override
    public Collection<UUID> trackers(UUID id) {
        List<UUID> out = new ArrayList<>();
        EntityPlayerMP p = player(id);
        if (p != null && p.worldObj instanceof WorldServer) {
            for (EntityPlayer t : ((WorldServer) p.worldObj).getEntityTracker()
                .getTrackingPlayers(p)) {
                out.add(t.getUniqueID());
            }
        }
        return out;
    }

    @Override
    public void info(String line) {
        Futura3D.LOG.info(line);
    }

    @Override
    public void warn(String line) {
        Futura3D.LOG.warn(line);
    }

    /** Packet, login and tick handlers, registered once. */
    public static final class Events {

        @SubscribeEvent
        public void onPacket(FMLNetworkEvent.ServerCustomPacketEvent event) {
            ForgeModelServer s = current;
            if (s == null || !(event.handler instanceof NetHandlerPlayServer)) {
                return;
            }
            EntityPlayerMP player = ((NetHandlerPlayServer) event.handler).playerEntity;
            if (player == null) {
                return;
            }
            Message m;
            try {
                m = MessageCodec.decodeFromClient(ModNetwork.payload(event.packet));
            } catch (ProtocolException e) {
                if (s.server.protocolError(player.getUniqueID())) {
                    Futura3D.LOG.warn(
                        "Ignoring model messages from {} after repeated protocol errors (last: {})",
                        player.getCommandSenderName(),
                        e.getMessage());
                }
                return;
            }
            s.server.received(player.getUniqueID(), m);
        }

        @SubscribeEvent
        public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
            if (current != null && event.player instanceof EntityPlayerMP) {
                current.server.join(event.player.getUniqueID());
            }
        }

        @SubscribeEvent
        public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            if (current != null) {
                current.server.leave(event.player.getUniqueID());
            }
        }

        /** After the spawn packet of the target: what he plays and his roaming variables. */
        @SubscribeEvent
        public void onStartTracking(net.minecraftforge.event.entity.player.PlayerEvent.StartTracking event) {
            if (current != null && event.entityPlayer instanceof EntityPlayerMP
                && event.target instanceof EntityPlayerMP) {
                current.server.startTracking(event.entityPlayer.getUniqueID(), event.target.getUniqueID());
            }
        }

        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent event) {
            if (current != null && event.phase == TickEvent.Phase.END) {
                current.server.tick();
            }
        }
    }
}
