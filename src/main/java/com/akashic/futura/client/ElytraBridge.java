package com.akashic.futura.client;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.akashic.futura.Futura3D;

/**
 * Et Futurum Requiem's elytra ({@code etfuturum}, "backlytra"), read by reflection so neither mod needs the other. EFR
 * backports the 1.9 elytra onto the vanilla biped: the glide flag on the player (a mixin adding
 * {@code etfu$isElytraFlying}), the body's tilt in {@code RenderPlayer.rotateCorpse} and the wings in the armor pass
 * ({@code LayerBetterElytra} from {@code RenderLivingEvent.SetArmorModel}). A player drawn with a model skips both, so
 * he glided standing up, in the "flying" pose and without wings: the model needs the flag (its {@code elytra_fly}
 * clip lays the body down, as upstream, where the vanilla tilt does not run either) and the wings at its
 * {@code ElytraLocator}, as upstream's {@code CustomPlayerElytraLayer} puts them. The wings are EFR's own layer, so
 * the texture (the cape when the player has one), the enchantment glint and the wing angles stay EFR's.
 */
final class ElytraBridge {

    private static final String API_PLAYER = "ganymedes01.etfuturum.api.elytra.IElytraPlayer";
    private static final String ITEM = "ganymedes01.etfuturum.items.equipment.ItemArmorElytra";
    private static final String LAYER = "ganymedes01.etfuturum.client.renderer.entity.elytra.LayerBetterElytra";

    private static boolean resolved;
    private static Class<?> apiPlayer;
    private static Method getElytra;
    private static Method renderLayer;
    private static boolean failed;

    /** Per player class: {flying, unused, rotX, rotY, rotZ} (the rotations may be null). */
    private static final Map<Class<?>, Method[]> ACCESSORS = new ConcurrentHashMap<>();
    private static final Method[] NONE = new Method[5];

    private ElytraBridge() {}

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        ClassLoader loader = ElytraBridge.class.getClassLoader();
        try {
            apiPlayer = Class.forName(API_PLAYER, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return; // not installed
        }
        try {
            getElytra = Class.forName(ITEM, false, loader)
                .getMethod("getElytra", EntityLivingBase.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            Futura3D.LOG.warn("Et Futurum Requiem's elytra item is unreachable; no wings on the models", e);
        }
        try {
            renderLayer = Class.forName(LAYER, false, loader)
                .getMethod(
                    "doRenderLayer",
                    EntityLivingBase.class,
                    float.class,
                    float.class,
                    float.class,
                    float.class,
                    float.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            Futura3D.LOG.warn("Et Futurum Requiem's elytra layer is unreachable; no wings on the models", e);
        }
        Futura3D.LOG.info("Et Futurum Requiem found: its elytra glide on the player models");
    }

    /** Whether EFR's elytra exist in this game. */
    static boolean present() {
        resolve();
        return apiPlayer != null;
    }

    private static Method[] accessors(EntityPlayer player) {
        Class<?> type = player.getClass();
        Method[] m = ACCESSORS.get(type);
        if (m == null) {
            m = NONE;
            if (apiPlayer != null && apiPlayer.isInstance(player)) {
                m = new Method[5];
                m[0] = method(type, "etfu$isElytraFlying");
                m[2] = method(type, "getRotateElytraX");
                m[3] = method(type, "getRotateElytraY");
                m[4] = method(type, "getRotateElytraZ");
                if (m[0] == null) {
                    m = NONE;
                }
            }
            ACCESSORS.put(type, m);
        }
        return m;
    }

    private static Method method(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static Object call(Method m, Object target) {
        if (m == null || failed) {
            return null;
        }
        try {
            return m.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true;
            Futura3D.LOG.warn("Et Futurum Requiem's elytra are unreadable; the models glide no more", e);
            return null;
        }
    }

    /** Whether the player glides with an elytra (1.9's {@code isElytraFlying}). */
    static boolean flying(EntityPlayer player) {
        if (!present() || player == null) {
            return false;
        }
        Object v = call(accessors(player)[0], player);
        return v instanceof Boolean && (Boolean) v;
    }

    /**
     * The wing angles EFR's elytra model last set for the player, in degrees ({@code ysm.elytra_rot_*}), or null. EFR
     * eases them every time the wings are drawn, which for a modelled player is our own {@link #drawWings}.
     */
    static float[] wingRotation(EntityPlayer player) {
        if (!present() || player == null) {
            return null;
        }
        Method[] m = accessors(player);
        Object x = call(m[2], player);
        Object y = call(m[3], player);
        Object z = call(m[4], player);
        if (!(x instanceof Number) || !(y instanceof Number) || !(z instanceof Number)) {
            return null;
        }
        return new float[] { (float) Math.toDegrees(((Number) x).floatValue()),
            (float) Math.toDegrees(((Number) y).floatValue()), (float) Math.toDegrees(((Number) z).floatValue()) };
    }

    /** The elytra the player wears (chest slot, or Baubles Expanded as EFR decides), or null. */
    static ItemStack elytra(EntityPlayer player) {
        if (!present() || getElytra == null || player == null || failed) {
            return null;
        }
        try {
            Object v = getElytra.invoke(null, player);
            return v instanceof ItemStack ? (ItemStack) v : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true;
            Futura3D.LOG.warn("Et Futurum Requiem's elytra item is unreadable; no wings on the models", e);
            return null;
        }
    }

    /**
     * Draws EFR's wings in the current GL frame, which must be the vanilla biped's body frame (pixels times 1/16, y
     * down, x mirrored, the neck at the origin): what {@code SetArmorModel} gives EFR's layer.
     *
     * @return false when it could not
     */
    static boolean drawWings(AbstractClientPlayer player, float partialTicks) {
        if (renderLayer == null || failed) {
            return false;
        }
        try {
            renderLayer.invoke(
                null,
                player,
                player.limbSwing,
                player.limbSwingAmount,
                partialTicks,
                (float) player.ticksExisted,
                0.0625F);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true;
            Futura3D.LOG.warn("Et Futurum Requiem's elytra layer failed; no wings on the models", e);
            return false;
        }
    }
}
