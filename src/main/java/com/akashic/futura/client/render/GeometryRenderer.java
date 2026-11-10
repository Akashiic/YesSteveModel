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
import com.akashic.futura.geo.TranslucentSort;

/**
 * Draws a {@link BakedGeometry} with upstream's partitions ({@link FacePartition}). The opaque partitions of each bone
 * are compiled once into display lists and every frame only the bone matrices ({@link PoseMatrices}) are applied; the
 * translucent quads are transformed, culled and sorted back to front on the CPU every draw ({@link TranslucentSort},
 * upstream's per-draw ordering) and sent through the {@link Tessellator}.
 *
 * <p>
 * Opaque faces are drawn first, with back faces culled where the partition allows it. Translucent faces follow,
 * blended and writing depth (upstream's {@code entityTranslucent} render type), farthest first. Back faces that are
 * drawn are lit with the normal reversed, as upstream does, through GL's two-sided lighting. Bone colour applies to
 * every face, bone transparency only to translucent ones (upstream: {@code bone-opacity.md}), glow sets the lightmap of
 * the bone's faces ({@code -1} keeps the light).
 */
public final class GeometryRenderer {

    private static final int P = FacePartition.COUNT;

    /**
     * Development switch for comparison screenshots (render harness): {@code false} draws the translucent quads in
     * their fixed emission order instead of back to front.
     */
    public static boolean sortTranslucent = true;
    /** Display lists allocated and not deleted, by every renderer (leak checks). */
    private static int liveLists;

    private final BakedGeometry geometry;
    private final byte[][] partitions;
    private final float[][] uvs;
    private final boolean[] has;
    private final TranslucentSort translucent;
    private final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer glMatrix = BufferUtils.createFloatBuffer(16);
    private final float[] modelView = new float[16];
    private final float[] projection = new float[16];
    private final float[] clip = new float[16];
    private PoseMatrices scratch;
    private int listBase = -1;

    /** Every face opaque and drawn on both sides (no texture information). */
    public GeometryRenderer(BakedGeometry geometry) {
        this(geometry, null, null);
    }

    /** @param partitions per bone, per quad, from {@link FacePartition#classify}; {@code null}: all opaque, no cull */
    public GeometryRenderer(BakedGeometry geometry, byte[][] partitions) {
        this(geometry, partitions, null);
    }

    /** @param baked the partitions and snapped UVs of a texture, from {@link FacePartition#bake} */
    public GeometryRenderer(BakedGeometry geometry, FacePartition.Baked baked) {
        this(geometry, baked.partitions, baked.uvs);
    }

    private GeometryRenderer(BakedGeometry geometry, byte[][] partitions, float[][] uvs) {
        this.geometry = geometry;
        this.partitions = partitions;
        this.uvs = uvs;
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
        this.translucent = anyTranslucent ? new TranslucentSort(geometry, partitions, uvs) : null;
    }

    public BakedGeometry geometry() {
        return geometry;
    }

    /** Whether some face is in a translucent partition. */
    public boolean hasTranslucent() {
        return translucent != null;
    }

    private int partition(int bone, int quad) {
        return partitions == null ? FacePartition.OPAQUE : partitions[bone][quad];
    }

