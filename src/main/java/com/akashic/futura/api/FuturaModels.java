package com.akashic.futura.api;

import java.util.function.Predicate;

import net.minecraft.entity.player.EntityPlayer;

import com.akashic.futura.client.PlayerModels;

import cpw.mods.fml.common.FMLCommonHandler;

/**
 * The stable entry point for other mods: whether a player is drawn with a model, and where the model's locators are.
 * Only JDK and Minecraft types in the signatures, so a mod that does not depend on 3D Futura Engine can call it by
 * reflection (Kunftig does: {@code Class.forName("com.akashic.futura.api.FuturaModels")}, method handles looked up
 * once). Methods are kept compatible once published; new ones may be added.
 *
 * <p>
 * Client side, client thread (render thread). On a dedicated server every query answers {@code false} or
 * {@code null} and the setters do nothing.
 *
 * <p>
 * <b>Locator frames.</b> A locator is a bone of the model named for what goes there: {@code RightHandLocator},
 * {@code LeftHandLocator}, {@code Head}, {@code BackpackLocator}, {@code LeftWaistLocator},
 * {@code RightWaistLocator}, {@code BladeLocator}, {@code SheathLocator}... A name stands for every bone whose name
 * without its trailing digits is that name (upstream's locator groups); the first bone shown in the current pose is
 * used (a zero scale in the animation hides a bone and its group). A frame is a column-major {@code float[16]}
 * ({@code glMultMatrix} order) from the locator's local space (blocks, y up, the front of the model towards -z, origin
 * at the bone's pivot after the animated pose, the model's scale included) to a render space given per method.
 */
public final class FuturaModels {

    /**
     * Raised when a method is added; 1: the methods below; 2: {@link #modelKey}; 3: external states
     * ({@link #setExternalState}, {@link #externalState}), {@link #hasAnimation} and {@link #playingAnimation}.
     */
    public static final int API_VERSION = 3;

    /**
     * External state (API 3): any value while the player's main hand holds a SlashBlade-style blade. The model then
     * uses upstream's SlashBlade animations: {@code hold_mainhand:slashblade} and the other {@code :slashblade}
     * names, and the main state as {@code slashblade:<state>} ({@code slashblade:idle}, {@code slashblade:walk}...)
     * when the model or the default model has it.
     */
    public static final String SLASHBLADE_HELD = "slashblade.held";
    /**
     * External state (API 3): the blade's combo state while one runs, as SlashBlade names it ({@code combo_a1},
     * {@code aerial_rave_a1}, {@code judgement_cut}...; {@code slashblade:} is added when missing). With
     * {@link #SLASHBLADE_HELD} set, the model plays {@code slashblade:<combo>} once on its swing controller (models
     * show {@code BladeLocator} and {@code SheathLocator} inside those clips); a combo the model lacks only keeps the
     * plain swing from playing. Clear it (null) when the chain is over; clear and set it again to restart the same
     * combo.
     */
    public static final String SLASHBLADE_COMBO = "slashblade.combo";

    private FuturaModels() {}

    private static boolean client() {
        return FMLCommonHandler.instance()
            .getSide()
            .isClient();
    }

    /** Whether the player was drawn in the world with a model in this frame or the one before. */
    public static boolean isModelled(EntityPlayer player) {
        return player != null && client() && PlayerModels.INSTANCE.modelled(player);
    }

    /**
     * The model the player is drawn with now: {@code builtin:<folder>} or {@code server:<id>}; the default model
     * ({@code builtin:default}) while his own model loads. Each player has his own model, so locators and their
     * presence differ between players.
     *
     * @return the key, or {@code null} on a dedicated server or before the models are loaded
     */
    public static String modelKey(EntityPlayer player) {
        if (player == null || !client()) {
            return null;
        }
        com.akashic.futura.client.ClientModel model = PlayerModels.INSTANCE.modelOf(player);
        return model == null ? null : model.key;
    }

    /**
     * A locator of the player's last world draw, in camera-relative render space: world axes, origin at
     * {@code RenderManager.renderPosX/Y/Z} (the space of {@code RenderWorldLastEvent} and of an entity renderer's
     * {@code doRender(x, y, z)}). Asked before the player is drawn in this frame, the previous frame's pose is moved
     * with the player to where he is now.
     *
     * @return a new {@code float[16]}, or {@code null} if the player was not drawn with a model in this frame or the
     *         one before, or no bone of the group is shown
     */
    public static float[] locator(EntityPlayer player, String name) {
        return player == null || name == null || !client() ? null : PlayerModels.INSTANCE.locator(player, name);
    }

