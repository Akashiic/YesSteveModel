package com.akashic.futura.client;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.akashic.futura.Futura3D;
import com.akashic.futura.anim.BonePose;
import com.akashic.futura.anim.PlayerAnimator;
import com.akashic.futura.anim.PlayerSnapshot;
import com.akashic.futura.geo.PoseMatrices;
import com.akashic.futura.model.LoadedModel;

/**
 * Draws players with their model instead of the vanilla biped. Each player is drawn with the model
 * {@link ModelSelection} gives him ({@link ModelRegistry#DEFAULT} when none, or while his model loads), with his
 * selected texture, animated by a {@link PlayerAnimator} kept per player and model (a new one when his model changes).
 */
public final class PlayerModels {

    public static final PlayerModels INSTANCE = new PlayerModels();

    /** A player's animator and the model it animates. */
    private static final class Animated {

        final ClientModel model;
        final PlayerAnimator animator;

        Animated(ClientModel model) {
            this.model = model;
            this.animator = new PlayerAnimator(model.animations);
        }
    }

    private final Map<AbstractClientPlayer, Animated> animators = new WeakHashMap<>();
    private final PlayerSnapshot snapshot = new PlayerSnapshot();
    /** States other mods set on players (FuturaModels.setExternalState). */
    private final com.akashic.futura.anim.ExternalStates external = new com.akashic.futura.anim.ExternalStates();
    private boolean enabled = true;
    /** Development: every player drawn with this model, whatever is selected. */
    private String forced;

    private final ThirdPersonRenderer thirdPerson = new ThirdPersonRenderer();

    private PlayerModels() {}

    /** Lists the models and loads the default one (no GL work: display lists are made on the first draw). */
    public void load() {
        ModelRegistry.INSTANCE.init();
    }

    /**
     * Development: draws every player with a built-in model, whatever is selected (loaded on this thread).
     *
     * @param builtin folder under {@code assets/futura3d/builtin}, e.g. {@code default} or {@code wine_fox/18_wedding}
     * @return false if it could not be loaded (the previous model stays)
     */
    public boolean use(String builtin) {
        return force(ModelRegistry.BUILTIN + builtin);
    }

    /**
     * Development: draws every player with a model, whatever is selected, or goes back to the selections.
     *
     * @param key a {@link ModelRegistry} key (loaded on this thread), or null for the selections
     * @return false if it could not be loaded (nothing changes)
     */
    public boolean force(String key) {
        if (key == null) {
            forced = null;
            return true;
        }
        if (ModelRegistry.INSTANCE.loadNow(key) == null) {
            Futura3D.LOG.error("Could not load model {}", key);
            return false;
        }
        forced = key;
        return true;
    }

