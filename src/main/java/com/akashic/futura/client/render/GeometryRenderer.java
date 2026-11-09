package com.akashic.futura.client.render;

import java.nio.FloatBuffer;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.akashic.futura.anim.BoneAttributes;
import com.akashic.futura.anim.BonePose;
import com.akashic.futura.geo.BakedGeometry;
import com.akashic.futura.geo.FacePartition;
import com.akashic.futura.geo.Mat4;
import com.akashic.futura.geo.PoseMatrices;

/**
 * Draws a {@link BakedGeometry}: each bone's quads are compiled once into up to four display lists, one per
 * {@link FacePartition}, and every frame only the bone matrices ({@link PoseMatrices}) are applied.
 *
 * <p>
 * Opaque faces are drawn first, with back faces culled where the partition allows it. Translucent faces follow in
 * two passes, since display lists cannot be sorted: their fully opaque texels write depth, then every texel is
 * blended with the depth mask off. Bone colour applies to every face, bone transparency only to translucent ones
 * (upstream: {@code bone-opacity.md}), glow sets the lightmap of the bone's faces ({@code -1} keeps the light).
 */
public final class GeometryRenderer {

    private static final int P = FacePartition.COUNT;

    private final BakedGeometry geometry;
    private final byte[][] partitions;
    private final boolean[] has;
    private final boolean translucent;
    private final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
    private PoseMatrices scratch;
    private int listBase = -1;

    /** Every face opaque and drawn on both sides (no texture information). */
    public GeometryRenderer(BakedGeometry geometry) {
        this(geometry, null);
    }

    /** @param partitions per bone, per quad, from {@link FacePartition#classify}; {@code null}: all opaque, no cull */
    public GeometryRenderer(BakedGeometry geometry, byte[][] partitions) {
        this.geometry = geometry;
        this.partitions = partitions;
        this.has = new boolean[geometry.bones.length * P];
        boolean anyTranslucent = false;
        for (int b = 0; b < geometry.bones.length; b++) {
            int quads = geometry.bones[b].quadCount();
            for (int q = 0; q < quads; q++) {
                int p = partition(b, q);
                if (p >= 0) {
                    has[b * P + p] = true;
                    anyTranslucent |= FacePartition.translucent(p);
                }
            }
        }
        this.translucent = anyTranslucent;
    }

    public BakedGeometry geometry() {
        return geometry;
    }

    /** Whether some face is in a translucent partition. */
    public boolean hasTranslucent() {
        return translucent;
    }

    private int partition(int bone, int quad) {
        return partitions == null ? FacePartition.OPAQUE : partitions[bone][quad];
    }

    /** Compiles the display lists; needs the GL context. Called on the first draw. */
    private void compile() {
        BakedGeometry.Bone[] bones = geometry.bones;
        listBase = GL11.glGenLists(Math.max(1, bones.length * P));
        Tessellator t = Tessellator.instance;
        for (int i = 0; i < bones.length; i++) {
            float[] q = bones[i].quads;
            for (int p = 0; p < P; p++) {
                if (!has[i * P + p]) {
                    continue;
                }
                GL11.glNewList(listBase + i * P + p, GL11.GL_COMPILE);
                t.startDrawingQuads();
                for (int quad = 0, o = 0; o < q.length; quad++, o += BakedGeometry.QUAD_FLOATS) {
                    if (partition(i, quad) != p) {
                        continue;
                    }
                    t.setNormal(q[o + 20], q[o + 21], q[o + 22]);
                    for (int v = 0; v < 4; v++) {
                        int k = o + v * 5;
                        t.addVertexWithUV(q[k], q[k + 1], q[k + 2], q[k + 3], q[k + 4]);
                    }
                }
                t.draw();
                GL11.glEndList();
            }
        }
    }

    /**
     * Draws the geometry at the current matrix (model space: blocks, feet at the origin), texture already bound, in
     * white and without bone attributes.
     *
     * @param pose per-bone animation, or {@code null} for the rest pose
     */
    public void draw(BonePose pose) {
        if (scratch == null) {
            scratch = new PoseMatrices(geometry);
        }
        scratch.compute(pose);
        draw(scratch, null, 1, 1, 1, 1);
    }

