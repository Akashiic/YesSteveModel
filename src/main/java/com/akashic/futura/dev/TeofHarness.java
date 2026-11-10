package com.akashic.futura.dev;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderFish;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ScreenShotHelper;

import org.lwjgl.opengl.GL11;

import com.akashic.futura.Futura3D;
import com.akashic.futura.client.EmoteKeys;
import com.akashic.futura.client.LocalEmotes;
import com.akashic.futura.client.ModelRegistry;
import com.akashic.futura.client.ModelSelection;
import com.akashic.futura.client.PlayerModels;
import com.akashic.futura.client.net.ClientNetwork;
import com.akashic.futura.emote.RouletteMenu;
import com.akashic.futura.model.ModelInfo;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Development only, never in the release jar: two clients on one dedicated server (the TEOF modpack test). With
 * {@code teof <host>:<port> <A|B> <sync dir>} in {@code futura3d-harness}, each client joins, and both walk the same
 * list of stages, meeting at a barrier before each one (a file per client in the shared sync dir: both run on one
 * machine). Client A is the operator: it gives the commands (models, emotes, grants), opens the picker and the
 * roulette with their keys, and measures frame times; client B is the other player each one has to see live. Writes
 * {@code futura3d-harness.txt} and {@code screenshots/futura3d-teof-<role>-<stage>.png}.
 */
public final class TeofHarness {

    private static final int MEASURE_FRAMES = 240;
    private static final int BARRIER_TIMEOUT_TICKS = 20 * 240;

    private final String host;
    private final int port;
    private final boolean driver;
    private final String role;
    private final File syncDir;
    private final PrintWriter out;
    private final List<Stage> stages = new ArrayList<>();

    private boolean launched;
    private int menuTicks;
    private int waiting;
    private int stage = -1;
    private boolean arrived;
    private int barrierTicks;
    private int ticks;
    private boolean shotPending;
    private boolean done;
    private int view = 1;
    private float yaw;
    private boolean keepScreen;

    // spawn of A, the stage layout is around it
    private int x0, y0, z0;

    // frame times
    private boolean measuring;
    private int frames;
    private long lastFrame;
    private final long[] frameNanos = new long[MEASURE_FRAMES];

    private static final class Stage {

        final String name;
        final int settle;
        final Consumer<TeofHarness> action;
        final boolean shotA;
        final boolean shotB;
        final boolean measure;

        Stage(String name, int settle, Consumer<TeofHarness> action, boolean shotA, boolean shotB, boolean measure) {
            this.name = name;
            this.settle = settle;
            this.action = action;
            this.shotA = shotA;
            this.shotB = shotB;
            this.measure = measure;
        }
    }

    private TeofHarness(String host, int port, String role, File syncDir) throws IOException {
        this.host = host;
        this.port = port;
        this.role = role;
        this.driver = role.equals("A");
        this.syncDir = syncDir;
        syncDir.mkdirs();
        out = new PrintWriter(new File(Minecraft.getMinecraft().mcDataDir, "futura3d-harness.txt"));
        stages();
    }

    /** @param words {@code teof host:port A|B syncdir} */
    static void install(String[] words) throws IOException {
        String[] hp = words[1].split(":");
        TeofHarness h = new TeofHarness(hp[0], Integer.parseInt(hp[1]), words[2], new File(words[3]));
        FMLCommonHandler.instance()
            .bus()
            .register(h);
    }

    // ---- The stages ----

    private void stage(String name, int settle, Consumer<TeofHarness> action, boolean shotA, boolean shotB) {
        stages.add(new Stage(name, settle, action, shotA, shotB, false));
    }

    private void measure(String name, Consumer<TeofHarness> action) {
        stages.add(new Stage(name, 60, action, false, false, true));
    }