    /** Compiles the display lists of the opaque partitions; needs the GL context. Called on the first draw. */
    private void compile() {
        BakedGeometry.Bone[] bones = geometry.bones;
        listBase = GL11.glGenLists(Math.max(1, bones.length * P));
        liveLists += Math.max(1, bones.length * P);
        Tessellator t = Tessellator.instance;
        for (int i = 0; i < bones.length; i++) {
            float[] q = bones[i].quads;
            float[] uv = uvs == null ? null : uvs[i];
            for (int p = 0; p < P; p++) {
                if (!has[i * P + p] || FacePartition.translucent(p)) {
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
                        if (uv == null) {
                            t.addVertexWithUV(q[k], q[k + 1], q[k + 2], q[k + 3], q[k + 4]);
                        } else {
                            int u = quad * FacePartition.UV_FLOATS + v * 2;
                            t.addVertexWithUV(q[k], q[k + 1], q[k + 2], uv[u], uv[u + 1]);
                        }
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
            GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_CURRENT_BIT
                | GL11.GL_LIGHTING_BIT);
        GL11.glEnable(GL11.GL_NORMALIZE);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glDepthMask(true);

        // Opaque.
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        pass(pose, attributes, FacePartition.OPAQUE_CULL, r, g, b, a, lastX, lastY);
        // From here back faces can be drawn: they are lit from their own side (upstream reverses their normal). Not
        // needed above, where they are culled by the same winding test.
        GL11.glLightModeli(GL11.GL_LIGHT_MODEL_TWO_SIDE, GL11.GL_TRUE);
        pass(pose, attributes, FacePartition.OPAQUE, r, g, b, a, lastX, lastY);

        if (translucent != null) {
            // Sorted back to front, blended, writing depth; culled on the CPU.
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_CULL_FACE);
            drawTranslucent(pose, attributes, r, g, b, a, lastX, lastY, false);
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
        int lastGlow = BoneAttributes.NO_GLOW;
        for (int i = 0; i < geometry.bones.length; i++) {
            if (!has[i * P + p] || !pose.visible(i)) {
                continue;
            }
            int glow;
            if (attributes != null) {
                int rgb = attributes.color[i];
                GL11.glColor4f(r * (rgb >> 16 & 0xFF) / 255f, g * (rgb >> 8 & 0xFF) / 255f, b * (rgb & 0xFF) / 255f, a);
            } else {
                GL11.glColor4f(r, g, b, a);
            }
            glow = glow(attributes, i);
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

    /** The glow level of a bone, or {@link BoneAttributes#NO_GLOW} to keep the light. */
    private int glow(BoneAttributes attributes, int bone) {
        int glow = attributes != null ? attributes.glow[bone]
            : geometry.bones[bone].glow() ? 15 : BoneAttributes.NO_GLOW;
        return glow < 0 ? BoneAttributes.NO_GLOW : glow;
    }

    /**
     * The translucent quads of visible bones, farthest first, in one tessellation.
     *
     * @param flat for overlays: no colour or lightmap of its own, the current GL colour applies
     */
    private void drawTranslucent(PoseMatrices pose, BoneAttributes attributes, float r, float g, float b, float a,
        float lightX, float lightY, boolean flat) {
        int n = translucent.sort(pose, clipMatrix(), sortTranslucent);
        if (n == 0) {
            return;
        }
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        if (flat) {
            t.disableColor();
        }
        int light = (int) lightY << 16 | (int) lightX & 0xFFFF;
        float[] pos = translucent.positions, normal = translucent.normals, uv = translucent.uv;
        int lastBone = -1;
        for (int i = 0; i < n; i++) {
            int q = translucent.order(i);
            int bone = translucent.bone(q);
            if (!flat && bone != lastBone) {
                lastBone = bone;
                if (attributes != null) {
                    int rgb = attributes.color[bone];
                    t.setColorRGBA_F(
                        r * (rgb >> 16 & 0xFF) / 255f,
                        g * (rgb >> 8 & 0xFF) / 255f,
                        b * (rgb & 0xFF) / 255f,
                        a * attributes.alpha[bone] / 255f);
                } else {
                    t.setColorRGBA_F(r, g, b, a);
                }
                int glow = glow(attributes, bone);
                t.setBrightness(glow == BoneAttributes.NO_GLOW ? light : glow * 16 << 16 | glow * 16);
            }
            t.setNormal(normal[q * 3], normal[q * 3 + 1], normal[q * 3 + 2]);
            for (int v = 0; v < 4; v++) {
                int k = q * 12 + v * 3, u = q * 8 + v * 2;
                t.addVertexWithUV(pos[k], pos[k + 1], pos[k + 2], uv[u], uv[u + 1]);
            }
        }
        t.draw();
    }

    /** Projection times model-view, the model space of the current matrix to clip space. */
    private float[] clipMatrix() {
        glMatrix.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, glMatrix);
        glMatrix.get(modelView);
        glMatrix.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, glMatrix);
        glMatrix.get(projection);
        Mat4.mul(projection, 0, modelView, 0, clip, 0);
        return clip;
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
     * (the caller sets the depth function to {@code GL_EQUAL}): the translucent quads go through the same CPU path as
     * in {@link #draw}, so that their depth is the same. Culling and the light model are changed, and restored.
     */
    public void drawFlat(PoseMatrices pose) {
        if (listBase < 0) {
            compile();
        }
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LIGHTING_BIT);
        for (int p = 0; p < P; p++) {
            if (FacePartition.translucent(p)) {
                continue;
            }
            if (FacePartition.culled(p)) {
                GL11.glEnable(GL11.GL_CULL_FACE);
            } else {
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glLightModeli(GL11.GL_LIGHT_MODEL_TWO_SIDE, GL11.GL_TRUE);
            }
            for (int i = 0; i < geometry.bones.length; i++) {
                if (has[i * P + p] && pose.visible(i)) {
                    callBone(pose, i, p);
                }
            }
        }
        if (translucent != null) {
            GL11.glDisable(GL11.GL_CULL_FACE);
            drawTranslucent(pose, null, 1, 1, 1, 1, 0, 0, true);
        }
        GL11.glPopAttrib();
    }

    /** Frees the display lists. */
    /** Display lists allocated and not deleted (render thread; leak checks). */
    public static int liveLists() {
        return liveLists;
    }

    public void delete() {
        if (listBase >= 0) {
            GL11.glDeleteLists(listBase, Math.max(1, geometry.bones.length * P));
            liveLists -= Math.max(1, geometry.bones.length * P);
            listBase = -1;
        }
    }
}
