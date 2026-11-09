package com.akashic.futura.client;

import java.io.IOException;
import java.io.InputStream;
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
import com.akashic.futura.anim.ModelAnimations;
import com.akashic.futura.anim.PlayerAnimator;
import com.akashic.futura.anim.PlayerSnapshot;
import com.akashic.futura.client.render.GeometryRenderer;
import com.akashic.futura.geo.FacePartition;
import com.akashic.futura.model.ClasspathModelSource;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelLoader;

/**
 * Draws players with their model instead of the vanilla biped, animated by a {@link PlayerAnimator} per player. For
 * now every player gets the same built-in model (no selection or networking yet).
 */
public final class PlayerModels {

    public static final PlayerModels INSTANCE = new PlayerModels();
    private static final String BUILTIN = "/assets/futura3d/builtin/";

    private LoadedModel model;
    private GeometryRenderer renderer;
    private ModelAnimations animations;
    private final Map<AbstractClientPlayer, PlayerAnimator> animators = new WeakHashMap<>();
    private final PlayerSnapshot snapshot = new PlayerSnapshot();
    private ResourceLocation texture;
    private boolean failed;
    private boolean enabled = true;
    private boolean animationFailed;

    private final ThirdPersonRenderer thirdPerson = new ThirdPersonRenderer();

    private PlayerModels() {}

    /** Reads and bakes the built-in default model (no GL work: the display lists are made on the first draw). */
    public void load() {
        use("default");
    }

    /**
     * Switches every player to a built-in model.
     *
     * @param builtin folder under {@code assets/futura3d/builtin}, e.g. {@code default} or {@code wine_fox/18_wedding}
     * @return false if it could not be loaded (the previous model stays)
     */
    public boolean use(String builtin) {
        try {
            String base = BUILTIN + builtin + "/";
            LoadedModel loaded = ModelLoader.load(new ClasspathModelSource(base));
            if (renderer != null) {
                renderer.delete();
            }
            ModelAnimations compiled = ModelAnimations.compile(loaded);
            byte[][] partitions = partitions(loaded);
            model = loaded;
            animations = compiled;
            animators.clear();
            thirdPerson.reset();
            renderer = new GeometryRenderer(loaded.main, partitions);
            texture = new ResourceLocation(Futura3D.MODID, "builtin/" + builtin + "/" + loaded.defaultTexture());
            failed = false;
            animationFailed = false;
            Futura3D.LOG.info(
                "Model {}: {} bones, {} cubes, {} quads, texture {}",
                loaded.name(),
                loaded.main.bones.length,
                loaded.main.cubeCount,
                loaded.main.quadCount(),
                texture);
            return true;
        } catch (Exception e) {
            failed = renderer == null;
            Futura3D.LOG.error("Could not load the built-in model {}", builtin, e);
            return false;
        }
    }

    public boolean active() {
        return enabled && !failed && renderer != null;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LoadedModel model() {
        return model;
    }

    /** The texture of the current model (shared by the body and the first-person arms). */
    public ResourceLocation texture() {
        return texture;
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
        PlayerAnimator animator = animator(player);
        PlayerInputsAdapter.fill(snapshot, player, pt);
        BonePose pose = null;
        float bodyYaw = snapshot.bodyYaw;
        try {
            animator.update(snapshot, player.ticksExisted + pt);
            pose = animator.pose();
            bodyYaw = animator.renderBodyYaw();
        } catch (RuntimeException e) {
            // A broken animation must not take the game down: draw the rest pose and say it once per model.
            if (!animationFailed) {
                animationFailed = true;
                Futura3D.LOG.error("Animation failed for model {}", model.name(), e);
            }
        }
        thirdPerson.render(
            player,
            renderer,
            texture,
            model.widthScale,
            model.heightScale,
            model.manifest.properties.renderLayersFirst,
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

    /** The animator of a player for the current model, created on first use. */
    public PlayerAnimator animator(AbstractClientPlayer player) {
        PlayerAnimator animator = animators.get(player);
        if (animator == null) {
            animator = new PlayerAnimator(animations);
            animators.put(player, animator);
        }
        return animator;
    }

    /** Draws the model in its rest pose at the current matrix (feet at the origin), scale applied here. */
    public void drawAt(float widthScale, float heightScale) {
        drawAt(widthScale, heightScale, null);
    }

    /** Draws the model at the current matrix (feet at the origin, facing the model's front), scale applied here. */
    public void drawAt(float widthScale, float heightScale, BonePose pose) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT);
        GL11.glScalef(widthScale, heightScale, widthScale);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1, 1, 1, 1);
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(texture);
        renderer.draw(pose);
        GL11.glPopAttrib();
    }

    // ---- Phase 3a: face partitions, locators and the off-hand seam ----

    /** Upstream's face partitions from the default texture's alpha; all opaque if the texture cannot be read. */
    private static byte[][] partitions(LoadedModel loaded) {
        FacePartition.Alpha alpha = null;
        try (InputStream in = loaded.source.open(loaded.defaultTexture())) {
            if (in != null) {
                alpha = FacePartition.Alpha.read(in);
            }
        } catch (IOException | RuntimeException e) {
            Futura3D.LOG.warn("Could not read the texture of {} for its face partitions", loaded.name(), e);
        }
        return FacePartition.classify(loaded.main, alpha, loaded.manifest.properties.forceCulling);
    }

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