    /**
     * Draws the geometry at the current matrix, texture already bound. GL state other than the matrix is restored.
     *
     * @param pose       the bone matrices of this frame
     * @param attributes bone colour, transparency and glow, or {@code null}
     * @param r          base colour, multiplied by each bone's
     * @param a          base alpha: opaque faces keep it, translucent ones multiply it by the bone's
     */
    public void draw(PoseMatrices pose, BoneAttributes attributes, float r, float g, float b, float a) {
        if (listBase < 0) {
            compile();
        }
        float lastX = OpenGlHelper.lastBrightnessX;
        float lastY = OpenGlHelper.lastBrightnessY;
        GL11.glPushAttrib(
            GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        GL11.glEnable(GL11.GL_NORMALIZE);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glDepthMask(true);

        // Opaque.
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        pass(pose, attributes, FacePartition.OPAQUE_CULL, r, g, b, a, lastX, lastY);
        pass(pose, attributes, FacePartition.OPAQUE, r, g, b, a, lastX, lastY);

        if (translucent) {
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            // Fully opaque texels first, writing depth, so that they hide what is behind them in any order.
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.99f);
            pass(pose, attributes, FacePartition.TRANSLUCENT_CULL, r, g, b, a, lastX, lastY);
            pass(pose, attributes, FacePartition.TRANSLUCENT, r, g, b, a, lastX, lastY);
            // Then everything visible, blended, without writing depth.
            GL11.glDepthMask(false);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
            pass(pose, attributes, FacePartition.TRANSLUCENT_CULL, r, g, b, a, lastX, lastY);
            pass(pose, attributes, FacePartition.TRANSLUCENT, r, g, b, a, lastX, lastY);
        }
        GL11.glPopAttrib();
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lastX, lastY);
    }

    private void pass(PoseMatrices pose, BoneAttributes attributes, int p, float r, float g, float b, float a,
        float lightX, float lightY) {
        if (FacePartition.culled(p)) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        } else {
            GL11.glDisable(GL11.GL_CULL_FACE);
        }
        boolean translucentPass = FacePartition.translucent(p);
        int lastGlow = BoneAttributes.NO_GLOW;
        for (int i = 0; i < geometry.bones.length; i++) {
            if (!has[i * P + p] || !pose.visible(i)) {
                continue;
            }
            int glow;
            if (attributes != null) {
                int rgb = attributes.color[i];
                float alpha = translucentPass ? a * attributes.alpha[i] / 255f : a;
                GL11.glColor4f(
                    r * (rgb >> 16 & 0xFF) / 255f,
                    g * (rgb >> 8 & 0xFF) / 255f,
                    b * (rgb & 0xFF) / 255f,
                    alpha);
                glow = attributes.glow[i];
            } else {
                GL11.glColor4f(r, g, b, a);
                glow = geometry.bones[i].glow() ? 15 : BoneAttributes.NO_GLOW;
            }
            if (glow < 0) {
                glow = BoneAttributes.NO_GLOW;
            }
            if (glow != lastGlow) {
                if (glow == BoneAttributes.NO_GLOW) {
                    OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lightX, lightY);
                } else {
                    // Block and sky light both set to the level.
                    OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, glow * 16f, glow * 16f);
                }
                lastGlow = glow;
            }
            callBone(pose, i, p);
        }
        if (lastGlow != BoneAttributes.NO_GLOW) {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lightX, lightY);
        }
    }

    /**
     * Draws one bone and its children at the current matrix, ignoring the bone's ancestors (their transforms and
     * hiding), as upstream extracts the first-person part subtrees. White, without bone attributes.
     *
     * @param pose per-bone animation of the whole geometry, or {@code null} for the rest pose
     */
    public void drawSubtree(int bone, BonePose pose) {
        if (scratch == null) {
            scratch = new PoseMatrices(geometry);
        }
        scratch.computeSubtree(bone, pose);
        draw(scratch, null, 1, 1, 1, 1);
    }

    private void callBone(PoseMatrices pose, int bone, int p) {
        matrix.clear();
        matrix.put(pose.bones, bone * Mat4.FLOATS, Mat4.FLOATS);
        matrix.flip();
        GL11.glPushMatrix();
        GL11.glMultMatrix(matrix);
        GL11.glCallList(listBase + bone * P + p);
        GL11.glPopMatrix();
    }

    /**
     * Draws every visible face again with the current colour and texture state, for overlays such as the hurt tint
     * (the caller sets the depth function to {@code GL_EQUAL}). Only culling is changed, and restored.
     */
    public void drawFlat(PoseMatrices pose) {
        if (listBase < 0) {
            compile();
        }
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
        for (int p = 0; p < P; p++) {
            if (FacePartition.culled(p)) {
                GL11.glEnable(GL11.GL_CULL_FACE);
            } else {
                GL11.glDisable(GL11.GL_CULL_FACE);
            }
            for (int i = 0; i < geometry.bones.length; i++) {
                if (has[i * P + p] && pose.visible(i)) {
                    callBone(pose, i, p);
                }
            }
        }
        GL11.glPopAttrib();
    }

    /** Frees the display lists. */
    public void delete() {
        if (listBase >= 0) {
            GL11.glDeleteLists(listBase, Math.max(1, geometry.bones.length * P));
            listBase = -1;
        }
    }
}
