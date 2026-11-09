package com.akashic.futura.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.akashic.futura.Futura3D;
import com.akashic.futura.client.render.GeometryRenderer;
import com.akashic.futura.model.ClasspathModelSource;
import com.akashic.futura.model.LoadedModel;
import com.akashic.futura.model.ModelLoader;

/**
 * Draws players with their model instead of the vanilla biped. For now every player gets the built-in
 * {@code default} model in its rest pose (phase 1 of the port: geometry and cost).
 */
public final class PlayerModels {

    public static final PlayerModels INSTANCE = new PlayerModels();
    private static final String BUILTIN = "/assets/futura3d/builtin/";

    private LoadedModel model;
    private GeometryRenderer renderer;
    private ResourceLocation texture;
    private boolean failed;
    private boolean enabled = true;

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
            model = loaded;
            renderer = new GeometryRenderer(loaded.main);
            texture = new ResourceLocation(Futura3D.MODID, "builtin/" + builtin + "/" + loaded.defaultTexture());
            failed = false;
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
        float bodyYaw = interpolateRotation(player.prevRenderYawOffset, player.renderYawOffset, pt);
        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);
        GL11.glRotatef(180f - bodyYaw, 0, 1, 0);
        drawAt(model.widthScale, model.heightScale);
        GL11.glPopMatrix();
        return true;
    }

    /** Draws the model at the current matrix (feet at the origin, facing the model's front), scale applied here. */
    public void drawAt(float widthScale, float heightScale) {
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
        renderer.draw(null);
        GL11.glPopAttrib();
    }

    private static float interpolateRotation(float prev, float now, float pt) {
        float delta = now - prev;
        while (delta < -180f) {
            delta += 360f;
        }
        while (delta >= 180f) {
            delta -= 360f;
        }
        return prev + pt * delta;
    }
}