    /** The first of several locators that is shown, as {@link #locator}; {@code null} if none is. */
    public static float[] locatorOrNull(EntityPlayer player, String... names) {
        if (names == null) {
            return null;
        }
        for (String name : names) {
            float[] m = locator(player, name);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    /** Every shown bone of a locator group, as {@link #locator}; {@code null} if the player was not drawn. */
    public static float[][] locators(EntityPlayer player, String name) {
        return player == null || name == null || !client() ? null : PlayerModels.INSTANCE.locators(player, name);
    }

    /**
     * Whether the player's model has a bone of that locator group, shown or hidden in the current pose (models that
     * hide a locator while an animation does not need it). False if the player was never drawn with a model.
     */
    public static boolean hasLocator(EntityPlayer player, String name) {
        return player != null && name != null && client() && PlayerModels.INSTANCE.hasLocator(player, name);
    }

    /**
     * For {@code RenderPlayerEvent.Post} handlers: whether the player draw that just ended drew this player's model
     * (in the world or in a screen such as the inventory). {@code RenderPlayerEvent.Specials} does not fire for a
     * modelled player: draw attachments here, after the model, with {@link #locatorInLastDraw}.
     */
    public static boolean drewModel(EntityPlayer player) {
        return player != null && client() && PlayerModels.INSTANCE.justDrew(player);
    }

    /**
     * For {@code RenderPlayerEvent.Post} handlers: a locator of the player draw that just ended, relative to the GL
     * matrix the player renderer was called with, which is the model-view matrix at {@code RenderPlayerEvent.Post}
     * (camera-relative render space in the world, the screen's space in the inventory).
     *
     * @return a new {@code float[16]}, or {@code null} if that draw was not this player's model or no bone of the group
     *         is shown
     */
    public static float[] locatorInLastDraw(EntityPlayer player, String name) {
        if (player == null || name == null || !client()) {
            return null;
        }
        float[][] all = PlayerModels.INSTANCE.locatorsOfLastDraw(player, name);
        return all == null || all.length == 0 ? null : all[0];
    }

    /**
     * 1.7.10 draws a stick in a player's hand while his fishing line is out, and so does the model. A mod that draws
     * its own rod can keep the held item in the model's hand for the players it says (it then draws the line itself,
     * or 3D Futura Engine draws it from where the stick's end would be).
     *
     * @param keeps {@code null} to remove
     */
    public static void setKeepsItemWhileFishing(Predicate<EntityPlayer> keeps) {
        if (client()) {
            PlayerModels.setKeepsItemWhileFishing(keeps);
        }
    }

    /**
     * API 3: sets ({@code value} non-null) or clears a state the animation runtime reads for the player, standing in
     * for what upstream reads from other mods (see {@link #SLASHBLADE_HELD} and {@link #SLASHBLADE_COMBO}). Kept per
     * player UUID until changed or the world is left; call it before the player is drawn (e.g. in
     * {@code RenderPlayerEvent.Pre}) for it to count in that frame. Setting the value it already has changes nothing;
     * any change is seen as new, so clearing and setting the same value restarts what it started.
     *
     * @param key   at most 64 characters
     * @param value at most 128 characters, or null to clear
     * @return false when not accepted (dedicated server, a limit: 16 keys per player)
     */
    public static boolean setExternalState(EntityPlayer player, String key, String value) {
        return player != null && client()
            && PlayerModels.INSTANCE.externalStates()
                .set(player.getUniqueID(), key, value);
    }

    /** API 3: a state set with {@link #setExternalState}, or null. */
    public static String externalState(EntityPlayer player, String key) {
        return player == null || key == null || !client() ? null
            : PlayerModels.INSTANCE.externalStates()
                .get(player.getUniqueID(), key);
    }

    /**
     * API 3: whether the model the player is drawn with has an animation of that name, its own or the default
     * model's (which every model falls back to), e.g. {@code slashblade:combo_a1}.
     */
    public static boolean hasAnimation(EntityPlayer player, String animation) {
        if (player == null || animation == null || !client()) {
            return false;
        }
        com.akashic.futura.client.ClientModel model = PlayerModels.INSTANCE.modelOf(player);
        return model != null && model.animations.library.get(animation) != null;
    }

    /**
     * API 3: the clip a controller of the player's model plays now ({@code player.main}, {@code player.swing},
     * {@code player.hold_mainhand}, {@code player.cap}...), or null. For checks and tests; the answer is the last
     * frame's.
     */
    public static String playingAnimation(EntityPlayer player, String controller) {
        if (!(player instanceof net.minecraft.client.entity.AbstractClientPlayer) || controller == null || !client()) {
            return null;
        }
        com.akashic.futura.anim.PlayerAnimator a = PlayerModels.INSTANCE
            .animator((net.minecraft.client.entity.AbstractClientPlayer) player);
        return a == null ? null : a.playing(controller);
    }
}
