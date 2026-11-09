package com.akashic.futura.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityFishHook;

import com.akashic.futura.geo.LocatorTransforms;
import com.akashic.futura.geo.Mat4;

/**
 * The vanilla fishing line of a player drawn with a model starts at the cast rod in the model's hand (the top end of
 * the stick {@code HeldItemRenderer} draws at {@code RightHandLocator}) instead of 1.7.10's fixed offsets from the
 * biped's hand ({@code RenderFish.doRender}). Upstream keeps vanilla's offsets for the line; on 1.7.10 they point
 * well away from a model's hand, so this follows the locator.
 *
 * <p>
 * Only the line's vertices move ({@code mixin/mixins/RenderFishMixin} redirects {@code Tessellator.addVertex}); the
 * curve is vanilla's ({@code y·(f²+f)/2 + 0.25}, 16 segments). A mod that draws its own line by cancelling
 * {@code doRender} at {@code startDrawing} (Kunftig's 3D rods) does so before any of these vertices, so both compose
 * whatever order the mixins are applied in. Client thread only.
 */
public final class FishingLine {

    private static final int SEGMENTS = 16;
    private static final float[] TIP = LocatorTransforms.castRodTip();

    private static boolean active;
    private static int vertex;
    private static double hookX, hookY, hookZ;
    private static double dx, dy, dz;

    private FishingLine() {}

    /**
     * Start of {@code RenderFish.doRender}: works out where this hook's line starts.
     *
     * @param x render position of the hook (relative to the camera), as {@code doRender} gets it
     */
    public static void begin(EntityFishHook hook, double x, double y, double z) {
        active = false;
        vertex = 0;
        EntityPlayer angler = hook.field_146042_b;
        if (angler == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (angler == mc.thePlayer && mc.gameSettings.thirdPersonView == 0) {
            return; // first person: the vanilla hand, vanilla line
        }
        float[] hand = PlayerModels.INSTANCE.locator(angler, ThirdPersonRenderer.RIGHT_HAND);
        if (hand == null) {
            return;
        }
        float[] tip = new float[3];
        Mat4.transformPoint(hand, 0, TIP[0], TIP[1], TIP[2], tip);
        if (!finite(tip[0]) || !finite(tip[1]) || !finite(tip[2])) {
            return;
        }
        hookX = x;
        hookY = y;
        hookZ = z;
        // vanilla's (start - hook) with the hook point 0.25 up, from which the curve hangs
        dx = tip[0] - x;
        dy = tip[1] - (y + 0.25);
        dz = tip[2] - z;
        active = true;
    }

    /** A vertex of the line: moved to start at the model's rod when {@link #begin} found one. */
    public static void vertex(Tessellator tess, double x, double y, double z) {
        if (!active) {
            tess.addVertex(x, y, z);
            return;
        }
        float f = (float) vertex / SEGMENTS;
        tess.addVertex(hookX + dx * f, hookY + dy * (f * f + f) * 0.5 + 0.25, hookZ + dz * f);
        if (++vertex > SEGMENTS) {
            active = false;
        }
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }
}
