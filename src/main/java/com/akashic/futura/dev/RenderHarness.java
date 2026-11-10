package com.akashic.futura.dev;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.akashic.futura.Futura3D;
import com.akashic.futura.client.LocalEmotes;
import com.akashic.futura.client.ModelRegistry;
import com.akashic.futura.client.ModelSelection;
import com.akashic.futura.client.PlayerModels;
import com.akashic.futura.client.gui.AnimationRouletteScreen;
import com.akashic.futura.emote.RouletteMenu;
import com.akashic.futura.model.FolderModelSource;
import com.akashic.futura.model.MemoryModelSource;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Development only, never in the release jar: with a {@code futura3d-harness} file in the client's run directory,
 * opens a flat world, takes screenshots of the local player with its model (front, side, and vanilla for
 * comparison) and measures frame times with 0, 1, 10 and 30 extra copies of the model, then closes the game.
 * Writes {@code futura3d-harness.txt} and {@code screenshots/futura3d-*.png}.
 */
public final class RenderHarness {

    private static final String WORLD = "futura3d-harness";
    private static final int SETTLE_TICKS = 40;
    private static final int[] COPIES = { 0, 1, 10, 30 };
    private static final int MEASURE_FRAMES = 240;

    private final PrintWriter out;
    private final List<Runnable> steps = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private boolean launched;
    private int menuTicks;
    private int index = -1;
    private int ticks;
    private boolean shotPending;
    private boolean done;

    // Measuring
    private int copies;
    private boolean measuring;
    private int frames;
    private long lastFrame;
    private long[] frameNanos = new long[MEASURE_FRAMES];
    private long[] drawNanos = new long[MEASURE_FRAMES];

    public static void install() throws FileNotFoundException {
        File file = new File(Minecraft.getMinecraft().mcDataDir, "futura3d-harness");
        try {
            String[] words = new String(
                java.nio.file.Files.readAllBytes(file.toPath()),
                java.nio.charset.StandardCharsets.UTF_8).trim()
                    .split("\\s+");
            if (words[0].equals("connect")) {
                ConnectHarness.install(words);
                return;
            }
            if (words[0].equals("teof")) {
                TeofHarness.install(words);
                return;
            }
        } catch (java.io.IOException e) {
            // run every step
        }
        RenderHarness harness = new RenderHarness();
        FMLCommonHandler.instance()
            .bus()
            .register(harness);
        MinecraftForge.EVENT_BUS.register(harness);
    }

    private RenderHarness() throws FileNotFoundException {
        out = new PrintWriter(new File(Minecraft.getMinecraft().mcDataDir, "futura3d-harness.txt"));
        step("front", () -> view(2, 0));
        step("side", () -> view(2, 90));
        step("back", () -> view(1, 30));
        step("vanilla", () -> {
            PlayerModels.INSTANCE.setEnabled(false);
            view(2, 0);
        });
        animSteps("default", "default");
        firstPersonSteps();
        animSteps("wedding", "wine_fox/18_wedding");
        copySteps("default", "default");
        // Models of the level the port targets (1554 cubes) and a stress case (4533 cubes).
        copySteps("wedding", "wine_fox/18_wedding");
        copySteps("sta", "wine_fox/08_sta");
        translucentSteps("wedding", "wine_fox/18_wedding");
        translucentSteps("little", "wine_fox/12_little");
        itemSteps();
        pickerSteps();
        serverSteps();
        controllerSteps();
        configSteps();
        viewSteps();
        langSteps();
        replacementSteps();
        fishingSteps();
        elytraSteps();
        skinSteps();
        remoteSteps();
        diagnoseSteps();
        leakSteps();
        onlySteps();
    }

    // Memory ("only leak"): 30 other players come into view with a model and leave; after a GC none of them may stay
    // in memory (a player entity keeps its whole world: a proxy switch would keep the old one).
    private final List<java.lang.ref.WeakReference<net.minecraft.entity.Entity>> spawned = new ArrayList<>();
    private int leakSpawn;

    private void leakSteps() {
        for (int i = 0; i < 3; i++) {
            step("leak-spawn" + i, () -> {
                PlayerModels.INSTANCE.setEnabled(true);
                PlayerModels.INSTANCE.use("default");
                look(0, 0, 18);
                leakSpawn = 1;
            });
        }
        // the server catalog reset again and again (as a proxy switch or /futura3d reload does): models, display lists
        // and textures must come back to the same counts
        for (int i = 0; i < 6; i++) {
            final int round = i;
            step("leak-reload" + i, () -> {
                Minecraft mc = Minecraft.getMinecraft();
                MinecraftServer server = MinecraftServer.getServer();
                PlayerModels.INSTANCE.force(null);
                if (round == 0) {
                    server.getCommandManager()
                        .executeCommand(
                            server,
                            "futura3d set " + mc.thePlayer.getCommandSenderName() + " " + diagModel());
                } else {
                    server.getCommandManager()
                        .executeCommand(server, "futura3d reload");
                }
                view(2, 0);
                atShot = () -> {
                    System.gc();
                    Runtime rt = Runtime.getRuntime();
                    report(
                        "leak reload " + round
                            + ": model "
                            + PlayerModels.INSTANCE.keyOf(mc.thePlayer)
                            + ", display lists "
                            + com.akashic.futura.client.render.GeometryRenderer.liveLists()
                            + ", textures "
                            + com.akashic.futura.client.render.ModelTexture.live()
                            + ", heap used "
                            + (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                            + " MiB");
                };
            });
            step("leak-reload" + i + "-wait", () -> view(2, 0));
        }
        step("leak-check", () -> {
            leakSpawn = 0;
            removeFake();
            atShot = () -> {
                for (int i = 0; i < 4; i++) {
                    System.gc();
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread()
                            .interrupt();
                    }
                }
                int alive = 0;
                for (java.lang.ref.WeakReference<net.minecraft.entity.Entity> r : spawned) {
                    alive += r.get() == null ? 0 : 1;
                }
                Runtime rt = Runtime.getRuntime();
                report(
                    "leak: " + alive
                        + " of "
                        + spawned.size()
                        + " removed players still in memory; animators "
                        + PlayerModels.INSTANCE.animatorCount()
                        + ", replacement states "
                        + com.akashic.futura.client.EntityReplacements.INSTANCE.stateCount()
                        + ", heap used "
                        + (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                        + " MiB");
                if (alive > 0 && Boolean.getBoolean("futura3d.harness.heapdump")) {
                    try {
                        java.lang.management.ManagementFactory
                            .newPlatformMXBeanProxy(
                                java.lang.management.ManagementFactory.getPlatformMBeanServer(),
                                "com.sun.management:type=HotSpotDiagnostic",
                                com.sun.management.HotSpotDiagnosticMXBean.class)
                            .dumpHeap(new File(mc().mcDataDir, "futura3d-leak.hprof").getPath(), true);
                        report("leak: heap dump written");
                    } catch (Exception e) {
                        report("leak: heap dump failed " + e);
                    }
                }
            };
        });
    }

    private static Minecraft mc() {
        return Minecraft.getMinecraft();
    }

    /** One more other player each tick while a leak step runs (the previous one leaves the world). */
    private void leakTick() {
        if (leakSpawn == 0 || spawned.size() >= 30 || ticks % 4 != 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        removeFake();
        fake = new EntityOtherPlayerMP(mc.theWorld, new GameProfile(java.util.UUID.randomUUID(), "leak"));
        fake.setPositionAndRotation(0.5, 4 + fake.yOffset, 3.5, 0, 0);
        // as S0CPacketSpawnPlayer does: no id before addEntityToWorld (vanilla then lost the entity in entityList for
        // good; WorldClientEntityIdMixin gives the id first)
        mc.theWorld.addEntityToWorld(FAKE_ID, fake);
        spawned.add(new java.lang.ref.WeakReference<>(fake));
    }

    // A server model from run/client/config/futura3d/models/<name> ("only diag", name in futura3d-harness-model,
    // default "yae"): bones the animation leaves broken (NaN, infinite or hidden) and the model's variables.
    private void diagnoseSteps() {
        step("diag-front", () -> {
            Minecraft mc = Minecraft.getMinecraft();
            MinecraftServer server = MinecraftServer.getServer();
            PlayerModels.INSTANCE.setEnabled(true);
            PlayerModels.INSTANCE.force(null);
            server.getCommandManager()
                .executeCommand(server, "futura3d set " + mc.thePlayer.getCommandSenderName() + " " + diagModel());
            view(2, 0);
            atShot = this::diagnose;
        });
        for (int i = 0; i < 4; i++) {
            step("diag-wait" + i, () -> view(2, 0));
        }
        step("diag-later", () -> {
            view(2, 0);
            atShot = this::diagnose;
        });
        step("diag-face", () -> {
            view(2, 20);
            atShot = this::diagnose;
        });
        step("diag-walk", () -> {
            view(2, 0);
            keys(true, false, false, false);
            atShot = this::diagnose;
        });
        step("diag-turn", () -> {
            keys(false, false, false, false);
            view(2, 120);
            atShot = this::diagnose;
        });
        step("diag-end", () -> {
            keys(false, false, false, false);
            Minecraft mc = Minecraft.getMinecraft();
            MinecraftServer server = MinecraftServer.getServer();
            server.getCommandManager()
                .executeCommand(server, "futura3d set " + mc.thePlayer.getCommandSenderName() + " default");
        });
    }

    private static String diagModel() {
        File f = new File(Minecraft.getMinecraft().mcDataDir, "futura3d-harness-model");
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8)
                .trim();
        } catch (java.io.IOException e) {
            return "yae";
        }
    }

    private void diagnose() {
        Minecraft mc = Minecraft.getMinecraft();
        com.akashic.futura.client.ClientModel m = PlayerModels.INSTANCE.modelOf(mc.thePlayer);
        com.akashic.futura.anim.PlayerAnimator a = PlayerModels.INSTANCE.animator(mc.thePlayer);
        if (m == null || a == null) {
            report("diag: no model/animator");
            return;
        }
        report("diag: model " + m.key + " state " + a.mainState());
        com.akashic.futura.geo.BakedGeometry g = m.loaded.main;
        float[] v = a.pose().values;
        StringBuilder bad = new StringBuilder();
        for (int i = 0; i < g.bones.length && (i + 1) * 9 <= v.length; i++) {
            boolean broken = false;
            for (int k = 0; k < 9; k++) {
                broken |= Float.isNaN(v[i * 9 + k]) || Float.isInfinite(v[i * 9 + k]);
            }
            if (broken || a.pose()
                .hidden(i)) {
                bad.append(g.bones[i].name)
                    .append(broken ? "(NaN/inf)" : "(hidden)")
                    .append(' ');
            }
        }
        report("diag: broken or hidden bones: " + bad);
        com.akashic.futura.molang.MolangVariables vars = a.variables();
        StringBuilder vs = new StringBuilder();
        for (String n : new String[] { "l7_p0", "l7_p0dot", "l7_k1", "l7_k2", "l7_k3", "l8_p0", "l1_p0", "l4_p0" }) {
            vs.append(n)
                .append('=')
                .append(vars.getOrNull(n))
                .append(' ');
        }
        report("diag: variables " + vs);
    }

