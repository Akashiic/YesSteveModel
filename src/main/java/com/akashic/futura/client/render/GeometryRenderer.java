package com.akashic.futura.client.render;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

import com.akashic.futura.anim.BonePose;
import com.akashic.futura.geo.BakedGeometry;

/**
 * Draws a {@link BakedGeometry}: each bone's quads are compiled once into a display list, and every frame only the
 * bone transforms are applied ({@code T(pivot) · Rz · Ry · Rx · S · T(-pivot)}, nested down the hierarchy).
 */
public final class GeometryRenderer {

    private static final float RAD_TO_DEG = (float) (180 / Math.PI);

    private final BakedGeometry geometry;
    private int listBase = -1;

    public GeometryRenderer(BakedGeometry geometry) {
        this.geometry = geometry;
    }

    public BakedGeometry geometry() {
        return geometry;
    }

    /** Compiles the display lists; needs the GL context. Called on the first draw. */
    private void compile() {
        BakedGeometry.Bone[] bones = geometry.bones;
        listBase = GL11.glGenLists(Math.max(1, bones.length));
        Tessellator t = Tessellator.instance;
        for (int i = 0; i < bones.length; i++) {
            float[] q = bones[i].quads;
            if (q.length == 0) {
                continue;
            }
            GL11.glNewList(listBase + i, GL11.GL_COMPILE);
            t.startDrawingQuads();
            for (int o = 0; o < q.length; o += BakedGeometry.QUAD_FLOATS) {
                t.setNormal(q[o + 20], q[o + 21], q[o + 22]);
                for (int v = 0; v < 4; v++) {
                    int p = o + v * 5;
                    t.addVertexWithUV(q[p], q[p + 1], q[p + 2], q[p + 3], q[p + 4]);
                }
            }
            t.draw();
            GL11.glEndList();
        }
    }

    /**
     * Draws the geometry at the current matrix (model space: blocks, feet at the origin), texture already bound.
     *
     * @param pose per-bone animation, or {@code null} for the rest pose
     */
    public void draw(BonePose pose) {
        if (listBase < 0) {
            compile();
        }
        for (int root : geometry.roots) {
            drawBone(root, pose);
        }
    }

    /**
     * Draws one bone and its children at the current matrix, ignoring the bone's ancestors (their transforms and
     * hiding), as upstream extracts the first-person part subtrees.
     *
     * @param pose per-bone animation of the whole geometry, or {@code null} for the rest pose
     */
    public void drawSubtree(int bone, BonePose pose) {
        if (listBase < 0) {
            compile();
        }
        drawBone(bone, pose);
    }

    private void drawBone(int index, BonePose pose) {
        BakedGeometry.Bone bone = geometry.bones[index];
        if (pose != null && pose.hidden(index)) {
            return;
        }
        float[] pivot = bone.pivot;
        float rx = bone.rotation[0], ry = bone.rotation[1], rz = bone.rotation[2];
        float tx = 0, ty = 0, tz = 0, sx = 1, sy = 1, sz = 1;
        if (pose != null) {
            float[] p = pose.values;
            int o = index * BonePose.FLOATS;
            rx += p[o];
            ry += p[o + 1];
            rz += p[o + 2];
            tx = p[o + 3];
            ty = p[o + 4];
            tz = p[o + 5];
            sx = p[o + 6];
            sy = p[o + 7];
            sz = p[o + 8];
        }
        GL11.glPushMatrix();
        GL11.glTranslatef(pivot[0] + tx, pivot[1] + ty, pivot[2] + tz);
        if (rz != 0) {
            GL11.glRotatef(rz * RAD_TO_DEG, 0, 0, 1);
        }
        if (ry != 0) {
            GL11.glRotatef(ry * RAD_TO_DEG, 0, 1, 0);
        }
        if (rx != 0) {
            GL11.glRotatef(rx * RAD_TO_DEG, 1, 0, 0);
        }
        if (sx != 1 || sy != 1 || sz != 1) {
            GL11.glScalef(sx, sy, sz);
        }
        GL11.glTranslatef(-pivot[0], -pivot[1], -pivot[2]);
        if (bone.quads.length > 0) {
            if (bone.glow()) {
                float lastX = OpenGlHelper.lastBrightnessX;
                float lastY = OpenGlHelper.lastBrightnessY;
                OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f);
                GL11.glCallList(listBase + index);
                OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lastX, lastY);
            } else {
                GL11.glCallList(listBase + index);
            }
        }
        for (int child : bone.children) {
            drawBone(child, pose);
        }
        GL11.glPopMatrix();
    }

    /** Frees the display lists. */
    public void delete() {
        if (listBase >= 0) {
            GL11.glDeleteLists(listBase, Math.max(1, geometry.bones.length));
            listBase = -1;
        }
    }
}