    private void stages() {
        stage("join", 200, h -> {
            report("status " + ClientNetwork.INSTANCE.status());
            report("gl " + GL11.glGetString(GL11.GL_RENDERER) + " / " + GL11.glGetString(GL11.GL_VERSION));
            reportMixins();
            if (driver) {
                EntityPlayerSP p = Minecraft.getMinecraft().thePlayer;
                x0 = MathHelper.floor_double(p.posX);
                z0 = MathHelper.floor_double(p.posZ);
                y0 = Minecraft.getMinecraft().theWorld.getHeightValue(x0, z0);
                report("spawn " + x0 + " " + y0 + " " + z0 + " (player at y " + p.posY + ")");
                command("/gamemode 1 TesteA");
                command("/gamemode 1 TesteB");
                command("/time set 6000");
                command("/weather clear");
                command("/gamerule doDaylightCycle false");
                command("/gamerule doMobSpawning false");
                place("TesteA", 0, 0);
                place("TesteB", 2, 5);
            }
        }, true, true);
        // A tracks B only once the tracker re-checks it: moving B more than 4 blocks makes the server try again
        stage("nudge", 60, h -> {
            report("others " + others());
            if (driver) {
                place("TesteB", 2, 14);
            }
        }, false, false);
        stage("default", 80, h -> {
            if (driver) {
                place("TesteB", 2, 5);
            }
        }, true, true);
        stage("default-fp", 30, h -> {
            report("others " + others());
            report("keys " + keys());
            view = 0;
        }, true, true);
        stage("roulette", 40, h -> {
            view = 1;
            if (driver) {
                pressKey(EmoteKeys.ROULETTE);
            }
        }, true, false);
        stage("set", 160, h -> {
            if (driver) {
                report("screen " + screen());
                keepScreen = false;
                Minecraft.getMinecraft()
                    .displayGuiScreen(null);
                command("/futura3d list");
                command("/futura3d set TesteA alex");
                command("/futura3d set TesteB steve");
            }
        }, true, true);
        stage("set-fp", 30, h -> {
            report("status " + ClientNetwork.INSTANCE.status());
            report("keys " + keys());
            view = 0;
        }, true, true);
        stage("set-front", 30, h -> view = 2, true, true);
        stage("emote-b", 40, h -> {
            view = 0;
            if (!driver) {
                report("B plays " + firstEmote() + ": " + LocalEmotes.INSTANCE.play(firstEmote()));
            }
        }, true, false);
        stage("emote-b-seen", 5, h -> report("emotes " + emotes()), false, false);
        stage("emote-cmd", 40, h -> {
            if (!driver) {
                LocalEmotes.INSTANCE.stop();
            } else {
                command("/futura3d play TesteA " + firstEmote());
            }
        }, false, true);
        stage("emote-cmd-seen", 5, h -> report("emotes " + emotes()), false, false);
        stage("picker", 60, h -> {
            view = 1;
            if (driver) {
                command("/futura3d play TesteA stop");
                pressKey(EmoteKeys.MODEL_PICKER);
            }
        }, true, false);
        // the picker again, showing only the server's models (the shot for the docs: CC0 models only)
        stage("picker-server", 60, h -> {
            if (driver) {
                report("screen " + screen());
                Minecraft.getMinecraft()
                    .displayGuiScreen(
                        new com.akashic.futura.client.gui.ModelPickerScreen()
                            .only(k -> k.startsWith(ModelRegistry.SERVER))
                            .focus(keyByName("Alex"), null));
            }
        }, true, false);
        stage("picker-select", 80, h -> {
            if (driver) {
                report("screen " + screen());
                String steve = keyByName("Steve");
                report("picker: requestLocal " + steve);
                ModelSelection.INSTANCE.requestLocal(steve, null);
                keepScreen = false;
                Minecraft.getMinecraft()
                    .displayGuiScreen(null);
            }
        }, false, true);
        stage("locked", 60, h -> {
            report("keys " + keys());
            if (driver) {
                String boy = keyByName("Default Boy");
                report("locked " + boy + " " + ModelRegistry.INSTANCE.isLocked(boy) + ": requestLocal");
                ModelSelection.INSTANCE.requestLocal(boy, null);
            }
        }, false, false);
        stage("grant", 80, h -> {
            report("keys after the locked request " + keys());
            if (driver) {
                command("/futura3d auth TesteA add default_boy");
            }
        }, false, false);
        stage("granted", 120, h -> {
            view = driver ? 1 : 0;
            if (driver) {
                String boy = keyByName("Default Boy");
                report("locked " + boy + " " + ModelRegistry.INSTANCE.isLocked(boy) + ": requestLocal");
                ModelSelection.INSTANCE.requestLocal(boy, null);
            }
        }, true, true);
        stage("survival", 40, h -> {
            report("keys " + keys());
            if (driver) {
                command("/gamemode 0 TesteA");
            }
        }, false, false);
        stage("inventory", 40, h -> {
            if (driver) {
                Minecraft mc = Minecraft.getMinecraft();
                keepScreen = true;
                mc.displayGuiScreen(new GuiInventory(mc.thePlayer));
            }
        }, true, false);
        stage("first-person", 40, h -> {
            keepScreen = false;
            Minecraft.getMinecraft()
                .displayGuiScreen(null);
            if (driver) {
                command("/gamemode 1 TesteA");
                view = 0;
            }
        }, false, false);
        stage("first-person-sword", 40, h -> {
            if (driver) {
                hold(new ItemStack(Items.diamond_sword));
            }
        }, true, false);
        stage("rod", 60, h -> {
            if (driver) {
                view = 1;
                hold(new ItemStack(Items.fishing_rod));
            }
        }, false, false);
        stage("rod-cast", 50, h -> {
            if (driver) {
                Minecraft mc = Minecraft.getMinecraft();
                mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem());
            }
        }, true, true);
        stage("rod-front", 20, h -> {
            if (driver) {
                report("hook " + (Minecraft.getMinecraft().thePlayer.fishEntity != null));
                view = 2;
            }
        }, true, false);
        stage("offhand", 40, h -> {
            if (driver) {
                Minecraft mc = Minecraft.getMinecraft();
                mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem());
                hold(new ItemStack(Items.diamond_sword));
                report("backhand off hand set: " + backhand(mc.thePlayer, new ItemStack(Blocks.torch)));
                view = 2;
            }
        }, true, false);
        stage("offhand-fp", 20, h -> view = 0, true, false);
        // the same with the models off: what Backhand draws on the vanilla biped and in first person
        stage("offhand-vanilla", 20, h -> {
            if (driver) {
                PlayerModels.INSTANCE.setEnabled(false);
                view = 2;
            }
        }, true, false);
        stage("offhand-fp-vanilla", 20, h -> view = 0, true, false);
        stage("models-on", 10, h -> {
            if (driver) {
                PlayerModels.INSTANCE.setEnabled(true);
            }
        }, false, false);
        measure("perf-models-2", h -> {
            report("others " + others());
            if (driver) {
                Minecraft mc = Minecraft.getMinecraft();
                backhand(mc.thePlayer, null);
                hold(null);
                view = 1;
            }
        });
        measure("perf-vanilla-2", h -> {
            if (driver) {
                PlayerModels.INSTANCE.setEnabled(false);
            }
        });
        stage("move-b", 60, h -> {
            if (driver) {
                PlayerModels.INSTANCE.setEnabled(true);
                place("TesteB", 0, -24);
            }
        }, true, false);
        measure("perf-models-1", h -> {});
        measure("perf-vanilla-1", h -> {
            if (driver) {
                PlayerModels.INSTANCE.setEnabled(false);
            }
        });
        stage("end", 40, h -> {
            if (driver) {
                PlayerModels.INSTANCE.setEnabled(true);
                place("TesteB", 2, 5);
                command("/futura3d auth TesteA remove default_boy");
                command("/futura3d set TesteA default");
                command("/futura3d set TesteB default");
            }
        }, false, false);
    }

    // ---- Actions ----

    private void place(String player, int dx, int dz) {
        command("/tp " + player + " " + (x0 + dx + 0.5) + " " + y0 + " " + (z0 + dz + 0.5));
    }

    /** The other players this client has as entities, with their positions. */
    private static String others() {
        Minecraft mc = Minecraft.getMinecraft();
        List<String> list = new ArrayList<>();
        for (Object o : mc.theWorld.playerEntities) {
            EntityPlayer p = (EntityPlayer) o;
            if (p != mc.thePlayer) {
                list.add(
                    String.format(Locale.ROOT, "%s@%.1f,%.1f,%.1f", p.getCommandSenderName(), p.posX, p.posY, p.posZ));
            }
        }
        return list + String
            .format(Locale.ROOT, " (self at %.1f,%.1f,%.1f)", mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
    }

    private void hold(ItemStack stack) {
        Minecraft mc = Minecraft.getMinecraft();
        int slot = mc.thePlayer.inventory.currentItem;
        mc.thePlayer.inventory.setInventorySlotContents(slot, stack);
        mc.playerController.sendSlotPacket(stack, 36 + slot);
    }

    private void pressKey(KeyBinding key) {
        report("press " + key.getKeyDescription() + " (" + key.getKeyCode() + ")");
        KeyBinding.onTick(key.getKeyCode());
        keepScreen = true;
        FMLCommonHandler.instance()
            .fireKeyInput();
    }

    /** Backhand's off hand, client side only (the shot of this client), by reflection; false without Backhand. */
    private static boolean backhand(EntityPlayer player, ItemStack stack) {
        try {
            Class.forName("xonin.backhand.api.core.BackhandUtils")
                .getMethod("setPlayerOffhandItem", EntityPlayer.class, ItemStack.class)
                .invoke(null, player, stack);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    private String firstEmote() {
        RouletteMenu menu = LocalEmotes.INSTANCE.menu();
        return menu == null || menu.isEmpty() ? "extra0"
            : menu.rootEntries()
                .get(0).key;
    }

    private static String keyByName(String name) {
        for (String key : ModelRegistry.INSTANCE.keys()) {
            ModelInfo info = ModelRegistry.INSTANCE.info(key);
            if (key.startsWith(ModelRegistry.SERVER) && info != null && info.name.startsWith(name)) {
                return key;
            }
        }
        return null;
    }

    private static EntityPlayer other() {
        Minecraft mc = Minecraft.getMinecraft();
        for (Object o : mc.theWorld.playerEntities) {
            EntityPlayer p = (EntityPlayer) o;
            if (p != mc.thePlayer) {
                return p;
            }
        }
        return null;
    }

    private static String keys() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer other = other();
        return "self " + mc.thePlayer.getCommandSenderName()
            + "="
            + ModelSelection.INSTANCE.keyOf(mc.thePlayer.getUniqueID())
            + ", other "
            + (other == null ? "not tracked"
                : other.getCommandSenderName() + "("
                    + other.getUniqueID()
                    + ")="
                    + ModelSelection.INSTANCE.keyOf(other.getUniqueID()));
    }

    private static String emotes() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer other = other();
        return "self " + PlayerModels.INSTANCE.animator(mc.thePlayer)
            .isPlayingExtra()
            + ", other "
            + (other == null ? "not tracked"
                : PlayerModels.INSTANCE.animator((net.minecraft.client.entity.AbstractClientPlayer) other)
                    .isPlayingExtra());
    }

    private static String screen() {
        Object s = Minecraft.getMinecraft().currentScreen;
        return s == null ? "none"
            : s.getClass()
                .getName();
    }

    /** Whether the reobfuscated mixins really merged into their targets (handler methods present). */
    private void reportMixins() {
        report("mixin RenderPlayer " + has(RenderPlayer.class, "drawModel"));
        report("mixin RenderPlayer arm " + has(RenderPlayer.class, "futura3d"));
        report("mixin ItemRenderer " + has(ItemRenderer.class, "futura3d"));
        report("mixin RenderFish " + has(RenderFish.class, "futura3d"));
        report("mixin EntityRenderer " + has(net.minecraft.client.renderer.EntityRenderer.class, "futura3d"));
    }

    private static String has(Class<?> cls, String part) {
        List<String> names = new ArrayList<>();
        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName()
                .contains(part)) {
                names.add(m.getName());
            }
        }
        return names.isEmpty() ? "NOT APPLIED" : names.toString();
    }

    private void command(String cmd) {
        report("command " + cmd);
        Minecraft.getMinecraft().thePlayer.sendChatMessage(cmd);
    }

    private void report(String line) {
        Futura3D.LOG.info("[harness {}] {}", role, line);
        out.println(line);
        out.flush();
    }

    // ---- Barrier ----

    private void arrive(int index) {
        try {
            Files.write(
                new File(syncDir, role).toPath(),
                Integer.toString(index)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            report("sync write failed " + e);
        }
    }

    private int otherAt() {
        try {
            return Integer.parseInt(
                new String(Files.readAllBytes(new File(syncDir, driver ? "B" : "A").toPath()), StandardCharsets.UTF_8)
                    .trim());
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    // ---- Ticks ----

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.phase != TickEvent.Phase.END || done) {
            return;
        }
        if (!launched) {
            // a modpack may replace the main menu (TEOF's cinematic menu): any screen once the game ticks, with no
            // world
            if (mc.currentScreen instanceof GuiMainMenu || mc.theWorld == null && mc.currentScreen != null
                && !(mc.currentScreen instanceof net.minecraft.client.multiplayer.GuiConnecting)
                && ++menuTicks > 60) {
                launched = true;
                mc.gameSettings.pauseOnLostFocus = false;
                mc.mouseHelper = new net.minecraft.util.MouseHelper() {

                    @Override
                    public void mouseXYChange() {
                        deltaX = 0;
                        deltaY = 0;
                    }
                };
                report(
                    "connecting to " + host
                        + ":"
                        + port
                        + " as "
                        + mc.getSession()
                            .getUsername());
                cpw.mods.fml.client.FMLClientHandler.instance()
                    .connectToRealmsServer(host, port);
                mc.displayGuiScreen(
                    new net.minecraft.client.multiplayer.GuiConnecting(mc.currentScreen, mc, host, port));
            }
            return;
        }
        if (mc.thePlayer == null) {
            if (++waiting > 20 * 300) {
                report("never joined");
                finish(mc);
            }
            return;
        }
        if (mc.currentScreen != null && !keepScreen) {
            mc.displayGuiScreen(null);
        }
        mc.gameSettings.thirdPersonView = view;
        // face the other player when it is tracked (the shots show each one from the other), else face south/north
        EntityPlayer other = other();
        mc.thePlayer.rotationYaw = other == null ? driver ? 0 : 180
            : (float) Math.toDegrees(Math.atan2(other.posZ - mc.thePlayer.posZ, other.posX - mc.thePlayer.posX)) - 90;
        mc.thePlayer.rotationYawHead = mc.thePlayer.rotationYaw;
        mc.thePlayer.renderYawOffset = mc.thePlayer.rotationYaw;
        mc.thePlayer.rotationPitch = view == 0 ? 25 : 10;
        if (shotPending || measuring) {
            return;
        }
        if (stage < 0) {
            // let the catalog download before the first stage
            if (++ticks < 100) {
                return;
            }
            stage = 0;
            ticks = 0;
            arrived = false;
        }
        if (stage >= stages.size()) {
            arrive(stages.size());
            if (otherAt() >= stages.size() || ++barrierTicks > BARRIER_TIMEOUT_TICKS) {
                finish(mc);
            }
            return;
        }
        Stage s = stages.get(stage);
        if (!arrived) {
            arrive(stage);
            // the other client may still be loading the modpack before the first stage
            int timeout = stage == 0 ? 20 * 1800 : BARRIER_TIMEOUT_TICKS;
            if (otherAt() < stage && ++barrierTicks <= timeout) {
                return;
            }
            if (barrierTicks > timeout) {
                report("barrier timeout before " + s.name);
            }
            barrierTicks = 0;
            arrived = true;
            ticks = 0;
            report("stage " + s.name);
            try {
                s.action.accept(this);
            } catch (RuntimeException e) {
                report("stage " + s.name + " failed: " + e);
                Futura3D.LOG.error("[harness] stage failed", e);
            }
            return;
        }
        if (++ticks < s.settle) {
            return;
        }
        if (s.measure && driver) {
            measuring = true;
            frames = 0;
            lastFrame = 0;
        } else if (driver ? s.shotA : s.shotB) {
            shotPending = true;
        } else {
            next();
        }
    }

    private void next() {
        stage++;
        arrived = false;
        ticks = 0;
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done || stage < 0 || stage >= stages.size()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        String name = stages.get(stage).name;
        if (measuring) {
            long now = System.nanoTime();
            if (lastFrame != 0) {
                frameNanos[frames++] = now - lastFrame;
            }
            lastFrame = now;
            if (frames < MEASURE_FRAMES) {
                return;
            }
            measuring = false;
            long[] sorted = frameNanos.clone();
            Arrays.sort(sorted);
            double sum = 0;
            for (long n : sorted) {
                sum += n;
            }
            report(
                String.format(
                    Locale.ROOT,
                    "%s frame ms: mean %.2f p50 %.2f p99 %.2f (%d frames)",
                    name,
                    sum / sorted.length / 1e6,
                    sorted[sorted.length / 2] / 1e6,
                    sorted[(int) (sorted.length * 0.99)] / 1e6,
                    sorted.length));
            shotPending = true;
        }
        if (!shotPending) {
            return;
        }
        shotPending = false;
        ScreenShotHelper.saveScreenshot(
            mc.mcDataDir,
            "futura3d-teof-" + role + "-" + name + ".png",
            mc.displayWidth,
            mc.displayHeight,
            mc.getFramebuffer());
        report("shot " + name);
        next();
    }

    private void finish(Minecraft mc) {
        done = true;
        report("done");
        out.close();
        mc.shutdown();
    }
}
