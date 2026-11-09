package com.akashic.futura.client;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.akashic.futura.Futura3D;
import com.akashic.futura.anim.BonePose;
import com.akashic.futura.anim.ModelAnimations;
import com.akashic.futura.anim.PlayerAnimator;
import com.akashic.futura.anim.PlayerSnapshot;
import com.akashic.futura.client.render.GeometryRenderer;
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
            model = loaded;
            animations = compiled;
            animators.clear();
            renderer = new GeometryRenderer(loaded.main);
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

    /**
     * Draws a player's model where vanilla would draw the biped.
     *
     * @param x  render position (feet, relative to the camera), as {@code RendererLivingEntity.doRender} gets it
     * @param pt partial tick
     * @return false to let vanilla draw the player
     */
    public boolean render(AbstractClientPlayer player, double x, double y, double z, float pt) {
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
        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);
        GL11.glRotatef(180f - bodyYaw, 0, 1, 0);
        drawAt(model.widthScale, model.heightScale, pose);
        GL11.glPopMatrix();
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
}