    // Other players as a 1.7.10 client sees them ("only remote"): moved by position updates, onGround never set.
    // Before the fix their models hung in the jump pose and slid instead of walking.
    private void remoteSteps() {
        step("remote-walk", () -> {
            fakeItem(false, null, false, 90);
            PlayerModels.INSTANCE.use("default");
            fakeWalk = 1;
            atShot = () -> report("remote walking: " + remoteState());
        });
        step("remote-stand", () -> {
            fakeWalk = 0;
            atShot = () -> report("remote standing: " + remoteState());
        });
        step("remote-hover", () -> {
            fakeWalk = 2;
            atShot = () -> report("remote hovering in the air: " + remoteState());
        });
        step("remote-end", () -> {
            fakeWalk = 0;
            removeFake();
        });
        // swimming (1.13+): sprinting with the eyes under water, the YSM "swim" state
        step("swim-remote", () -> {
            pool(true);
            fakeItem(false, null, false, 90);
            look(0, 0, 50);
            PlayerModels.INSTANCE.use("default");
            fakeWalk = 3;
            atShot = () -> report("remote sprinting under water: " + remoteState());
        });
        step("swim-stop", () -> {
            fakeWalk = 4;
            atShot = () -> report("remote in water, not sprinting: " + remoteState());
        });
        step("swim-end", () -> {
            fakeWalk = 0;
            removeFake();
            pool(false);
        });
    }

