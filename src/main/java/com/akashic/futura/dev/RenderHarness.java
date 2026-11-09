package com.akashic.futura.dev;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.GL11;

import com.akashic.futura.Futura3D;
import com.akashic.futura.client.PlayerModels;

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
        copySteps("default", "default");
        // Models of the level the port targets (1554 cubes) and a stress case (4533 cubes).
        copySteps("wedding", "wine_fox/18_wedding");
        copySteps("sta", "wine_fox/08_sta");
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
        if (mc.currentScreen != null) {
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
}