    public boolean active() {
        return enabled && ModelRegistry.INSTANCE.get(ModelRegistry.DEFAULT) != null;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** The key of the model a player should be drawn with (it may still be loading). */
    public String keyOf(EntityPlayer player) {
        if (forced != null) {
            return forced;
        }
        String key = player == null ? null : ModelSelection.INSTANCE.keyOf(player.getUniqueID());
        return key == null ? ModelRegistry.DEFAULT : key;
    }

    /** The texture stem selected for a player, or null for his model's default. */
    public String textureOf(EntityPlayer player) {
        return forced != null || player == null ? null : ModelSelection.INSTANCE.textureOf(player.getUniqueID());
    }

    /** The model a player is drawn with now: his model if ready, else the default model (null before start-up). */
    public ClientModel modelOf(EntityPlayer player) {
        ClientModel model = ModelRegistry.INSTANCE.get(keyOf(player));
        return model != null ? model : ModelRegistry.INSTANCE.get(ModelRegistry.DEFAULT);
    }

    /** The local player's model now (the default model without a world). */
    public ClientModel localModel() {
        return modelOf(Minecraft.getMinecraft().thePlayer);
    }

    /** The local player's model now, as loaded from its files. */
    public LoadedModel model() {
        ClientModel model = localModel();
        return model == null ? null : model.loaded;
    }

    /** The texture the local player's model is drawn with (shared by the body and the first-person arms). */
    public ResourceLocation texture() {
        return textureOf(localModel(), Minecraft.getMinecraft().thePlayer);
    }

    /** The texture of a player's model (render thread: uploads it on first use). */
    public ResourceLocation textureOf(ClientModel model, EntityPlayer player) {
        return model == null ? null : model.texture(model.skin(textureOf(player)));
    }

    /**
     * Draws a player's model where vanilla would draw the biped.
     *
     * @param x  render position (feet, relative to the camera), as {@code RendererLivingEntity.doRender} gets it
     * @param pt partial tick
     * @return false to let vanilla draw the player
     */
    public boolean render(AbstractClientPlayer player, double x, double y, double z, float pt) {
        thirdPerson.forgetLastDraw();
        if (!active()) {
            return false;
        }
        ClientModel model = modelOf(player);
        if (model == null) {
            return false;
        }
        int skin = model.skin(textureOf(player));
        PlayerAnimator animator = animated(player, model).animator;
        PlayerInputsAdapter.fill(snapshot, player, pt);
        BonePose pose = null;
        float bodyYaw = snapshot.bodyYaw;
        try {
            animator.update(snapshot, player.ticksExisted + pt);
            pose = animator.pose();
            bodyYaw = animator.renderBodyYaw();
        } catch (RuntimeException e) {
            // A broken animation must not take the game down: draw the rest pose and say it once per model.
            if (!model.animationFailed) {
                model.animationFailed = true;
                Futura3D.LOG.error("Animation failed for model {}", model.key, e);
            }
        }
        thirdPerson.render(
            player,
            model.renderer(skin),
            model.texture(skin),
            model.loaded.widthScale,
            model.loaded.heightScale,
            model.loaded.manifest.properties.renderLayersFirst,
            pose,
            pose == null ? null : animator.attributes(),
            x,
            y,
            z,
            bodyYaw,
            pt,
            snapshot.renderingInInventory);
        return true;
    }

    /** States other mods set on players ({@code FuturaModels.setExternalState}). */
    public com.akashic.futura.anim.ExternalStates externalStates() {
        return external;
    }

    /** The animator of a player for the model he is drawn with now, created on first use. */
    public PlayerAnimator animator(AbstractClientPlayer player) {
        ClientModel model = modelOf(player);
        return model == null ? null : animated(player, model).animator;
    }

    private Animated animated(AbstractClientPlayer player, ClientModel model) {
        Animated animated = animators.get(player);
        if (animated == null || animated.model != model) {
            // A new model (or a reload of it): its own animator; the old one is dropped.
            animated = new Animated(model);
            animators.put(player, animated);
        }
        return animated;
    }

    /** Frees the built-in models no player is drawn with or has selected (after the picker loaded many). */
    public void releaseUnusedModels() {
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (AbstractClientPlayer player : animators.keySet()) {
            keep.add(keyOf(player));
        }
        keep.add(keyOf(Minecraft.getMinecraft().thePlayer));
        ModelRegistry.INSTANCE.releaseUnused(keep, modelsInUse());
    }

    /** The models players are drawn with now (and the local player's). */
    private java.util.Set<ClientModel> modelsInUse() {
        java.util.Set<ClientModel> used = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Animated animated : animators.values()) {
            used.add(animated.model);
        }
        ClientModel local = localModel();
        if (local != null) {
            used.add(local);
        }
        if (forced != null) {
            ClientModel model = ModelRegistry.INSTANCE.get(forced);
            if (model != null) {
                used.add(model);
            }
        }
        return used;
    }

    /** Drops the animators and frames of models that were freed (server models after a disconnect). */
    void forgetReleased() {
        for (Iterator<Animated> it = animators.values()
            .iterator(); it.hasNext();) {
            if (it.next().model.released()) {
                it.remove();
            }
        }
        thirdPerson.forgetReleased();
    }

    /** Draws the local player's model in its rest pose at the current matrix (feet at the origin), scale applied. */
    public void drawAt(float widthScale, float heightScale) {
        drawAt(widthScale, heightScale, null);
    }

    /** Draws the local player's model at the current matrix (feet at the origin, facing its front), scale applied. */
    public void drawAt(float widthScale, float heightScale, BonePose pose) {
        ClientModel model = localModel();
        if (model != null) {
            drawModel(model, model.skin(textureOf(Minecraft.getMinecraft().thePlayer)), widthScale, heightScale, pose);
        }
    }