    /** A pool dug 3 blocks deep at the second player's spot (server world, flat ground under y 4), or filled back. */
    private void pool(boolean water) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }
        net.minecraft.world.World w = server.worldServerForDimension(0);
        for (int x = -3; x <= 3; x++) {
            for (int z = 1; z <= 6; z++) {
                for (int y = 1; y <= 3; y++) {
                    w.setBlock(
                        x,
                        y,
                        z,
                        water ? net.minecraft.init.Blocks.water
                            : y == 3 ? net.minecraft.init.Blocks.grass : net.minecraft.init.Blocks.dirt);
                }
            }
        }
    }

    private String remoteState() {
        com.akashic.futura.anim.PlayerAnimator a = fake == null ? null : PlayerModels.INSTANCE.animator(fake);
        return "state " + (a == null ? "?" : a.mainState())
            + ", entity onGround "
            + (fake == null ? "?" : fake.onGround);
    }

    // The default look: the player's own skin on the Steve/Alex model ("only skin"). A synthetic skin painted by
    // body part (head red, body green, right arm blue, left arm yellow, right leg cyan, left leg magenta, second
    // layer white rims) shows where each part of the skin lands on the YSM skeleton.
    private void skinSteps() {
        step("skin-steve-front", () -> {
            PlayerModels.INSTANCE.setEnabled(true);
            PlayerModels.INSTANCE.force(null);
            com.akashic.futura.client.ModelSelection.INSTANCE.requestLocal(null, null);
            com.akashic.futura.client.PlayerSkins.INSTANCE
                .setTestSkin(Minecraft.getMinecraft().thePlayer, testSkin(false), false);
            view(2, 0);
            atShot = () -> report("skin model " + PlayerModels.INSTANCE.keyOf(Minecraft.getMinecraft().thePlayer));
        });
        step("skin-steve-back", () -> view(1, 30));
        step("skin-alex-front", () -> {
            com.akashic.futura.client.PlayerSkins.INSTANCE
                .setTestSkin(Minecraft.getMinecraft().thePlayer, testSkin(true), true);
            view(2, 0);
            atShot = () -> report("skin model " + PlayerModels.INSTANCE.keyOf(Minecraft.getMinecraft().thePlayer));
        });
        step("skin-alex-side", () -> view(2, 90));
        step("skin-legacy-64x32", () -> {
            java.awt.image.BufferedImage full = testSkin(false);
            com.akashic.futura.client.PlayerSkins.INSTANCE
                .setTestSkin(Minecraft.getMinecraft().thePlayer, full.getSubimage(0, 0, 64, 32), false);
            view(2, 0);
        });
        step("skin-none", () -> {
            com.akashic.futura.client.PlayerSkins.INSTANCE.setTestSkin(Minecraft.getMinecraft().thePlayer, null, false);
            view(2, 0);
            atShot = () -> report(
                "no skin: model " + PlayerModels.INSTANCE.keyOf(Minecraft.getMinecraft().thePlayer)
                    + " with its own texture");
        });
        step("skin-end", () -> {
            PlayerModels.INSTANCE.use("default");
            view(2, 0);
        });
    }

    /** A 64x64 skin painted by part (slim: 3-pixel arms). */
    private static java.awt.image.BufferedImage testSkin(boolean slim) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
            64,
            64,
            java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        int arm = slim ? 14 : 16;
        Object[][] parts = { { 0, 0, 32, 16, 0xFFD03030 }, // head
            { 16, 16, 24, 16, 0xFF30A030 }, // body
            { 40, 16, arm, 16, 0xFF3050D0 }, // right arm
            { 32, 48, arm, 16, 0xFFE0D030 }, // left arm
            { 0, 16, 16, 16, 0xFF30C0C0 }, // right leg
            { 16, 48, 16, 16, 0xFFC030C0 } }; // left leg
        for (Object[] p : parts) {
            g.setColor(new java.awt.Color((Integer) p[4], true));
            g.fillRect((Integer) p[0], (Integer) p[1], (Integer) p[2], (Integer) p[3]);
        }
        // the face (front of the head: 8..16, 8..16): eyes and a mouth
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(9, 11, 2, 1);
        g.fillRect(13, 11, 2, 1);
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(10, 11, 1, 1);
        g.fillRect(13, 11, 1, 1);
        g.fillRect(11, 14, 2, 1);
        // second layer: white rims (hat band, jacket hem, sleeve and trouser cuffs)
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(32, 14, 32, 2); // hat band
        g.fillRect(16, 46, 24, 2); // jacket hem
        g.fillRect(40, 46, arm, 2); // right sleeve cuff
        g.fillRect(48, 62, arm, 2); // left sleeve cuff
        g.fillRect(0, 46, 16, 2); // right trouser cuff
        g.fillRect(0, 62, 16, 2); // left trouser cuff
        g.dispose();
        return img;
    }

    // Etapa 6: the first-person camera height from the model (ViewLocator, else Head). Steps "view-*".
    private void viewSteps() {
        step("view-vanilla", () -> {
            viewScene();
            PlayerModels.INSTANCE.setEnabled(false);
            keys(false, false, false, false);
            viewAt();
            atShot = () -> reportView("vanilla");
        });
        step("view-default", () -> {
            PlayerModels.INSTANCE.setEnabled(true);
            PlayerModels.INSTANCE.use("default");
            keys(false, false, false, false);
            viewAt();
            atShot = () -> reportView("default standing");
        });
        step("view-default-sneak", () -> {
            keys(false, true, false, false);
            viewAt();
            atShot = () -> reportView("default sneaking");
        });
        step("view-vanilla-sneak", () -> {
            PlayerModels.INSTANCE.setEnabled(false);
            keys(false, true, false, false);
            viewAt();
            atShot = () -> reportView("vanilla sneaking");
        });
        step("view-momo-small", () -> {
            PlayerModels.INSTANCE.setEnabled(true);
            PlayerModels.INSTANCE.use("wine_fox/14_momo");
            keys(false, false, false, false);
            viewAt();
            setVariable("player_size", 0.4);
            atShot = () -> reportView("momo v.player_size=0.4");
        });
        step("view-momo-big", () -> {
            setVariable("player_size", 2.0);
            viewAt();
            atShot = () -> reportView("momo v.player_size=2");
        });
        step("view-momo-big-third", () -> {
            Minecraft.getMinecraft().gameSettings.thirdPersonView = 2;
            atShot = () -> reportView("momo v.player_size=2, third person (vanilla camera)");
        });
        step("view-end", () -> {
            setVariable("player_size", 1.0);
            PlayerModels.INSTANCE.use("default");
            keys(false, false, false, false);
            view(2, 0);
        });
    }

    /** A column of coloured wool in front of the spawn, one colour per block of height, to read the eye height. */
    private void viewScene() {
        EntityPlayerMP p = serverPlayer(Minecraft.getMinecraft());
        if (p == null) {
            return;
        }
        for (int y = 4; y < 9; y++) {
            for (int x = -1; x <= 1; x++) {
                p.worldObj.setBlock(x, y, 3, Blocks.wool, y - 4 + (x == 0 ? 0 : 8), 3);
            }
        }
    }

    /** First person at the spawn, looking straight south at the column. */
    private void viewAt() {
        Minecraft mc = Minecraft.getMinecraft();
        mc.gameSettings.thirdPersonView = 0;
        EntityPlayerMP p = serverPlayer(mc);
        if (p != null) {
            p.playerNetServerHandler.setPlayerLocation(0.5, 4, 0.5, 0, 0);
        }
    }

    private void reportView(String label) {
        com.akashic.futura.client.ViewCamera c = com.akashic.futura.client.ViewCamera.INSTANCE;
        report(
            String.format(
                Locale.ROOT,
                "view %s: camera offset %.3f (target %.3f) blocks",
                label,
                c.offset(),
                c.target()));
    }

    // Etapa 6: the models' own translations in the roulette and the picker. Steps "lang-*".
    private void langSteps() {
        step("lang-roulette-zh", () -> {
            Minecraft mc = Minecraft.getMinecraft();
            setLanguage("zh_CN");
            PlayerModels.INSTANCE.setEnabled(true);
            PlayerModels.INSTANCE.use("wine_fox/14_momo");
            keys(false, false, false, false);
            view(2, 0);
            keepScreen = true;
            RouletteMenu menu = LocalEmotes.INSTANCE.menu();
            menu.reset();
            mc.displayGuiScreen(new AnimationRouletteScreen(menu));
        });
        step("lang-roulette-en", () -> {
            Minecraft mc = Minecraft.getMinecraft();
            setLanguage("en_US");
            RouletteMenu menu = LocalEmotes.INSTANCE.menu();
            menu.reset();
            mc.displayGuiScreen(new AnimationRouletteScreen(menu));
        });
        step("lang-roulette-sub-en", () -> {
            Minecraft mc = Minecraft.getMinecraft();
            RouletteMenu menu = LocalEmotes.INSTANCE.menu();
            menu.reset();
            List<RouletteMenu.Entry> entries = menu.pageEntries();
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i)
                    .isClassify()) {
                    report("lang sub-page: " + menu.click(i));
                    break;
                }
            }
            mc.displayGuiScreen(new AnimationRouletteScreen(menu));
        });
        step("lang-roulette-config-pt", () -> {
            Minecraft mc = Minecraft.getMinecraft();
            setLanguage("pt_BR");
            RouletteMenu menu = LocalEmotes.INSTANCE.menu();
            menu.reset();
            AnimationRouletteScreen screen = new AnimationRouletteScreen(menu);
            mc.displayGuiScreen(screen);
            com.akashic.futura.model.LoadedModel model = PlayerModels.INSTANCE.model();
            if (model != null && !model.manifest.properties.extraAnimationButtons.isEmpty()) {
                screen.openConfig(model.manifest.properties.extraAnimationButtons.get(0));
            }
        });
        step("lang-picker-zh", () -> {
            Minecraft mc = Minecraft.getMinecraft();
            setLanguage("zh_CN");
            PlayerModels.INSTANCE.force(null);
            mc.displayGuiScreen(
                new com.akashic.futura.client.gui.ModelPickerScreen().focus("builtin:wine_fox/14_momo", null));
        });
        step("lang-picker-zh-loaded", () -> pointAtCard("builtin:wine_fox/14_momo"));
        step("lang-picker-pt", () -> {
            Minecraft mc = Minecraft.getMinecraft();
            setLanguage("pt_BR");
            mc.displayGuiScreen(
                new com.akashic.futura.client.gui.ModelPickerScreen().focus("builtin:wine_fox/14_momo", null));
            pointAtCard("builtin:wine_fox/14_momo");
        });
        step("lang-end", () -> {
            keepScreen = false;
            Minecraft mc = Minecraft.getMinecraft();
            mc.displayGuiScreen(null);
            setLanguage("en_US");
            LocalEmotes.INSTANCE.menu()
                .reset();
            PlayerModels.INSTANCE.use("default");
            view(2, 0);
        });
    }

    /**
     * The game language the model texts are looked up in. Only the setting and the font's Unicode flag change (no
     * resource reload): the game's own texts stay as they were.
     */
    private void setLanguage(String code) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.gameSettings.language = code;
        mc.fontRenderer.setUnicodeFlag(code.startsWith("zh") || code.startsWith("ja") || code.startsWith("ko"));
        report("language " + code);
    }

    // ---- Item 2: projectile and vehicle replacements. Steps "repl-*" ("only repl" runs just these). ----

    private static final int REPL_ID = -4243;
    private net.minecraft.entity.Entity replEntity;
    /** Where the replaced entity is held every tick (x, y of its bounding box bottom, z, yaw, pitch). */
    private double[] replPlace;

    /**
     * A second player with a model, and next to him an arrow he shot, his fishing hook, a boat, a minecart on a rail
     * and a horse he rides, each drawn with his model's replacement and then as vanilla.
     */
    private void replacementSteps() {
        for (boolean vanilla : new boolean[] { false, true }) {
            String v = vanilla ? "vanilla-" : "";
            step("repl-" + v + "arrow", () -> replStep(vanilla, "default", () -> {
                net.minecraft.entity.projectile.EntityArrow arrow = new net.minecraft.entity.projectile.EntityArrow(
                    Minecraft.getMinecraft().theWorld,
                    1.5,
                    5,
                    3.5);
                arrow.shootingEntity = fake;
                return arrow;
            }, new double[] { 1.5, 5, 3.5, 90, -20 }, false));
            step("repl-" + v + "hook", () -> replStep(vanilla, "default", () -> {
                fake.inventory.setInventorySlotContents(fake.inventory.currentItem, new ItemStack(Items.fishing_rod));
                return new net.minecraft.entity.projectile.EntityFishHook(
                    Minecraft.getMinecraft().theWorld,
                    1.5,
                    4.2,
                    2.5,
                    fake);
            }, new double[] { 1.5, 4.2, 2.5, 0, 0 }, false));
            step(
                "repl-" + v + "boat",
                () -> replStep(
                    vanilla,
                    "default",
                    () -> {
                        return new net.minecraft.entity.item.EntityBoat(Minecraft.getMinecraft().theWorld, 0.5, 4, 3.5);
                    },
                    new double[] { 0.5, 4, 3.5, 0, 0 },
                    true));
            step("repl-" + v + "minecart", () -> replStep(vanilla, "default", () -> {
                MinecraftServer server = MinecraftServer.getServer();
                if (server != null) {
                    // an east-west rail: the cart's yaw follows it
                    server.worldServers[0].setBlock(0, 4, 3, Blocks.rail, 1, 3);
                }
                return new net.minecraft.entity.item.EntityMinecartEmpty(
                    Minecraft.getMinecraft().theWorld,
                    0.5,
                    4,
                    3.5);
            }, new double[] { 0.5, 4, 3.5, 0, 0 }, true));
            step("repl-" + v + "horse", () -> replStep(vanilla, "wine_fox/16_tactics", () -> {
                net.minecraft.entity.passive.EntityHorse horse = new net.minecraft.entity.passive.EntityHorse(
                    Minecraft.getMinecraft().theWorld);
                horse.setHorseSaddled(true);
                return horse;
            }, new double[] { 0.5, 4, 3.5, 90, 0 }, true));
        }
        step("repl-end", () -> {
            replRemove();
            removeFake();
            MinecraftServer server = MinecraftServer.getServer();
            if (server != null) {
                server.worldServers[0].setBlockToAir(0, 4, 3);
            }
            com.akashic.futura.client.EntityReplacements.INSTANCE.setEnabled(true);
            PlayerModels.INSTANCE.setEnabled(true);
            PlayerModels.INSTANCE.use("default");
            Minecraft.getMinecraft().gameSettings.hideGUI = false;
        });
    }

    private void replStep(boolean vanilla, String builtin,
        java.util.function.Supplier<net.minecraft.entity.Entity> make, double[] place, boolean ride) {
        Minecraft mc = Minecraft.getMinecraft();
        replRemove();
        removeFake();
        com.akashic.futura.client.EntityReplacements.INSTANCE.setEnabled(!vanilla);
        PlayerModels.INSTANCE.setEnabled(true);
        PlayerModels.INSTANCE.use(builtin);
        keys(false, false, false, false);
        holdUse = false;
        fakeHurt = 0;
        fakeDying = false;
        fakeUse = null;
        mc.gameSettings.hideGUI = true;
        fake = new EntityOtherPlayerMP(mc.theWorld, new GameProfile(java.util.UUID.randomUUID(), "futura3d"));
        fake.setPositionAndRotation(0.5, 4 + fake.yOffset, ride ? 3.5 : 3.0, 90, 0);
        fakeYaw = 90;
        mc.theWorld.addEntityToWorld(FAKE_ID, fake);
        replEntity = make.get();
        replPlace = place;
        replHold();
        mc.theWorld.addEntityToWorld(REPL_ID, replEntity);
        if (ride) {
            fake.mountEntity(replEntity);
        }
        // a quarter view from the south-east, three blocks away
        lookFrom(3.0, 1.0, 0, 45, 20);
        report(
            "repl " + builtin
                + " "
                + net.minecraft.entity.EntityList.getEntityString(replEntity)
                + " replaced="
                + (!vanilla && ride ? com.akashic.futura.client.EntityReplacements.INSTANCE.replacesVehicle(replEntity)
                    : !vanilla));
    }

    /** Holds the replaced entity in place (no motion, fixed rotation), every tick. */
    private void replHold() {
        if (replEntity == null || replPlace == null) {
            return;
        }
        net.minecraft.entity.Entity e = replEntity;
        double y = replPlace[1] + e.yOffset;
        e.setPosition(replPlace[0], y, replPlace[2]);
        e.prevPosX = e.lastTickPosX = replPlace[0];
        e.prevPosY = e.lastTickPosY = y;
        e.prevPosZ = e.lastTickPosZ = replPlace[2];
        e.motionX = e.motionY = e.motionZ = 0;
        e.rotationYaw = e.prevRotationYaw = (float) replPlace[3];
        e.rotationPitch = e.prevRotationPitch = (float) replPlace[4];
        if (e instanceof net.minecraft.entity.EntityLivingBase) {
            net.minecraft.entity.EntityLivingBase living = (net.minecraft.entity.EntityLivingBase) e;
            living.renderYawOffset = living.prevRenderYawOffset = (float) replPlace[3];
            living.rotationYawHead = living.prevRotationYawHead = (float) replPlace[3];
        }
    }

    private void replRemove() {
        if (replEntity != null) {
            if (fake != null && fake.ridingEntity == replEntity) {
                fake.mountEntity(null);
            }
            Minecraft.getMinecraft().theWorld.removeEntityFromWorld(REPL_ID);
            replEntity = null;
            replPlace = null;
        }
    }

    // ---- The rod (anim/FishingStates). Steps "fish-*" ("only fish" runs just these). ----

    /** Ticks into the step at which the shot is taken, or -1 for {@link #SETTLE_TICKS}. */
    private int settle = -1;
    /** Runs at tick {@link #fishAt} of the step (the moment a phase starts). */
    private Runnable fishAction;
    private int fishAt = -1;

    /**
     * A second player seen from the side with a fishing rod and the default model, through every phase of the rod:
     * each shot a few ticks after its phase starts (the moment its clip is about), the bobber held three blocks in
     * front of him. The fight's states come as Kunftig would set them (external states).
     */
    private void fishingSteps() {
        // the rod comes to the hand: the spin, halfway
        fishStep("fish-draw", false, null, null, false, () -> fakeHold(new ItemStack(Items.fishing_rod)), 5);
        // the cast: wind-up, release, follow-through; then the line out and waiting
        fishStep("fish-cast-windup", false, null, null, false, () -> fake.swingItem(), 2);
        fishStep("fish-cast-release", false, null, null, false, () -> fake.swingItem(), 4);
        fishStep("fish-cast-follow", false, null, null, false, () -> {
            fake.swingItem();
            fishHook();
        }, 6);
        fishStep("fish-wait", true, null, null, false, null, 30);
        fishStep("fish-bite", true, null, null, false, () -> fishState("mordida", null), 2);
        fishStep("fish-fight-calm", true, "briga", "0", false, null, 30);
        fishStep("fish-fight-tense", true, "briga", "1", false, null, 30);
        fishStep("fish-fight-heave", true, "briga", "0.5", true, null, 30);
        fishStep("fish-reel", true, "recolher", "0.4", false, null, 30);
        fishStep("fish-catch-yank", true, "briga", "0.5", false, () -> fishState("captura", null), 8);
        fishStep("fish-catch-pump", true, "briga", "0.5", false, () -> {
            fishState("captura", null);
            replRemove(); // the server reels in: the line comes back
            fake.fishEntity = null;
        }, 15);
        fishStep("fish-escape", true, "briga", "0.7", false, () -> fishState("escapou", null), 10);
        fishStep("fish-retrieve", true, null, null, false, () -> fake.swingItem(), 3);
        fishStep("fish-fight-front", true, "briga", "0.6", false, null, 30);
        step("fish-end", () -> {
            replRemove();
            removeFake();
            Minecraft.getMinecraft().gameSettings.hideGUI = false;
        });
    }

    private void fishStep(String name, boolean line, String state, String tension, boolean sneaking, Runnable action,
        int shotAfter) {
        int start = 10; // the model loaded, the pose settled
        step(name, () -> {
            replRemove();
            removeFake(); // a new player each time: a new animator, first seen with what he holds
            PlayerModels.INSTANCE.setEnabled(true);
            PlayerModels.INSTANCE.use("default");
            boolean front = name.endsWith("-front");
            fakeItem(
                false,
                new ItemStack(name.equals("fish-draw") ? Items.iron_sword : Items.fishing_rod),
                false,
                front ? 180 : 90);
            fake.setSneaking(sneaking);
            if (line) {
                fishHook();
            }
            fishState(state, tension);
            fishAction = action;
            fishAt = action == null ? -1 : start;
            settle = (action == null ? 0 : start) + shotAfter;
        });
    }

    /** The bobber three blocks in front of the second player, held there ({@link #replHold}). */
    private void fishHook() {
        Minecraft mc = Minecraft.getMinecraft();
        double dx = -Math.sin(Math.toRadians(fakeYaw)) * 3;
        double dz = Math.cos(Math.toRadians(fakeYaw)) * 3;
        double[] at = { fake.posX + dx, 3.9, fake.posZ + dz, 0, 0 };
        replEntity = new net.minecraft.entity.projectile.EntityFishHook(mc.theWorld, at[0], at[1], at[2], fake);
        replPlace = at;
        replHold();
        mc.theWorld.addEntityToWorld(REPL_ID, replEntity);
        fake.fishEntity = (net.minecraft.entity.projectile.EntityFishHook) replEntity;
    }

    private void fishState(String state, String tension) {
        if (fake == null) {
            return;
        }
        com.akashic.futura.anim.ExternalStates states = PlayerModels.INSTANCE.externalStates();
        if (state != null) {
            states.set(fake.getUniqueID(), com.akashic.futura.anim.FishingStates.STATE, null);
            states.set(fake.getUniqueID(), com.akashic.futura.anim.FishingStates.STATE, state);
        }
        if (tension != null) {
            states.set(fake.getUniqueID(), com.akashic.futura.anim.FishingStates.TENSION, tension);
        }
    }

    private void fakeHold(ItemStack stack) {
        fake.inventory.setInventorySlotContents(fake.inventory.currentItem, stack);
    }

    private void fishTick() {
        if (fake != null && fishAt >= 0 && ticks == fishAt && fishAction != null) {
            Runnable r = fishAction;
            fishAction = null;
            r.run();
        }
        if (fake != null && index >= 0
            && index < names.size()
            && names.get(index)
                .startsWith("fish-")) {
            report(
                "  t" + ticks
                    + " phase "
                    + PlayerModels.INSTANCE.animator(fake)
                        .fishingPhase()
                    + " "
                    + PlayerModels.INSTANCE.animator(fake)
                        .playing("player.fishing"));
        }
    }

    // ---- Et Futurum Requiem's elytra (client/ElytraBridge). Steps "elytra-*" ("only elytra" runs just these). ----

    /** Gliding as EFR's mixin would set it, every tick of the step ({@link #elytraTick}); null for standing. */
    private float[] elytraGlide;

    /**
     * The second player wearing EFR's elytra: standing (the wings folded on the back), gliding level from the side,
     * diving, and gliding seen from behind; each with the vanilla biped (EFR's own drawing) to compare. Needs
     * etfuturum in run/client/mods; without it the steps report it and take the shots anyway.
     */
    private void elytraSteps() {
        for (boolean vanilla : new boolean[] { false, true }) {
            String v = vanilla ? "vanilla-" : "";
            step("elytra-" + v + "stand-back", () -> elytraStep(vanilla, 0, null));
            step("elytra-" + v + "glide-side", () -> elytraStep(vanilla, 90, new float[] { 0 }));
            step("elytra-" + v + "dive-side", () -> elytraStep(vanilla, 90, new float[] { 40 }));
            step("elytra-" + v + "glide-back", () -> elytraStep(vanilla, 0, new float[] { -10 }));
        }
        step("elytra-model-without-locator", () -> {
            elytraStep(false, 90, new float[] { 0 });
            PlayerModels.INSTANCE.use("wine_fox/22_elf");
        });
        step("elytra-end", () -> {
            elytraGlide = null;
            removeFake();
            PlayerModels.INSTANCE.setEnabled(true);
            PlayerModels.INSTANCE.use("default");
            Minecraft.getMinecraft().gameSettings.hideGUI = false;
        });
    }

    private void elytraStep(boolean vanilla, float yaw, float[] glide) {
        replRemove();
        removeFake();
        PlayerModels.INSTANCE.setEnabled(!vanilla);
        PlayerModels.INSTANCE.use("default");
        fakeItem(vanilla, new ItemStack(Items.fishing_rod), false, yaw);
        fake.inventory.setInventorySlotContents(fake.inventory.currentItem, null);
        net.minecraft.item.Item elytra = (net.minecraft.item.Item) net.minecraft.item.Item.itemRegistry
            .getObject("etfuturum:elytra");
        report("  elytra item " + elytra);
        if (elytra != null) {
            fake.inventory.armorInventory[2] = new ItemStack(elytra);
        }
        elytraGlide = glide;
        settle = 30;
    }

    private void elytraTick() {
        if (fake == null || index < 0
            || index >= names.size()
            || !names.get(index)
                .startsWith("elytra-")) {
            return;
        }
        boolean glide = elytraGlide != null;
        try {
            fake.getClass()
                .getMethod("etfu$setElytraFlying", boolean.class)
                .invoke(fake, glide);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (ticks == 1) {
                report("  no EFR elytra on the player: " + e);
            }
        }
        if (glide) {
            float pitch = elytraGlide[0];
            fake.rotationPitch = fake.prevRotationPitch = pitch;
            // flying where it looks, a little sideways (the lean into the turn)
            double yawRad = Math.toRadians(fakeYaw + 15);
            fake.motionX = -Math.sin(yawRad) * 0.6;
            fake.motionZ = Math.cos(yawRad) * 0.6;
            fake.motionY = -Math.sin(Math.toRadians(pitch)) * 0.6;
            fake.onGround = false;
            fake.setPositionAndRotation(fake.posX, 5, fake.posZ, fakeYaw, pitch);
        }
        if (ticks == 25) {
            String main = "-";
            try {
                main = PlayerModels.INSTANCE.animator(fake)
                    .mainState();
            } catch (RuntimeException e) {
                // the vanilla twin: no animator was updated
            }
            report("  flying " + ElytraProbe.flying(fake) + " ticks " + ElytraProbe.ticks(fake) + " main " + main);
        }
    }

    // Phase 5a: Bedrock controllers, functions and sounds. Steps "ctrl-*" ("only ctrl" runs just these).
    private void controllerSteps() {
        step("ctrl-default-sword", () -> {
            ctrlModel("misc/4_default_controllers");
            keys(false, false, false, true);
            holdItem(new ItemStack(Items.diamond_sword));
            swingSword = false;
            view(2, 30);
            atShot = () -> reportControllers("default-controllers, sword held");
        });
        step("ctrl-default-swing", () -> {
            keys(false, false, false, true);
            holdItem(new ItemStack(Items.diamond_sword));
            view(2, 30);
            atShot = () -> reportControllers("default-controllers, swinging");
        });
        step("ctrl-kluonoa-nocar", () -> {
            ctrlModel("wine_fox/15_kluonoa");
            keys(false, false, false, false);
            setVariable("roaming.car", 0);
            view(2, 30);
        });
        step("ctrl-kluonoa-car", () -> {
            setVariable("roaming.car", 1);
            view(2, 30);
            atShot = () -> reportControllers("kluonoa, v.roaming.car = 1");
        });
        step("ctrl-kluonoa-car-walk", () -> {
            keys(true, false, false, false);
            view(2, 30);
            atShot = () -> reportControllers("kluonoa, car, walking");
        });
        step("ctrl-saint-swing", () -> {
            setVariable("roaming.car", 0);
            ctrlModel("wine_fox/21_saint");
            keys(false, false, false, true);
            holdItem(new ItemStack(Items.diamond_sword));
            view(2, 30);
            atShot = () -> reportControllers("saint, swinging a sword (sound keyframes atk1/atk2/atk3)");
        });
        step("ctrl-saint-bow", () -> {
            keys(false, false, false, false);
            holdItem(new ItemStack(Items.bow));
            view(2, 30);
            atTick5 = () -> holdUse = true;
            // releasing the bow: the model's player_ctrl_use function plays magic01_shoot
            atTick30 = () -> holdUse = false;
            atShot = () -> reportControllers("saint, bow drawn then released");
        });
        step("ctrl-wedding-emote-sound", () -> {
            holdUse = false;
            ctrlModel("wine_fox/18_wedding");
            keys(false, false, false, false);
            view(2, 30);
            atTick5 = () -> {
                PlayerModels.INSTANCE.animator(Minecraft.getMinecraft().thePlayer)
                    .playExtra("extra3");
                reportSounds("wedding, extra3 started (sound keyframe get_down)");
            };
            atShot = () -> reportSounds("wedding, extra3 after 40 ticks");
        });
        step("ctrl-momo-opus", () -> {
            ctrlModel("wine_fox/14_momo");
            view(2, 30);
            atTick5 = () -> {
                PlayerModels.INSTANCE.animator(Minecraft.getMinecraft().thePlayer)
                    .playExtra("dooble");
                reportSounds("momo, dooble started (an Ogg Opus file, streamed through CodecOggOpus)");
            };
        });
        step("ctrl-end", () -> {
            PlayerModels.INSTANCE.animator(Minecraft.getMinecraft().thePlayer)
                .stopExtra();
            reportSounds("end");
            keys(false, false, false, false);
            PlayerModels.INSTANCE.use("default");
        });
    }

    private void ctrlModel(String builtin) {
        PlayerModels.INSTANCE.setEnabled(true);
        PlayerModels.INSTANCE.use(builtin);
        copies = 0;
        keepScreen = false;
        LocalEmotes.INSTANCE.stop();
    }

    private void holdItem(ItemStack stack) {
        EntityPlayerMP p = serverPlayer(Minecraft.getMinecraft());
        if (p != null) {
            p.inventory.setInventorySlotContents(p.inventory.currentItem, stack);
        }
    }

    private void setVariable(String name, double value) {
        com.akashic.futura.anim.PlayerAnimator a = PlayerModels.INSTANCE.animator(Minecraft.getMinecraft().thePlayer);
        if (a != null) {
            a.variables()
                .set(name, com.akashic.futura.molang.MolangValue.of(value));
        }
    }

    /** Logs the state of every controller that is not idle, and the sounds so far. */
    private void reportControllers(String label) {
        com.akashic.futura.anim.PlayerAnimator a = PlayerModels.INSTANCE.animator(Minecraft.getMinecraft().thePlayer);
        report("controllers: " + label);
        if (a == null) {
            return;
        }
        for (com.akashic.futura.anim.AnimationController c : a.controllers()) {
            boolean bedrock = c instanceof com.akashic.futura.anim.HybridController
                && ((com.akashic.futura.anim.HybridController) c).bedrock() != null;
            if (bedrock || c.state() != com.akashic.futura.anim.PlaybackState.IDLE) {
                report("  " + c.name() + (bedrock ? " [bedrock] " : " ") + c.stateName());
            }
        }
        reportSounds(label);
    }

    private void reportSounds(String label) {
        report(
            "  sounds (" + label
                + "): accepted by the sound system so far "
                + com.akashic.futura.client.ModelSounds.INSTANCE.started()
                + ", playing "
                + com.akashic.futura.client.ModelSounds.INSTANCE.playing());
    }

    // Phase 4a: a server model through the integrated server's protocol. Needs a model folder or zip in
    // run/client/config/futura3d/models/ (outside git), e.g. a copy of builtin/misc/1_alex named futura_test.
    private static final String TEST_MODEL = "futura_test";

    private void serverSteps() {
        step("server-before", () -> {
            PlayerModels.INSTANCE.setEnabled(true);
            PlayerModels.INSTANCE.use("default");
            copies = 0;
            keys(false, false, false, false);
            report("server: " + com.akashic.futura.client.net.ClientNetwork.INSTANCE.status());
            com.akashic.futura.server.ModelServer server = com.akashic.futura.server.ForgeModelServer.current();
            if (server != null) {
                for (String line : server.list()) {
                    report("server list: " + line);
                }
            }
            view(2, 0);
        });
        step("server-set", () -> {
            Minecraft mc = Minecraft.getMinecraft();
            MinecraftServer server = MinecraftServer.getServer();
            // as the console (permission level 4): the harness world has no cheats
            server.getCommandManager()
                .executeCommand(server, "futura3d set " + mc.thePlayer.getCommandSenderName() + " " + TEST_MODEL);
            view(2, 0);
        });
        step("server-after", () -> {
            Minecraft mc = Minecraft.getMinecraft();
            report("server: " + com.akashic.futura.client.net.ClientNetwork.INSTANCE.status());
            File cache = new File(mc.mcDataDir, "futura3d/cache");
            String[] cached = cache.list();
            report("server: cache files " + (cached == null ? 0 : cached.length));
            String key;
            try {
                key = com.akashic.futura.client.ModelSelection.INSTANCE.keyOf(mc.thePlayer.getUniqueID());
            } catch (UnsupportedOperationException e) {
                key = "(ModelSelection not implemented yet)";
            }
            report("server: local player's key " + key);
            com.akashic.futura.server.ModelServer server = com.akashic.futura.server.ForgeModelServer.current();
            if (server != null) {
                report("server: state on the server " + server.stateOf(mc.thePlayer.getUniqueID()).key);
            }
            view(2, 0);
        });
        step("server-emote", () -> {
            Minecraft mc = Minecraft.getMinecraft();
            MinecraftServer server = MinecraftServer.getServer();
            server.getCommandManager()
                .executeCommand(server, "futura3d play " + mc.thePlayer.getCommandSenderName() + " extra0");
            view(2, 0);
        });
        step("server-reset", () -> {
            Minecraft mc = Minecraft.getMinecraft();
            MinecraftServer server = MinecraftServer.getServer();
            server.getCommandManager()
                .executeCommand(server, "futura3d play " + mc.thePlayer.getCommandSenderName() + " stop");
            server.getCommandManager()
                .executeCommand(server, "futura3d set " + mc.thePlayer.getCommandSenderName() + " default");
            report("server: " + com.akashic.futura.client.net.ClientNetwork.INSTANCE.status());
        });
    }

    // Animation states: keys held every tick by the harness, a sword swung every 10 ticks.
    private boolean holdForward;
    private boolean holdSneak;
    private boolean holdSprint;
    private boolean swingSword;

    private void animSteps(String label, String builtin) {
        step(label + "-idle", () -> {
            PlayerModels.INSTANCE.setEnabled(true);
            PlayerModels.INSTANCE.use(builtin);
            copies = 0;
            keys(false, false, false, false);
            view(2, 0);
        });
        step(label + "-walk", () -> {
            keys(true, false, false, false);
            view(2, 0);
        });
        step(label + "-run", () -> {
            keys(true, false, true, false);
            view(2, 0);
        });
        step(label + "-sneak", () -> {
            keys(true, true, false, false);
            view(2, 0);
        });
        step(label + "-sword", () -> {
            keys(false, false, false, true);
            EntityPlayerMP p = serverPlayer(Minecraft.getMinecraft());
            if (p != null) {
                p.inventory.setInventorySlotContents(p.inventory.currentItem, new ItemStack(Items.diamond_sword));
            }
            view(2, 0);
        });
        step(label + "-rest", () -> keys(false, false, false, false));
    }

    // First person (phase 3b): arms, then an emote and the roulette.
    private boolean keepScreen;

    private void firstPersonSteps() {
        step("fp-idle", () -> {
            PlayerModels.INSTANCE.setEnabled(true);
            PlayerModels.INSTANCE.use("default");
            copies = 0;
            keys(false, false, false, false);
            view(0, 0);
        });
        step("fp-vanilla", () -> {
            PlayerModels.INSTANCE.setEnabled(false);
            view(0, 0);
        });
        step("fp-walk", () -> {
            PlayerModels.INSTANCE.setEnabled(true);
            keys(true, false, false, false);
            view(0, 0);
        });
        step("fp-swing", () -> {
            keys(false, false, false, false);
            // swing with the empty hand: the model's arm follows vanilla's swing
            keys(false, false, false, true);
            view(0, 0);
        });
        step("fp-vanilla-swing", () -> {
            PlayerModels.INSTANCE.setEnabled(false);
            view(0, 0);
        });
        step("fp-sword", () -> {
            PlayerModels.INSTANCE.setEnabled(true);
            keys(false, false, false, true);
            EntityPlayerMP p = serverPlayer(Minecraft.getMinecraft());
            if (p != null) {
                p.inventory.setInventorySlotContents(p.inventory.currentItem, new ItemStack(Items.diamond_sword));
            }
            view(0, 0);
        });
        step("emote", () -> {
            keys(false, false, false, false);
            view(2, 0);
            RouletteMenu menu = LocalEmotes.INSTANCE.menu();
            String first = menu == null || menu.isEmpty() ? "extra0"
                : menu.rootEntries()
                    .get(0).key;
            report("emote " + first + " playing: " + LocalEmotes.INSTANCE.play(first));
        });
        step("roulette", () -> {
            Minecraft mc = Minecraft.getMinecraft();
            keepScreen = true;
            mc.displayGuiScreen(new AnimationRouletteScreen(LocalEmotes.INSTANCE.menu()));
            // hover the second slice (down-right of the centre), 75 GUI pixels out
            ScaledResolution res = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
            int scale = res.getScaleFactor();
            double angle = Math.toRadians(67.5);
            int gx = res.getScaledWidth() / 2 - 70 + (int) (75 * Math.cos(angle));
            int gy = res.getScaledHeight() / 2 - 8 + (int) (75 * Math.sin(angle));
            Mouse.setCursorPosition(gx * scale, mc.displayHeight - gy * scale - 1);
        });
        // a model whose fp_arm animates bones of its arms (parallel0)
        step("fp-nine-tailed", () -> {
            keepScreen = false;
            Minecraft.getMinecraft()
                .displayGuiScreen(null);
            LocalEmotes.INSTANCE.stop();
            PlayerModels.INSTANCE.use("wine_fox/19_nine_tailed");
            keys(false, false, false, false);
            view(0, 0);
        });
        step("fp-rest", () -> {
            keepScreen = false;
            Minecraft.getMinecraft()
                .displayGuiScreen(null);
            LocalEmotes.INSTANCE.stop();
            keys(false, false, false, false);
            view(2, 0);
        });
    }

    // Phase 5b: the roulette's config forms written through the local animator, the roaming variables they set sent
    // to the (integrated) server, and ysm.sync coming back from it.
    private void configSteps() {
        step("config-momo-panel", () -> {
            Minecraft mc = Minecraft.getMinecraft();
            PlayerModels.INSTANCE.setEnabled(true);
            PlayerModels.INSTANCE.use("wine_fox/14_momo");
            keys(false, false, false, false);
            view(2, 0);
            keepScreen = true;
            AnimationRouletteScreen screen = new AnimationRouletteScreen(LocalEmotes.INSTANCE.menu());
            mc.displayGuiScreen(screen);
            com.akashic.futura.model.LoadedModel model = PlayerModels.INSTANCE.model();
            if (model != null && !model.manifest.properties.extraAnimationButtons.isEmpty()) {
                com.akashic.futura.format.pojo.manifest.settings.ExtraAnimationButton button = model.manifest.properties.extraAnimationButtons
                    .get(0);
                screen.openConfig(button);
                com.akashic.futura.anim.PlayerAnimator a = PlayerModels.INSTANCE.animator(mc.thePlayer);
                for (com.akashic.futura.format.pojo.manifest.settings.ConfigForms f : button.configForms) {
                    switch (com.akashic.futura.emote.ConfigForm.type(f)) {
                        case RANGE:
                            com.akashic.futura.emote.ConfigForm
                                .writeFloat(f, a.context(), com.akashic.futura.emote.ConfigForm.atFraction(f, 0.4));
                            break;
                        case CHECKBOX:
                            com.akashic.futura.emote.ConfigForm.writeBool(f, a.context(), true);
                            break;
                        default:
                            break;
                    }
                }
                report(
                    "config momo: " + button.configForms.size()
                        + " forms written; v.player_size="
                        + a.variables()
                            .get("player_size")
                        + " v.roaming.back="
                        + a.variables()
                            .get("roaming.back"));
            }
        });
        step("config-roaming-sent", () -> {
            keepScreen = false;
            Minecraft mc = Minecraft.getMinecraft();
            mc.displayGuiScreen(null);
            view(2, 0);
            com.akashic.futura.client.net.ClientNetwork net = com.akashic.futura.client.net.ClientNetwork.INSTANCE;
            report(
                "network: " + net.status()
                    + "; roaming sent to the server: "
                    + net.sentRoaming()
                    + "; server holds: "
                    + (com.akashic.futura.server.ForgeModelServer.current() == null ? "-"
                        : com.akashic.futura.server.ForgeModelServer.current()
                            .roamingOf(serverPlayer(mc).getUniqueID())));
            // ysm.sync from the model's Molang: through the server and back
            com.akashic.futura.anim.PlayerAnimator a = PlayerModels.INSTANCE.animator(mc.thePlayer);
            com.akashic.futura.molang.Molang.parse("ysm.sync(1, 2.5, v.roaming.back)")
                .evaluate(a.context());
        });
        step("config-sync-back", () -> {
            float[] got = com.akashic.futura.net.MolangSync.lastReceived();
            report(
                "ysm.sync came back: " + (got == null ? "nothing" : java.util.Arrays.toString(got))
                    + " for the local animator: "
                    + (com.akashic.futura.net.MolangSync.lastTarget()
                        == PlayerModels.INSTANCE.animator(Minecraft.getMinecraft().thePlayer)));
            Minecraft mc = Minecraft.getMinecraft();
            PlayerModels.INSTANCE.use("wine_fox/08_sta");
            keepScreen = true;
            AnimationRouletteScreen screen = new AnimationRouletteScreen(LocalEmotes.INSTANCE.menu());
            mc.displayGuiScreen(screen);
            com.akashic.futura.model.LoadedModel model = PlayerModels.INSTANCE.model();
            if (model != null) {
                for (com.akashic.futura.format.pojo.manifest.settings.ExtraAnimationButton b : model.manifest.properties.extraAnimationButtons) {
                    for (com.akashic.futura.format.pojo.manifest.settings.ConfigForms f : b.configForms) {
                        if (com.akashic.futura.emote.ConfigForm.type(f)
                            == com.akashic.futura.emote.ConfigForm.Type.RADIO && f.value.contains("roaming")) {
                            screen.openConfig(b);
                            com.akashic.futura.emote.ConfigForm.clickLabel(
                                f,
                                PlayerModels.INSTANCE.animator(mc.thePlayer)
                                    .context(),
                                f.labels.size() - 1);
                            report("config sta: " + b.name + " radio " + f.value + " -> last label");
                            return;
                        }
                    }
                }
            }
        });
        step("config-sta-sent", () -> {
            keepScreen = false;
            Minecraft mc = Minecraft.getMinecraft();
            mc.displayGuiScreen(null);
            report("roaming sent for sta: " + com.akashic.futura.client.net.ClientNetwork.INSTANCE.sentRoaming());
            PlayerModels.INSTANCE.use("default");
        });
    }

    private void keys(boolean forward, boolean sneak, boolean sprint, boolean sword) {
        holdForward = forward;
        holdSneak = sneak;
        holdSprint = sprint;
        swingSword = sword;
        applyKeys();
        if (!sword) {
            EntityPlayerMP p = serverPlayer(Minecraft.getMinecraft());
            if (p != null) {
                p.inventory.setInventorySlotContents(p.inventory.currentItem, null);
            }
        }
    }

    private void applyKeys() {
        Minecraft mc = Minecraft.getMinecraft();
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), holdForward);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), holdSneak);
        if (mc.thePlayer != null) {
            mc.thePlayer.setSprinting(holdSprint && holdForward);
            // every 10 ticks, timed so that each shot catches a swing halfway
            if (swingSword && (ticks + 3) % 10 == 0) {
                mc.thePlayer.swingItem();
            }
        }
    }

    private void copySteps(String label, String builtin) {
        step(label + "-front", () -> {
            PlayerModels.INSTANCE.setEnabled(true);
            PlayerModels.INSTANCE.use(builtin);
            copies = 0;
            view(2, 0);
        });
        for (int n : COPIES) {
            step(label + "-copies-" + n, () -> {
                copies = n;
                view(1, 0);
            });
        }
    }

    // Stage 6: translucent faces sorted back to front ("only transl" runs just these). Each view twice: sorted, and
    // in the fixed emission order for comparison. Zoomed in (FOV 30) without the GUI.
    private void translucentSteps(String label, String builtin) {
        String[] views = { "front", "back", "side", "above" };
        int[] modes = { 2, 1, 2, 1 };
        float[] yaws = { 0, 0, 70, 150 };
        for (int i = 0; i < views.length; i++) {
            int v = i;
            for (boolean sorted : new boolean[] { true, false }) {
                step("transl-" + label + "-" + views[v] + (sorted ? "" : "-unsorted"), () -> {
                    Minecraft mc = Minecraft.getMinecraft();
                    PlayerModels.INSTANCE.setEnabled(true);
                    PlayerModels.INSTANCE.use(builtin);
                    copies = 0;
                    keys(false, false, false, false);
                    mc.gameSettings.hideGUI = true;
                    mc.gameSettings.fovSetting = 30;
                    com.akashic.futura.client.render.GeometryRenderer.sortTranslucent = sorted;
                    view(modes[v], yaws[v]);
                    if (views[v].equals("above")) {
                        EntityPlayerMP p = serverPlayer(mc);
                        if (p != null) {
                            p.playerNetServerHandler.setPlayerLocation(0.5, 4, 0.5, yaws[v], 50);
                        }
                    }
                });
            }
        }
        step("transl-" + label + "-end", () -> {
            Minecraft mc = Minecraft.getMinecraft();
            com.akashic.futura.client.render.GeometryRenderer.sortTranslucent = true;
            mc.gameSettings.fovSetting = 70;
            mc.gameSettings.hideGUI = false;
        });
    }

    private void step(String name, Runnable action) {
        names.add(name);
        steps.add(action);
    }

    private void view(int thirdPerson, float yaw) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.gameSettings.thirdPersonView = thirdPerson;
        EntityPlayerMP p = serverPlayer(mc);
        if (p != null) {
            p.playerNetServerHandler.setPlayerLocation(0.5, 4, 0.5, yaw, 10);
        }
    }

    private void report(String line) {
        Futura3D.LOG.info("[harness] {}", line);
        out.println(line);
        out.flush();
    }

    private static EntityPlayerMP serverPlayer(Minecraft mc) {
        MinecraftServer server = MinecraftServer.getServer();
        return server == null || mc.thePlayer == null ? null
            : server.getConfigurationManager()
                .func_152612_a(mc.thePlayer.getCommandSenderName());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.phase != TickEvent.Phase.END || done) {
            return;
        }
        if (!launched && mc.theWorld == null
            && (mc.currentScreen instanceof GuiMainMenu || mc.currentScreen != null && ++menuTicks > 60)) {
            launched = true;
            mc.gameSettings.pauseOnLostFocus = false;
            // Under Xvfb the grabbed pointer sends stray deltas every frame: only the harness turns the camera.
            mc.mouseHelper = new net.minecraft.util.MouseHelper() {

                @Override
                public void mouseXYChange() {
                    deltaX = 0;
                    deltaY = 0;
                }
            };
            mc.launchIntegratedServer(
                WORLD,
                WORLD,
                new WorldSettings(1L, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT));
            return;
        }
        EntityPlayerMP p = serverPlayer(mc);
        if (p == null || shotPending || measuring) {
            return;
        }
        mc.gameSettings.pauseOnLostFocus = false;
        if (mc.currentScreen != null && !keepScreen) {
            mc.displayGuiScreen(null);
        }
        if (index < 0) {
            p.getServerForPlayer()
                .setWorldTime(6000);
            report(
                "model: " + (PlayerModels.INSTANCE.model() == null ? "none"
                    : PlayerModels.INSTANCE.model()
                        .name() + ", bones="
                        + PlayerModels.INSTANCE.model().main.bones.length
                        + ", cubes="
                        + PlayerModels.INSTANCE.model().main.cubeCount
                        + ", quads="
                        + PlayerModels.INSTANCE.model().main.quadCount()));
            report("gl: " + GL11.glGetString(GL11.GL_RENDERER) + " / " + GL11.glGetString(GL11.GL_VERSION));
            index = 0;
            ticks = 0;
            steps.get(0)
                .run();
            return;
        }
        applyKeys();
        itemTick();
        replHold();
        fishTick();
        elytraTick();
        if (++ticks >= (settle > 0 ? settle : SETTLE_TICKS)) {
            if (names.get(index)
                .contains("-copies-")) {
                measuring = true;
                frames = 0;
                lastFrame = 0;
            } else {
                shotPending = true;
            }
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (copies <= 0 || index < 0 || done) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        long start = System.nanoTime();
        RenderHelper.enableStandardItemLighting();
        int light = mc.theWorld.getLightBrightnessForSkyBlocks(0, 4, 0, 0);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, light % 65536, light / 65536f);
        // A grid in front of the player (north of the spawn, facing south toward the camera).
        int perRow = 10;
        for (int i = 0; i < copies; i++) {
            double wx = 0.5 + (i % perRow - (perRow - 1) / 2.0) * 1.2;
            double wz = 0.5 + 4 + (i / perRow) * 1.6;
            GL11.glPushMatrix();
            GL11.glTranslated(
                wx - RenderManager.renderPosX,
                4 - RenderManager.renderPosY,
                wz - RenderManager.renderPosZ);
            PlayerModels.INSTANCE
                .drawAt(PlayerModels.INSTANCE.model().widthScale, PlayerModels.INSTANCE.model().heightScale);
            GL11.glPopMatrix();
        }
        RenderHelper.disableStandardItemLighting();
        if (measuring && frames < MEASURE_FRAMES) {
            drawNanos[frames] = System.nanoTime() - start;
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done || index < 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (measuring) {
            long now = System.nanoTime();
            if (lastFrame != 0 && frames < MEASURE_FRAMES) {
                frameNanos[frames] = now - lastFrame;
                if (copies == 0) {
                    drawNanos[frames] = 0;
                }
                frames++;
            }
            lastFrame = now;
            if (frames < MEASURE_FRAMES) {
                return;
            }
            measuring = false;
            report(
                String.format(
                    Locale.ROOT,
                    "%s copies=%d frame ms: mean %.2f p50 %.2f p99 %.2f | model draw (CPU) ms: mean %.3f p99 %.3f",
                    names.get(index),
                    copies,
                    mean(frameNanos),
                    pct(frameNanos, 0.5),
                    pct(frameNanos, 0.99),
                    mean(drawNanos),
                    pct(drawNanos, 0.99)));
            shotPending = true;
        }
        if (!shotPending) {
            return;
        }
        String name = names.get(index);
        if (atShot != null) {
            Runnable r = atShot;
            atShot = null;
            r.run();
        }
        ScreenShotHelper.saveScreenshot(
            mc.mcDataDir,
            "futura3d-" + name + ".png",
            mc.displayWidth,
            mc.displayHeight,
            mc.getFramebuffer());
        report("shot " + name);
        if (fake != null) {
            report("  RightHandLocator " + Arrays.toString(PlayerModels.INSTANCE.locator(fake, "RightHandLocator")));
        }
        shotPending = false;
        ticks = 0;
        settle = -1;
        fishAt = -1;
        fishAction = null;
        index++;
        if (index >= steps.size()) {
            done = true;
            copies = 0;
            report("done");
            out.close();
            mc.shutdown();
            return;
        }
        steps.get(index)
            .run();
    }

    private static double mean(long[] nanos) {
        long sum = 0;
        for (long n : nanos) {
            sum += n;
        }
        return sum / (double) nanos.length / 1e6;
    }

    private static double pct(long[] nanos, double p) {
        long[] sorted = Arrays.copyOf(nanos, nanos.length);
        Arrays.sort(sorted);
        return sorted[MathHelper.clamp_int((int) (p * (sorted.length - 1)), 0, sorted.length - 1)] / 1e6;
    }

    // ---- Phase 3a: items at the locators, sleeping, hurt tint ----

    private static final int FAKE_ID = -4242;
    private EntityOtherPlayerMP fake;
    private float fakeYaw;
    private int fakeHurt;
    /**
     * The second player moved like a network player: 0 still, 1 walking along x, 2 hovering 2 blocks up, 3 sprinting
     * under water, 4 still under water.
     */
    private int fakeWalk;
    private boolean fakeDying;
    private ItemStack fakeUse;
    private boolean holdUse;
    private int tapUse = -1;
    private Runnable atTick5;
    private Runnable atTick30;
    /** Runs right before the step's screenshot. */
    private Runnable atShot;
    private boolean itemModelLoaded;

    /**
     * Items in the hand, from the front on the local player (walking too) and from the side on a second player that
     * the local player looks at; the bow drawn, the rod cast, a block, a pumpkin on the head, the hurt tint and a
     * player asleep in a bed. Each model step has a vanilla twin for comparison.
     */
    private void itemSteps() {
        String[][] held = { { "sword", "diamond_sword" }, { "dirt", "dirt" } };
        for (String[] h : held) {
            for (boolean vanilla : new boolean[] { false, true }) {
                String label = "item-" + (vanilla ? "vanilla-" : "") + h[0];
                step(label + "-idle", () -> localItem(vanilla, h[1], false));
                if (!vanilla) {
                    step(label + "-walk", () -> {
                        holdForward = true;
                        applyKeys();
                    });
                }
            }
        }
        for (boolean vanilla : new boolean[] { false, true }) {
            String label = "item-" + (vanilla ? "vanilla-" : "");
            step(label + "bow-draw", () -> localItem(vanilla, "bow", true));
            step(label + "rod", () -> localItem(vanilla, "fishing_rod", false));
            step(label + "rod-cast", () -> tapUse = 2);
        }
        step("item-rod-reel", () -> {
            tapUse = 2;
            holdUse = false;
        });
        for (boolean vanilla : new boolean[] { false, true }) {
            String label = "item-side-" + (vanilla ? "vanilla-" : "");
            step(label + "sword", () -> fakeItem(vanilla, new ItemStack(Items.diamond_sword), false, 90));
            step(label + "bow", () -> fakeItem(vanilla, new ItemStack(Items.bow), true, 90));
            step(label + "rod", () -> fakeItem(vanilla, new ItemStack(Items.fishing_rod), false, 90));
            step(label + "dirt", () -> fakeItem(vanilla, new ItemStack(Blocks.dirt), false, 90));
            step(label + "pumpkin", () -> {
                fakeItem(vanilla, new ItemStack(Items.diamond_sword), false, 180);
                fake.inventory.armorInventory[3] = new ItemStack(Blocks.pumpkin);
            });
        }
        step("item-hurt", () -> {
            fakeItem(false, new ItemStack(Items.diamond_sword), false, 180);
            fakeHurt = 8;
        });
        step("item-vanilla-hurt", () -> {
            fakeItem(true, new ItemStack(Items.diamond_sword), false, 180);
            fakeHurt = 8;
        });
        for (boolean vanilla : new boolean[] { false, true }) {
            String v = vanilla ? "vanilla-" : "";
            step("item-" + v + "invisible", () -> {
                fakeItem(vanilla, new ItemStack(Items.diamond_sword), false, 180);
                fake.setInvisible(true);
            });
            step("item-" + v + "death", () -> {
                fakeItem(vanilla, new ItemStack(Items.diamond_sword), false, 90);
                fakeDying = true;
            });
        }
        step("item-attributes", () -> {
            fakeItem(false, new ItemStack(Items.diamond_sword), false, 180);
            // ysm.bone_color, bone_transparency and bone_glow as an animation would write them: a blue head, every
            // bone at alpha 80 (only translucent faces fade) and the legs at light level 0.
            com.akashic.futura.anim.BoneAttributes a = PlayerModels.INSTANCE.animator(fake)
                .attributes();
            com.akashic.futura.geo.BakedGeometry g = PlayerModels.INSTANCE.model().main;
            java.util.Arrays.fill(a.alpha, 80);
            setIfPresent(a.color, g.indexOf("Head"), 0x6080FF);
            setIfPresent(a.glow, g.indexOf("LeftLeg"), 0);
            setIfPresent(a.glow, g.indexOf("RightLeg"), 0);
        });
        step("item-attributes-reset", () -> {
            PlayerModels.INSTANCE.animator(fake)
                .attributes()
                .reset();
            PlayerModels.INSTANCE.use("wine_fox/18_wedding");
            fakeItem(false, new ItemStack(Items.diamond_sword), false, 180);
        });
        step("item-translucent-side", () -> fakeItem(false, new ItemStack(Items.diamond_sword), false, 90));
        step("item-translucent-default", () -> {
            PlayerModels.INSTANCE.use("default");
            fakeItem(false, new ItemStack(Items.diamond_sword), false, 180);
        });
        for (boolean vanilla : new boolean[] { false, true }) {
            step("item-" + (vanilla ? "vanilla-" : "") + "sleep", () -> sleepStep(vanilla));
        }
        step("item-end", () -> {
            removeFake();
            clearBed();
            Minecraft mc = Minecraft.getMinecraft();
            mc.gameSettings.hideGUI = false;
            PlayerModels.INSTANCE.setEnabled(true);
            keys(false, false, false, false);
        });
    }

    /** The harness file can narrow the steps: {@code items [part...]} or {@code only part...}. */
    private void onlySteps() {
        File file = new File(Minecraft.getMinecraft().mcDataDir, "futura3d-harness");
        String mode;
        try {
            mode = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return;
        }
        String[] words = mode.trim()
            .split("\\s+");
        // "items [part...]": the phase 3a steps (those whose name contains a part); "only part...": any step whose
        // name contains a part.
        boolean items = words[0].equals("items");
        if (!items && !words[0].equals("only")) {
            return;
        }
        String prefix = items ? "item-" : "";
        for (int i = names.size() - 1; i >= 0; i--) {
            String name = names.get(i);
            boolean keep = name.startsWith(prefix) && (words.length < 2 || name.equals("item-end"));
            for (int w = 1; w < words.length && !keep; w++) {
                keep = name.startsWith(prefix) && name.contains(words[w]);
            }
            if (!keep) {
                names.remove(i);
                steps.remove(i);
            }
        }
    }

    private void localItem(boolean vanilla, String item, boolean use) {
        removeFake();
        clearBed();
        PlayerModels.INSTANCE.setEnabled(!vanilla);
        if (!itemModelLoaded) {
            itemModelLoaded = PlayerModels.INSTANCE.use("default");
        }
        Minecraft.getMinecraft().gameSettings.hideGUI = false;
        keys(false, false, false, false);
        holdUse = false;
        EntityPlayerMP p = serverPlayer(Minecraft.getMinecraft());
        if (p != null) {
            p.inventory.setInventorySlotContents(
                p.inventory.currentItem,
                new ItemStack((net.minecraft.item.Item) net.minecraft.item.Item.itemRegistry.getObject(item)));
        }
        view(2, 0);
        if (use) {
            atTick5 = () -> holdUse = true;
        }
    }

    private void fakeItem(boolean vanilla, ItemStack stack, boolean use, float yaw) {
        Minecraft mc = Minecraft.getMinecraft();
        PlayerModels.INSTANCE.setEnabled(!vanilla);
        holdUse = false;
        fakeHurt = 0;
        fakeDying = false;
        keys(false, false, false, false);
        clearBed();
        mc.gameSettings.hideGUI = true;
        if (fake == null || fake.isPlayerSleeping() || fake.getHealth() <= 0) {
            removeFake();
            fake = new EntityOtherPlayerMP(mc.theWorld, new GameProfile(java.util.UUID.randomUUID(), "futura3d"));
            fake.setPositionAndRotation(0.5, 4 + fake.yOffset, 3.5, yaw, 0);
            mc.theWorld.addEntityToWorld(FAKE_ID, fake);
        }
        fakeYaw = yaw;
        fake.setInvisible(false);
        fake.inventory.armorInventory[3] = null;
        fake.inventory.setInventorySlotContents(fake.inventory.currentItem, stack);
        fakeUse = use ? stack : null;
        if (use) {
            fake.setItemInUse(stack, stack.getMaxItemUseDuration());
        } else {
            fake.clearItemInUse();
        }
        look(0, 0, 18);
    }

    private void sleepStep(boolean vanilla) {
        Minecraft mc = Minecraft.getMinecraft();
        removeFake();
        PlayerModels.INSTANCE.setEnabled(!vanilla);
        mc.gameSettings.hideGUI = true;
        fakeHurt = 0;
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null) {
            net.minecraft.world.World w = server.worldServers[0];
            w.difficultySetting = EnumDifficulty.PEACEFUL;
            // Foot at z = 3, head at z = 4 (direction 0: the head toward +z).
            w.setBlock(0, 4, 3, Blocks.bed, 0, 3);
            w.setBlock(0, 4, 4, Blocks.bed, 8, 3);
        }
        lookFrom(2.8, 3.8, 0, 90, 35);
        atTick5 = () -> {
            fake = new EntityOtherPlayerMP(mc.theWorld, new GameProfile(java.util.UUID.randomUUID(), "futura3d"));
            fake.setPositionAndRotation(0.5, 4 + fake.yOffset, 2.5, 0, 0);
            mc.theWorld.addEntityToWorld(FAKE_ID, fake);
            fake.sleepInBedAt(0, 4, 4);
        };
    }

    private static void setIfPresent(int[] values, int index, int value) {
        if (index >= 0) {
            values[index] = value;
        }
    }

    private static void clearBed() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null) {
            server.worldServers[0].setBlockToAir(0, 4, 3);
            server.worldServers[0].setBlockToAir(0, 4, 4);
        }
    }

    private void removeFake() {
        if (fake != null) {
            Minecraft.getMinecraft().theWorld.removeEntityFromWorld(FAKE_ID);
            fake = null;
        }
    }

    private void look(int thirdPerson, float yaw, float pitch) {
        lookFrom(0.5, 0.5, thirdPerson, yaw, pitch);
    }

    private void lookFrom(double x, double z, int thirdPerson, float yaw, float pitch) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.gameSettings.thirdPersonView = thirdPerson;
        EntityPlayerMP p = serverPlayer(mc);
        if (p != null) {
            p.inventory.setInventorySlotContents(p.inventory.currentItem, null);
            p.playerNetServerHandler.setPlayerLocation(x, 4, z, yaw, pitch);
        }
    }

    /** Every tick: the use key, the second player's yaw, bow and hurt time. */
    private void itemTick() {
        Minecraft mc = Minecraft.getMinecraft();
        leakTick();
        if (ticks == 5 && atTick5 != null) {
            Runnable r = atTick5;
            atTick5 = null;
            r.run();
        }
        if (ticks == 30 && atTick30 != null) {
            Runnable r = atTick30;
            atTick30 = null;
            r.run();
        }
        boolean tap = tapUse >= 0 && ticks == tapUse;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), holdUse || tap);
        if (fake != null && !fake.isPlayerSleeping()) {
            fake.rotationYaw = fake.prevRotationYaw = fakeYaw;
            fake.rotationYawHead = fake.prevRotationYawHead = fakeYaw;
            fake.renderYawOffset = fake.prevRenderYawOffset = fakeYaw;
            fake.onGround = true;
            if (fakeWalk >= 3) {
                fake.setSprinting(fakeWalk == 3);
                fake.setPositionAndRotation2(1.5 - 0.03 * ticks, 1.4, 3.5, fakeYaw, 0, 3);
                fake.onGround = false;
            } else if (fakeWalk > 0) {
                // as NetHandlerPlayClient moves him: an interpolation target, and 1.7.10 never sets his ground
                double x = fakeWalk == 1 ? 2.5 - 0.1 * ticks : 0.5;
                fake.setPositionAndRotation2(x, fakeWalk == 2 ? 6 : 4, 3.5, fakeYaw, 0, 3);
                fake.onGround = false;
            } else if (index >= 0 && index < names.size()
                && names.get(index)
                    .startsWith("remote")) {
                        fake.onGround = false;
                    }
            if (fakeUse != null && fake.getItemInUseCount() < 2) {
                fake.setItemInUse(fakeUse, fakeUse.getMaxItemUseDuration());
            }
            if (fakeDying && ticks >= 24) {
                // Dying from tick 24, so that the shot (tick 40) falls inside the one-second death animation; held
                // halfway through the 20 ticks of dying (at 20 the entity is removed).
                fake.setHealth(0);
                fake.deathTime = 10;
            }
            if (fakeHurt > 0) {
                fake.hurtTime = fakeHurt;
                fake.maxHurtTime = 10;
            }
        }
        if (ticks > tapUse + 1) {
            tapUse = -1;
        }
    }

    /** Draws the locator frames of the second player as axes (x red, y green, z blue), through the locator API. */
    @SubscribeEvent
    public void onRenderWorldLastLocators(RenderWorldLastEvent event) {
        if (fake == null || done || !PlayerModels.INSTANCE.active()) {
            return;
        }
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LINE_BIT);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glLineWidth(3);
        for (String name : new String[] { "RightHandLocator", "Head" }) {
            float[] m = PlayerModels.INSTANCE.locator(fake, name);
            if (m == null) {
                continue;
            }
            java.nio.FloatBuffer b = org.lwjgl.BufferUtils.createFloatBuffer(16);
            b.put(m)
                .flip();
            GL11.glPushMatrix();
            GL11.glMultMatrix(b);
            GL11.glBegin(GL11.GL_LINES);
            GL11.glColor3f(1, 0, 0);
            GL11.glVertex3f(0, 0, 0);
            GL11.glVertex3f(0.3f, 0, 0);
            GL11.glColor3f(0, 1, 0);
            GL11.glVertex3f(0, 0, 0);
            GL11.glVertex3f(0, 0.3f, 0);
            GL11.glColor3f(0, 0, 1);
            GL11.glVertex3f(0, 0, 0);
            GL11.glVertex3f(0, 0, 0.3f);
            GL11.glEnd();
            GL11.glPopMatrix();
        }
        GL11.glPopAttrib();
    }

    // ---- Phase 4b: a model per player, the picker, server-style models ----

    private static final String WEDDING = "builtin:wine_fox/18_wedding";

    /**
     * The picker (opened on the default model with its blue texture, the pointer on a card for its tooltip), a model
     * chosen through {@code requestLocal}, two players with different models, models registered as a server would
     * (from a folder and from memory, one locked) and drawn with their textures uploaded from the model's files, and
     * the fallback once they are cleared. Restores the default model and deletes the saved local choice at the end.
     */
    private void pickerSteps() {
        step("picker-open", () -> {
            Minecraft mc = Minecraft.getMinecraft();
            removeFake();
            clearBed();
            PlayerModels.INSTANCE.setEnabled(true);
            PlayerModels.INSTANCE.force(null);
            keys(false, false, false, false);
            mc.gameSettings.hideGUI = false;
            view(2, 0);
            keepScreen = true;
            mc.displayGuiScreen(
                new com.akashic.futura.client.gui.ModelPickerScreen().focus(ModelRegistry.DEFAULT, "blue"));
        });
        // Same screen, 2 s later: the page's models have loaded; the pointer on the second card.
        step("picker", () -> pointAtCard("builtin:misc/1_alex"));
        // Only the CC0 models (the pictures in docs/img show no model under another licence).
        step("picker-cc0", () -> {
            Minecraft.getMinecraft()
                .displayGuiScreen(
                    new com.akashic.futura.client.gui.ModelPickerScreen().only(RenderHarness::publicDomain)
                        .focus(ModelRegistry.DEFAULT, "blue"));
            pointAtCard("builtin:misc/1_alex");
        });
        // The second page: authored GUI poses (preview_animation) on the cards, a second texture in the preview.
        step(
            "picker-page2",
            () -> Minecraft.getMinecraft()
                .displayGuiScreen(
                    new com.akashic.futura.client.gui.ModelPickerScreen()
                        .focus("builtin:wine_fox/14_momo", "skin_green")));
        step("picker-page2-loaded", () -> {});
        step("picker-wedding", () -> {
            keepScreen = false;
            Minecraft.getMinecraft()
                .displayGuiScreen(null);
            ModelSelection.INSTANCE.requestLocal(WEDDING, null);
            ModelRegistry.INSTANCE.loadNow(WEDDING);
            view(2, 0);
            report(
                "local model: " + PlayerModels.INSTANCE.keyOf(Minecraft.getMinecraft().thePlayer)
                    + ", saved: "
                    + ModelSelection.INSTANCE.localKey());
        });
        step("picker-two", () -> {
            spawnBeside();
            ModelSelection.INSTANCE.select(fake.getUniqueID(), ModelRegistry.DEFAULT, "blue");
            view(2, 0);
        });
        step("picker-server", () -> {
            try {
                ModelRegistry.INSTANCE.registerServerModel(
                    "server:boy_folder",
                    new FolderModelSource(builtinDir("misc/3_default_boy")),
                    false);
                ModelRegistry.INSTANCE.registerServerModel(
                    "server:alex_memory",
                    new MemoryModelSource("alex_memory", readTree(builtinDir("misc/1_alex"))),
                    false);
                ModelRegistry.INSTANCE.registerServerModel(
                    "server:steve_locked",
                    new MemoryModelSource("steve_locked", readTree(builtinDir("misc/2_steve"))),
                    true);
            } catch (java.io.IOException e) {
                report("server models: " + e);
            }
            ModelSelection.INSTANCE.requestLocal("server:boy_folder", "red");
            if (fake == null) {
                spawnBeside();
            }
            ModelSelection.INSTANCE.select(fake.getUniqueID(), "server:alex_memory", null);
            view(2, 0);
        });
        step("picker-server-list", () -> {
            report(
                "server models ready: boy " + ModelRegistry.INSTANCE.isReady("server:boy_folder")
                    + ", alex "
                    + ModelRegistry.INSTANCE.isReady("server:alex_memory")
                    + "; local "
                    + PlayerModels.INSTANCE.modelOf(Minecraft.getMinecraft().thePlayer).key
                    + " texture "
                    + PlayerModels.INSTANCE.texture()
                    + "; other "
                    + PlayerModels.INSTANCE.modelOf(fake).key
                    + " texture "
                    + PlayerModels.INSTANCE.textureOf(PlayerModels.INSTANCE.modelOf(fake), fake));
            keepScreen = true;
            Minecraft.getMinecraft()
                .displayGuiScreen(
                    new com.akashic.futura.client.gui.ModelPickerScreen().only(RenderHarness::publicDomain)
                        .focus("server:alex_memory", null));
        });
        step("picker-server-hover", () -> pointAtCard("server:steve_locked"));
        step("picker-cleared", () -> {
            keepScreen = false;
            Minecraft.getMinecraft()
                .displayGuiScreen(null);
            ModelRegistry.INSTANCE.clearServerModels();
            ModelSelection.INSTANCE.clearRemote();
            report(
                "after clearing: keys " + ModelRegistry.INSTANCE.keys()
                    .size()
                    + ", local "
                    + PlayerModels.INSTANCE.modelOf(Minecraft.getMinecraft().thePlayer).key
                    + ", other "
                    + PlayerModels.INSTANCE.modelOf(fake).key);
            view(2, 0);
        });
        step("picker-end", () -> {
            ModelSelection.INSTANCE.requestLocal(null, null);
            removeFake();
            view(2, 0);
        });
    }

    private static boolean publicDomain(String key) {
        com.akashic.futura.model.ModelInfo info = ModelRegistry.INSTANCE.info(key);
        return info == null || info.licenseKind() == com.akashic.futura.model.ModelInfo.LicenseKind.PUBLIC_DOMAIN;
    }

    private void spawnBeside() {
        Minecraft mc = Minecraft.getMinecraft();
        removeFake();
        fake = new EntityOtherPlayerMP(mc.theWorld, new GameProfile(java.util.UUID.randomUUID(), "futura3d"));
        fake.setPositionAndRotation(1.9, 4 + fake.yOffset, 0.5, 0, 0);
        mc.theWorld.addEntityToWorld(FAKE_ID, fake);
        fakeYaw = 0;
        fakeHurt = 0;
        fakeDying = false;
        fakeUse = null;
    }

    private void pointAtCard(String key) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!(mc.currentScreen instanceof com.akashic.futura.client.gui.ModelPickerScreen)) {
            return;
        }
        int[] c = ((com.akashic.futura.client.gui.ModelPickerScreen) mc.currentScreen).cardCenter(key);
        if (c == null) {
            report("card not on the page: " + key);
            return;
        }
        int scale = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
        Mouse.setCursorPosition(c[0] * scale, mc.displayHeight - c[1] * scale - 1);
    }

    /**
     * A built-in model's folder on disk: where the resources are unpacked, or a copy taken out of the development jar
     * into {@code futura3d-harness-models} in the run directory.
     */
    private static File builtinDir(String folder) throws java.io.IOException {
        String base = "assets/futura3d/builtin/" + folder + "/";
        java.net.URL url = RenderHarness.class.getResource("/" + base + "ysm.json");
        if (url == null) {
            throw new java.io.IOException("No built-in model " + folder);
        }
        if ("file".equals(url.getProtocol())) {
            try {
                return new File(url.toURI()).getParentFile();
            } catch (java.net.URISyntaxException e) {
                throw new java.io.IOException(e);
            }
        }
        File out = new File(Minecraft.getMinecraft().mcDataDir, "futura3d-harness-models/" + folder);
        java.util.jar.JarFile jar = ((java.net.JarURLConnection) url.openConnection()).getJarFile();
        for (java.util.Enumeration<java.util.jar.JarEntry> e = jar.entries(); e.hasMoreElements();) {
            java.util.jar.JarEntry entry = e.nextElement();
            if (entry.isDirectory() || !entry.getName()
                .startsWith(base)) {
                continue;
            }
            File target = new File(
                out,
                entry.getName()
                    .substring(base.length()));
            target.getParentFile()
                .mkdirs();
            try (java.io.InputStream in = jar.getInputStream(entry)) {
                java.nio.file.Files.copy(in, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return out;
    }

    /** Every file under a folder, by relative path, as a server's transfer would hand them over. */
    private static java.util.Map<String, byte[]> readTree(File dir) throws java.io.IOException {
        java.util.Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        java.nio.file.Path root = dir.toPath();
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) walk::iterator) {
                if (java.nio.file.Files.isRegularFile(p)) {
                    files.put(
                        root.relativize(p)
                            .toString()
                            .replace(File.separatorChar, '/'),
                        java.nio.file.Files.readAllBytes(p));
                }
            }
        }
        return files;
    }
}
