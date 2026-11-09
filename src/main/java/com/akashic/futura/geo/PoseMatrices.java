/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * the locator frames of src/main/java/com/elfmcys/ysm/geckolib3/geo/animated/GeoModelState.java and the group names of
 * src/main/java/com/elfmcys/ysm/geckolib3/geo/render/built/GeoLocatorType.java, Apache License 2.0.
 * Changes: bone matrices computed in Java (upstream reads them from the native frame state); any bone name is a group.
 */
package com.akashic.futura.geo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.akashic.futura.anim.BonePose;

/**
 * The model-space matrix of every bone for one pose, computed once per frame and shared by the draw passes and the
 * locators. A bone's matrix is its parent's times {@code T(pivot + position) · Rz · Ry · Rx · S · T(-pivot)}; a bone
 * scaled to zero on all axes, or with a non-finite value, is hidden with its whole subtree (upstream's extract rules).
 * No Minecraft classes.
 *
 * <p>
 * Locators follow upstream's {@code GeoModelState.visitLocatorGroup}: the frame of a locator bone is its matrix times
 * {@code T(pivot)}, that is the bone's own pivot after its rotation and scale. A locator group is every bone whose
 * name, without trailing digits, is the group's name ({@code RightHandLocator}, {@code RightHandLocator2}...), and
 * only visible bones count.
 */
public final class PoseMatrices {

    private final BakedGeometry geometry;
    /** {@link Mat4#FLOATS} per bone, model space (blocks, feet at the origin). */
    public final float[] bones;
    private final boolean[] visible;
    private final Map<String, int[]> groups = new HashMap<>();

    public PoseMatrices(BakedGeometry geometry) {
        this.geometry = geometry;
        this.bones = new float[geometry.bones.length * Mat4.FLOATS];
        this.visible = new boolean[geometry.bones.length];
        compute(null);
    }

    public BakedGeometry geometry() {
        return geometry;
    }

    /** @param pose the frame's animation, or {@code null} for the rest pose */
    public void compute(BonePose pose) {
        compute(pose, -1);
    }

    /**
     * Computes only one bone and its descendants, the bone taken as a root (its ancestors' transforms and hiding are
     * ignored); every other bone is hidden. Upstream extracts the first-person part subtrees this way.
     */
    public void computeSubtree(int root, BonePose pose) {
        compute(pose, root);
    }

    private void compute(BonePose pose, int root) {
        BakedGeometry.Bone[] all = geometry.bones;
        float[] p = pose == null ? null : pose.values;
        // Parents come before their children (BakedGeometry's order).
        for (int i = 0; i < all.length; i++) {
            BakedGeometry.Bone bone = all[i];
            int o = i * Mat4.FLOATS;
            if (root >= 0 && i != root && (i < root || bone.parent < 0 || !inSubtree(bone.parent, root))) {
                visible[i] = false;
                continue;
            }
            boolean topLevel = bone.parent < 0 || i == root;
            boolean shown = topLevel || visible[bone.parent];
            if (topLevel) {
                Mat4.identity(bones, o);
            } else {
                Mat4.copy(bones, bone.parent * Mat4.FLOATS, bones, o);
            }
            float rx = bone.rotation[0], ry = bone.rotation[1], rz = bone.rotation[2];
            float tx = 0, ty = 0, tz = 0, sx = 1, sy = 1, sz = 1;
            if (p != null) {
                int q = i * BonePose.FLOATS;
                rx += p[q];
                ry += p[q + 1];
                rz += p[q + 2];
                tx = p[q + 3];
                ty = p[q + 4];
                tz = p[q + 5];
                sx = p[q + 6];
                sy = p[q + 7];
                sz = p[q + 8];
                if (sx == 0 && sy == 0 && sz == 0) {
                    shown = false;
                }
                for (int k = 0; k < BonePose.FLOATS; k++) {
                    if (!finite(p[q + k])) {
                        shown = false;
                    }
                }
            }
            visible[i] = shown;
            if (!shown) {
                continue;
            }
            float[] pivot = bone.pivot;
            Mat4.translate(bones, o, pivot[0] + tx, pivot[1] + ty, pivot[2] + tz);
            Mat4.rotateZ(bones, o, rz);
            Mat4.rotateY(bones, o, ry);
            Mat4.rotateX(bones, o, rx);
            if (sx != 1 || sy != 1 || sz != 1) {
                Mat4.scale(bones, o, sx, sy, sz);
            }
            Mat4.translate(bones, o, -pivot[0], -pivot[1], -pivot[2]);
        }
    }

    /** Whether {@code bone} is {@code root} or below it (parents come first, so walking up ends quickly). */
    private boolean inSubtree(int bone, int root) {
        BakedGeometry.Bone[] all = geometry.bones;
        for (int b = bone; b >= 0; b = all[b].parent) {
            if (b == root) {
                return true;
            }
            if (b < root) {
                return false;
            }
        }
        return false;
    }

    private static boolean finite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    /** Whether the bone and all its ancestors are shown in the last computed pose. */
    public boolean visible(int bone) {
        return visible[bone];
    }

    /** Writes the locator frame of a bone ({@code matrix · T(pivot)}) to {@code out} at {@code offset}. */
    public void locator(int bone, float[] out, int offset) {
        Mat4.copy(bones, bone * Mat4.FLOATS, out, offset);
        float[] pivot = geometry.bones[bone].pivot;
        Mat4.translate(out, offset, pivot[0], pivot[1], pivot[2]);
    }

    /** All bones of a locator group, visible or not, in bone order; empty if the model has none. */
    public int[] group(String name) {
        int[] members = groups.get(name);
        if (members == null) {
            List<Integer> found = new ArrayList<>();
            for (int i = 0; i < geometry.bones.length; i++) {
                if (groupName(geometry.bones[i].name).equals(name)) {
                    found.add(i);
                }
            }
            members = new int[found.size()];
            for (int i = 0; i < members.length; i++) {
                members[i] = found.get(i);
            }
            groups.put(name, members);
        }
        return members;
    }

    /** The visible bones of a locator group in the last computed pose. */
    public int[] visibleGroup(String name) {
        int[] members = group(name);
        int n = 0;
        for (int b : members) {
            if (visible[b]) {
                n++;
            }
        }
        if (n == members.length) {
            return members;
        }
        int[] shown = new int[n];
        n = 0;
        for (int b : members) {
            if (visible[b]) {
                shown[n++] = b;
            }
        }
        return shown;
    }

    /** A bone name without its trailing digits (upstream's {@code GeoLocatorType.getByBoneName}). */
    public static String groupName(String bone) {
        int end = bone.length();
        while (end > 0 && Character.isDigit(bone.charAt(end - 1))) {
            end--;
        }
        return end > 0 && end < bone.length() ? bone.substring(0, end) : bone;
    }
}
