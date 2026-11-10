package com.akashic.futura.dev;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.util.ScreenShotHelper;

import com.akashic.futura.Futura3D;
import com.akashic.futura.client.ModelSelection;
import com.akashic.futura.client.net.ClientNetwork;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Development only, never in the release jar: with {@code connect <host>:<port> [model]} in
 * {@code run/client/futura3d-harness}, joins a dedicated server (the real wire path, which singleplayer's in-memory
 * connection skips), lets the model catalog download, selects the model with {@code /futura3d set} (the player must be
 * an operator there), plays an emote, takes screenshots and quits. Writes {@code futura3d-harness.txt}.
 */
public final class ConnectHarness {

    private final String host;
    private final int port;
    private final String model;
    private final PrintWriter out;
    private boolean launched;
    private int waiting;
    private int ticks;
    private boolean shot;
    private String shotName;
    private boolean done;
    private boolean keepScreen;

    private ConnectHarness(String host, int port, String model) throws FileNotFoundException {
        this.host = host;
        this.port = port;
        this.model = model;
        out = new PrintWriter(new File(Minecraft.getMinecraft().mcDataDir, "futura3d-harness.txt"));
    }

    /** @param words {@code connect host:port [model]} */
    static void install(String[] words) throws FileNotFoundException {
        String[] hp = (words.length > 1 ? words[1] : "127.0.0.1:25565").split(":");
        ConnectHarness h = new ConnectHarness(
            hp[0],
            hp.length > 1 ? Integer.parseInt(hp[1]) : 25565,
            words.length > 2 ? words[2] : "futura_test");
        FMLCommonHandler.instance()
            .bus()
            .register(h);
    }

    private void report(String line) {
        Futura3D.LOG.info("[harness] {}", line);
        out.println(line);
        out.flush();
    }

    private void command(String cmd) {
        report("command " + cmd);
        Minecraft.getMinecraft().thePlayer.sendChatMessage(cmd);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.phase != TickEvent.Phase.END || done) {
            return;
        }
        if (!launched) {
            if (mc.currentScreen instanceof GuiMainMenu) {
                launched = true;
                mc.gameSettings.pauseOnLostFocus = false;
                report("connecting to " + host + ":" + port);
                // arms the latch FML's handshake waits on (connectToServer needs the multiplayer screen's data), then
                // connects
                cpw.mods.fml.client.FMLClientHandler.instance()
                    .connectToRealmsServer(host, port);
                mc.displayGuiScreen(
                    new net.minecraft.client.multiplayer.GuiConnecting(mc.currentScreen, mc, host, port));
            }
            return;
        }
        if (mc.thePlayer == null) {
            if (++waiting > 20 * 120) {
                report("never joined");
                finish(mc);
            }
            return;
        }
        if (mc.currentScreen != null && !keepScreen) {
            mc.displayGuiScreen(null);
        }
        int t = ++ticks;
        String name = mc.thePlayer.getCommandSenderName();
        mc.gameSettings.thirdPersonView = 2;
        mc.thePlayer.rotationPitch = 10;
        if (t == 20) {
            command("/time set 6000");
            command("/weather clear");
        } else if (t == 60) {
            report("t=3s " + ClientNetwork.INSTANCE.status());
            command("/futura3d list");
            command("/futura3d set " + name + " " + model);
        } else if (t == 200) {
            report("t=10s " + ClientNetwork.INSTANCE.status());
            String key;
            try {
                key = ModelSelection.INSTANCE.keyOf(mc.thePlayer.getUniqueID());
            } catch (UnsupportedOperationException e) {
                key = "(ModelSelection not implemented yet)";
            }
            report("local player's key " + key);
            String[] cached = new File(mc.mcDataDir, "futura3d/cache").list();
            report("cache files " + (cached == null ? 0 : cached.length));
            shot("connect-model");
        } else if (t == 220) {
            command("/futura3d play " + name + " extra0");
        } else if (t == 230) {
            report(
                "emote playing on the local animator: "
                    + com.akashic.futura.client.PlayerModels.INSTANCE.animator(mc.thePlayer)
                        .isPlayingExtra());
        } else if (t == 260) {
            shot("connect-emote");
        } else if (t == 280) {
            command("/futura3d play " + name + " stop");
            command("/futura3d set " + name + " default");
            command("/futura3d auth " + name + " add steve");
        } else if (t == 290) {
            report(
                "emote playing after stop: " + com.akashic.futura.client.PlayerModels.INSTANCE.animator(mc.thePlayer)
                    .isPlayingExtra());
            command("/futura3d reload");
        } else if (t == 360) {
            report("t=18s " + ClientNetwork.INSTANCE.status());
            command("/futura3d auth " + name + " remove steve");
        } else if (t == 380) {
            // permission nodes: without Bukkit an operator has every node and a player none; the server checks
            // again every few seconds and tells the client (protocol 4)
            report("as operator: " + locks());
            command("/deop " + name);
        } else if (t == 520) {
            report("after /deop: " + locks());
            ModelSelection.INSTANCE.requestLocal(LOCKED, null);
            keepScreen = true;
            mc.displayGuiScreen(new com.akashic.futura.client.gui.ModelPickerScreen().focus(LOCKED, null));
        } else if (t == 560) {
            shot("connect-picker-locked");
        } else if (t == 580) {
            keepScreen = false;
            report("key after asking for a locked model: " + ModelSelection.INSTANCE.keyOf(mc.thePlayer.getUniqueID()));
            ModelSelection.INSTANCE.requestLocal(FREE, null);
        } else if (t == 620) {
            report("key after asking for Steve (free): " + ModelSelection.INSTANCE.keyOf(mc.thePlayer.getUniqueID()));
            ModelSelection.INSTANCE.requestLocal(null, null);
            finish(mc);
        }
    }

    private static final String LOCKED = "builtin:wine_fox/18_wedding";
    private static final String FREE = "builtin:misc/2_steve";

    private static String locks() {
        com.akashic.futura.client.ModelRegistry r = com.akashic.futura.client.ModelRegistry.INSTANCE;
        return LOCKED + " locked=" + r.isLocked(LOCKED) + ", " + FREE + " locked=" + r.isLocked(FREE);
    }

    private void shot(String name) {
        shot = true;
        shotName = name;
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !shot) {
            return;
        }
        shot = false;
        Minecraft mc = Minecraft.getMinecraft();
        ScreenShotHelper.saveScreenshot(
            mc.mcDataDir,
            "futura3d-" + shotName + ".png",
            mc.displayWidth,
            mc.displayHeight,
            mc.getFramebuffer());
        report("shot " + shotName);
    }

    private void finish(Minecraft mc) {
        done = true;
        report("done");
        out.close();
        mc.shutdown();
    }
}
