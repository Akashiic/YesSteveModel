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
import com.akashic.futura.client.PlayerModels;
import com.akashic.futura.client.gui.AnimationRouletteScreen;
import com.akashic.futura.emote.RouletteMenu;
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
        itemSteps();
        serverSteps();
        onlySteps();
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
        if (++ticks >= SETTLE_TICKS) {
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
    private boolean fakeDying;
    private ItemStack fakeUse;
    private boolean holdUse;
    private int tapUse = -1;
    private Runnable atTick5;
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
        if (ticks == 5 && atTick5 != null) {
            Runnable r = atTick5;
            atTick5 = null;
            r.run();
        }
        boolean tap = tapUse >= 0 && ticks == tapUse;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), holdUse || tap);
        if (fake != null && !fake.isPlayerSleeping()) {
            fake.rotationYaw = fake.prevRotationYaw = fakeYaw;
            fake.rotationYawHead = fake.prevRotationYawHead = fakeYaw;
            fake.renderYawOffset = fake.prevRenderYawOffset = fakeYaw;
            fake.onGround = true;
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
}