    /**
     * Draws a model at the current matrix (feet at the origin, facing its front), in white without bone attributes:
     * copies in the world, previews in screens.
     *
     * @param skin a texture index of the model ({@link ClientModel#skin})
     * @param pose the animation, or null for the rest pose
     */
    public void drawModel(ClientModel model, int skin, float widthScale, float heightScale, BonePose pose) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glPushMatrix();
        GL11.glScalef(widthScale, heightScale, widthScale);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1, 1, 1, 1);
        model.bind(skin);
        model.renderer(skin)
            .draw(pose);
        GL11.glPopMatrix();
        GL11.glPopAttrib();
    }

    /**
     * Draws a model with bone matrices already computed, at the current matrix (the model's scale already applied by
     * the caller), in white without bone attributes.
     */
    public void drawModel(ClientModel model, int skin, PoseMatrices pose) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        GL11.glColor4f(1, 1, 1, 1);
        model.bind(skin);
        model.renderer(skin)
            .draw(pose, null, 1, 1, 1, 1);
        GL11.glPopAttrib();
    }

    // ---- Phase 3a: locators and the off-hand seam ----

    /**
     * The frame of a locator bone in a player's last third-person draw, for attaching things to the model (rods,
     * fishing line, blades...): {@code RightHandLocator}, {@code LeftHandLocator}, {@code Head},
     * {@code BackpackLocator}, or any bone name without trailing digits (upstream's locator groups: the first visible
     * bone of the group is returned).
     *
     * <p>
     * The matrix is column-major ({@code glMultMatrix} order) and maps the locator's local space (blocks, y up, the
     * origin at the bone's pivot after the animated pose, the model's scale included) to camera-relative render space:
     * world axes, origin at {@code RenderManager.renderPosX/Y/Z}, the space of a {@code RenderWorldLastEvent} or of an
     * entity's {@code doRender(x, y, z)}. Upstream draws a held item at
     * {@code locator · T(0, -1/16, -0.1) · Rx(-90°)} in 1.20's item space; {@code LocatorTransforms} has the 1.7.10
     * equivalents. The frame is kept for invisible players too (the model draws nothing for them), but not for draws
     * in a screen (the inventory) nor in first person, where the player is not drawn this way. Asked before the
     * player is drawn in this frame (an entity drawn earlier, such as his fishing hook), the previous frame's pose is
     * moved with the player to where he is in this frame, relative to this frame's camera.
     *
     * @return a new {@code float[16]}, or {@code null} if the player was not drawn with a model in this frame or the
     *         previous one, or the model has no visible bone of that group (a zero scale hides a group)
     */
    public float[] locator(EntityPlayer player, String name) {
        return active() ? thirdPerson.locator(player, name) : null;
    }

    /** Every visible bone of a locator group, as {@link #locator}; {@code null} if the player was not drawn. */
    public float[][] locators(EntityPlayer player, String name) {
        return active() ? thirdPerson.locators(player, name) : null;
    }

    /** Whether the player was drawn in the world with a model in this frame or the one before. */
    public boolean modelled(EntityPlayer player) {
        return active() && thirdPerson.modelled(player);
    }

    /** Whether the player's model has a bone of a locator group, shown or hidden in the current pose. */
    public boolean hasLocator(EntityPlayer player, String name) {
        return active() && thirdPerson.hasGroup(player, name);
    }

    /** Whether the player draw that just ended (in the world or in a screen) drew this player's model. */
    public boolean justDrew(EntityPlayer player) {
        return active() && thirdPerson.justDrew(player);
    }

    /**
     * The visible bones of a locator group in the player draw that just ended, relative to the matrix the player
     * renderer was called with: for {@code RenderPlayerEvent.Post} handlers, in the world and in screens alike.
     *
     * @return {@code null} if that draw was not this player's model
     */
    public float[][] locatorsOfLastDraw(EntityPlayer player, String name) {
        return active() ? thirdPerson.locatorsOfLastDraw(player, name) : null;
    }

    private static volatile Predicate<EntityPlayer> keepsItemWhileFishing;

    /**
     * 1.7.10 draws a stick in the hand while the fishing line is out, and so does the model; a mod that draws its own
     * rod (Kunftig's 3D rods) can keep the held item for the players it says.
     *
     * @param keeps whether a player's held item stays in the hand with the line out; {@code null} removes the seam
     */
    public static void setKeepsItemWhileFishing(Predicate<EntityPlayer> keeps) {
        keepsItemWhileFishing = keeps;
    }

    /** Whether the held item is drawn in place of vanilla's stick while the player's line is out. */
    public static boolean keepsItemWhileFishing(EntityPlayer player) {
        Predicate<EntityPlayer> keeps = keepsItemWhileFishing;
        if (keeps == null) {
            return false;
        }
        try {
            return keeps.test(player);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static volatile Function<EntityPlayer, ItemStack> offhandSource;

    /**
     * Vanilla 1.7.10 has no off-hand; a mod that adds one (Backhand...) can give its item here, and the model draws it
     * at {@code LeftHandLocator}.
     *
     * @param source the off-hand item of a player, or {@code null} for none; {@code null} removes the seam
     */
    public static void setOffhandSource(Function<EntityPlayer, ItemStack> source) {
        offhandSource = source;
    }

    static ItemStack offhand(EntityPlayer player) {
        Function<EntityPlayer, ItemStack> source = offhandSource;
        return source == null ? null : source.apply(player);
    }
}
