package com.akashic.futura.armor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.akashic.futura.geo.BakedGeometry;

/**
 * Where worn armor goes on a model: each part of the vanilla biped armor (the head, the body, the arms and legs) is
 * cut where the model bends (elbows, knees, ankles) and every cut is stretched over the box of one of the model's
 * bones, so the armor follows the model's own proportions and animation. The default model and the Steve/Alex models
 * have a fit tuned by hand; any other model gets one from its bones ({@link #fromGeometry}), found by the names every
 * YSM model uses ({@code Head}, {@code UpperBody}, {@code LeftArm}, {@code LeftForeArm}, {@code LeftLeg},
 * {@code LeftLowerLeg}, {@code LeftFoot}...).
 * <p>
 * Coordinates: a part's box and cuts are in the biped's part pixels (as {@code ModelBiped} builds its boxes, y down,
 * relative to the part's rotation point); a bone's box is in the geometry file's pixels (Bedrock: x not negated, y up,
 * absolute, as the file's cubes). No Minecraft classes.
 */
public final class ArmorFit {

    /** A part of {@code ModelBiped}: its box (pixels) and texture offset, as its constructor builds it. */
    public enum Part {

        HEAD(-4, -8, -4, 8, 8, 8, 0, 0, false, 0),
        /** The helmet's outer layer, half a pixel larger. */
        HEADWEAR(-4, -8, -4, 8, 8, 8, 32, 0, false, 0.5f),
        BODY(-4, 0, -2, 8, 12, 4, 16, 16, false, 0),
        RIGHT_ARM(-3, -2, -2, 4, 12, 4, 40, 16, false, 0),
        LEFT_ARM(-1, -2, -2, 4, 12, 4, 40, 16, true, 0),
        RIGHT_LEG(-2, 0, -2, 4, 12, 4, 0, 16, false, 0),
        LEFT_LEG(-2, 0, -2, 4, 12, 4, 0, 16, true, 0);

        public final float x, y, z;
        public final int width, height, depth;
        public final int u, v;
        public final boolean mirror;
        /** Added to the armor's inflation (the outer layer of the helmet). */
        public final float extraInflate;

        Part(float x, float y, float z, int width, int height, int depth, int u, int v, boolean mirror,
            float extraInflate) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.u = u;
            this.v = v;
            this.mirror = mirror;
            this.extraInflate = extraInflate;
        }

        /**
         * Whether vanilla shows this part for an armor render pass ({@code RenderPlayer.shouldRenderPass}: 0 the
         * helmet, 1 the chestplate, 2 the leggings, 3 the boots).
         */
        public boolean shownIn(int pass) {
            switch (this) {
                case HEAD:
                case HEADWEAR:
                    return pass == 0;
                case BODY:
                    return pass == 1 || pass == 2;
                case RIGHT_ARM:
                case LEFT_ARM:
                    return pass == 1;
                default:
                    return pass == 2 || pass == 3;
            }
        }
    }

    /** A cut of a part ({@code from} to {@code to} along its y, in its pixels) stretched over a bone's box. */
    public static final class Piece {

        public final Part part;
        public final float from, to;
        public final String bone;
        /** The bone's box: origin x, y, z, then size x, y, z (file pixels). */
        public final float[] box;

        Piece(Part part, float from, float to, String bone, float... box) {
            this.part = part;
            this.from = from;
            this.to = to;
            this.bone = bone;
            this.box = box;
        }

        /** Whether the cut starts at the top of the part (its top face is drawn). */
        public boolean top() {
            return from <= part.y;
        }

        /** Whether the cut ends at the bottom of the part (its bottom face is drawn). */
        public boolean bottom() {
            return to >= part.y + part.height;
        }
    }

    private static final Map<String, ArmorFit> FITS = new HashMap<>();

    static {
        FITS.put("builtin:default", defaultModel());
        FITS.put("builtin:misc/2_steve", biped(4));
        FITS.put("builtin:misc/1_alex", biped(3));
    }

    /** The cuts, in drawing order. */
    public final List<Piece> pieces;
    /** Per part, its whole box on the bone of its first cut (for armor drawn by a mod's own model, not cut). */
    private final Map<Part, Piece> whole = new EnumMap<>(Part.class);

    ArmorFit(List<Piece> pieces) {
        this.pieces = Collections.unmodifiableList(pieces);
        for (Part part : Part.values()) {
            Piece first = null;
            float[] lo = null;
            float[] hi = null;
            for (Piece p : pieces) {
                if (p.part != part) {
                    continue;
                }
                if (first == null) {
                    first = p;
                    lo = new float[] { p.box[0], p.box[1], p.box[2] };
                    hi = new float[] { p.box[0] + p.box[3], p.box[1] + p.box[4], p.box[2] + p.box[5] };
                } else {
                    for (int i = 0; i < 3; i++) {
                        lo[i] = Math.min(lo[i], p.box[i]);
                        hi[i] = Math.max(hi[i], p.box[i] + p.box[i + 3]);
                    }
                }
            }
            if (first != null) {
                whole.put(
                    part,
                    new Piece(
                        part,
                        part.y,
                        part.y + part.height,
                        first.bone,
                        lo[0],
                        lo[1],
                        lo[2],
                        hi[0] - lo[0],
                        hi[1] - lo[1],
                        hi[2] - lo[2]));
            }
        }
    }

    /** Fits found from geometries, kept while the geometry is. */
    private static final Map<BakedGeometry, ArmorFit> FOUND = new WeakHashMap<>();

    /** The hand-tuned fit of a built-in model, or null. */
    public static ArmorFit of(String modelKey) {
        return modelKey == null ? null : FITS.get(modelKey);
    }

    /**
     * The fit of a model: the hand-tuned one of a built-in model, else the one found from its geometry (null when the
     * geometry has none of the bones).
     */
    public static ArmorFit of(String modelKey, BakedGeometry geometry) {
        ArmorFit fit = of(modelKey);
        if (fit != null || geometry == null) {
            return fit;
        }
        synchronized (FOUND) {
            if (!FOUND.containsKey(geometry)) {
                FOUND.put(geometry, fromGeometry(geometry));
            }
            return FOUND.get(geometry);
        }
    }

    /** The bones a part is cut over; the box of one of them never takes cubes from another. */
    private static final java.util.Set<String> PART_BONES = new java.util.HashSet<>(
        java.util.Arrays.asList(
            "Head",
            "UpperBody",
            "UpBody",
            "Body",
            "RightArm",
            "RightForeArm",
            "LeftArm",
            "LeftForeArm",
            "RightLeg",
            "RightLowerLeg",
            "RightFoot",
            "LeftLeg",
            "LeftLowerLeg",
            "LeftFoot"));

    /** Below this share of a bone's biggest cube, a cube is a detail (a button, an eye) and does not size the box. */
    static final float DETAIL = 0.15f;

    /**
     * A fit from a model's own bones: the head on {@code Head}; the body on {@code UpperBody}, cut in two with a lower
     * {@code UpBody} below it (as Steve's), or on {@code UpBody} or {@code Body}; each arm cut at the elbow
     * ({@code LeftArm}, {@code LeftForeArm}), each leg at the knee and the ankle ({@code LeftLeg},
     * {@code LeftLowerLeg}, {@code LeftFoot}); a part with one bone gets one cut. A bone's box covers its cubes
     * (details left out); a bone without cubes takes those of the bones under it that are no part's bones.
     *
     * @return null when the geometry has none of the bones
     */
    public static ArmorFit fromGeometry(BakedGeometry g) {
        List<Piece> p = new ArrayList<>();
        float[] head = headBox(g);
        if (head != null) {
            p.add(new Piece(Part.HEAD, -8, 0, "Head", head));
            p.add(new Piece(Part.HEADWEAR, -8, 0, "Head", head));
        }
        float[] upper = boneBox(g, "UpperBody");
        float[] lower = boneBox(g, "UpBody");
        if (upper != null && lower != null && lowerTorso(upper, lower)) {
            p.add(new Piece(Part.BODY, 0, 6, "UpperBody", upper));
            p.add(new Piece(Part.BODY, 6, 12, "UpBody", lower));
        } else if (upper != null) {
            p.add(new Piece(Part.BODY, 0, 12, "UpperBody", upper));
        } else if (lower != null) {
            p.add(new Piece(Part.BODY, 0, 12, "UpBody", lower));
        } else {
            float[] body = boneBox(g, "Body");
            if (body != null) {
                p.add(new Piece(Part.BODY, 0, 12, "Body", body));
            }
        }
        limb(g, p, Part.RIGHT_ARM, "RightArm", "RightForeArm", null);
        limb(g, p, Part.LEFT_ARM, "LeftArm", "LeftForeArm", null);
        limb(g, p, Part.RIGHT_LEG, "RightLeg", "RightLowerLeg", "RightFoot");
        limb(g, p, Part.LEFT_LEG, "LeftLeg", "LeftLowerLeg", "LeftFoot");
        return p.isEmpty() ? null : new ArmorFit(p);
    }

    /** An arm (upper, lower) or a leg (upper, lower, foot): cut where it bends, one cut per bone it has. */
    private static void limb(BakedGeometry g, List<Piece> p, Part part, String upperBone, String lowerBone,
        String footBone) {
        float[] upper = boneBox(g, upperBone);
        float[] lower = boneBox(g, lowerBone);
        float[] foot = footBone == null ? null : boneBox(g, footBone);
        float top = part.y;
        float bottom = part.y + part.height;
        float knee = part.y + part.height / 2f;
        float ankle = part.y + part.height * 0.8f;
        if (upper != null && lower != null) {
            lower = narrowed(lower, upper);
            p.add(new Piece(part, top, knee, upperBone, upper));
            if (foot != null) {
                p.add(new Piece(part, knee, ankle, lowerBone, lower));
                p.add(new Piece(part, ankle, bottom, footBone, foot));
            } else {
                p.add(new Piece(part, knee, bottom, lowerBone, lower));
            }
        } else if (upper != null || lower != null) {
            p.add(new Piece(part, top, bottom, upper != null ? upperBone : lowerBone, upper != null ? upper : lower));
        }
    }

    /** How far the helmet may grow past the head's own cube to cover the hair: sides, front and back, and top. */
    static final float HAIR_SIDE = 1.0f, HAIR_TOP = 2.2f;

    /**
     * The head's cube grown over the hair and what else hangs on the head (ears, bangs), but at most
     * {@link #HAIR_SIDE} to the sides and {@link #HAIR_TOP} up, never down: long hair, a tail or a halo does not make
     * the helmet huge.
     */
    static float[] headBox(BakedGeometry g) {
        float[] head = boneBox(g, "Head");
        int bone = g.indexOf("Head");
        if (head == null || bone < 0) {
            return head;
        }
        List<float[]> cubes = new ArrayList<>();
        under(g, bone, cubes);
        float[] lo = { head[0], head[1], head[2] };
        float[] hi = { head[0] + head[3], head[1] + head[4], head[2] + head[5] };
        float[] min = { lo[0] - HAIR_SIDE, lo[1], lo[2] - HAIR_SIDE };
        float[] max = { hi[0] + HAIR_SIDE, hi[1] + HAIR_TOP, hi[2] + HAIR_SIDE };
        for (float[] c : cubes) {
            // only what touches the head's own cube grown by the limits
            boolean near = true;
            for (int i = 0; i < 3; i++) {
                near &= c[i + 3] > min[i] && c[i] < max[i];
            }
            if (!near) {
                continue;
            }
            for (int i = 0; i < 3; i++) {
                lo[i] = Math.max(min[i], Math.min(lo[i], c[i]));
                hi[i] = Math.min(max[i], Math.max(hi[i], c[i + 3]));
            }
        }
        return new float[] { lo[0], lo[1], lo[2], hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2] };
    }

    /**
     * Whether a lower {@code UpBody} box is the lower half of the torso (Steve's), not a skirt, a tail or a belt: it
     * starts where the upper torso ends and is not much wider or deeper.
     */
    static boolean lowerTorso(float[] upper, float[] lower) {
        float upperBottom = upper[1];
        float lowerTop = lower[1] + lower[4];
        return Math.abs(lowerTop - upperBottom) < 2 && lower[1] < upperBottom
            && lower[3] <= upper[3] * 1.3f
            && lower[5] <= upper[5] * 1.5f
            && lower[4] <= upper[4] * 1.5f;
    }

    /**
     * A lower limb box no wider or deeper than 1.5 times the upper limb (a forearm without cubes of its own can take a
     * held thing or a wide sleeve from under it): too wide on an axis, it takes the upper limb's on that axis.
     */
    static float[] narrowed(float[] lower, float[] upper) {
        float[] out = lower.clone();
        for (int i : new int[] { 0, 2 }) {
            if (lower[i + 3] > upper[i + 3] * 1.5f) {
                out[i] = upper[i];
                out[i + 3] = upper[i + 3];
            }
        }
        return out;
    }

    /**
     * The box of a bone's cubes in file pixels (origin, size), details left out; for a bone without cubes, of the
     * cubes under it outside the part bones; null if none.
     */
    static float[] boneBox(BakedGeometry g, String name) {
        int bone = g.indexOf(name);
        if (bone < 0) {
            return null;
        }
        List<float[]> cubes = new ArrayList<>();
        cubes(g, bone, cubes);
        if (cubes.isEmpty()) {
            under(g, bone, cubes);
        }
        if (cubes.isEmpty()) {
            return null;
        }
        float biggest = 0;
        for (float[] c : cubes) {
            biggest = Math.max(biggest, volume(c));
        }
        float[] lo = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE };
        float[] hi = { -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
        for (float[] c : cubes) {
            if (volume(c) < biggest * DETAIL) {
                continue;
            }
            for (int i = 0; i < 3; i++) {
                lo[i] = Math.min(lo[i], c[i]);
                hi[i] = Math.max(hi[i], c[i + 3]);
            }
        }
        for (int i = 0; i < 3; i++) {
            // a flat box (a plane) still needs some thickness to be stretched over
            if (hi[i] - lo[i] < 0.5f) {
                float mid = (lo[i] + hi[i]) / 2;
                lo[i] = mid - 0.25f;
                hi[i] = mid + 0.25f;
            }
        }
        return new float[] { lo[0], lo[1], lo[2], hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2] };
    }

    private static void under(BakedGeometry g, int bone, List<float[]> out) {
        for (int child : g.bones[bone].children) {
            if (PART_BONES.contains(g.bones[child].name)) {
                continue;
            }
            cubes(g, child, out);
            under(g, child, out);
        }
    }

    /** Each cube of a bone as (min x, y, z, max x, y, z) in file pixels (x not negated). */
    private static void cubes(BakedGeometry g, int bone, List<float[]> out) {
        BakedGeometry.Bone b = g.bones[bone];
        Map<Integer, float[]> byCube = new HashMap<>();
        int quads = b.quadCount();
        for (int q = 0; q < quads; q++) {
            int cube = b.cubeOfQuad[q];
            float[] c = byCube.get(cube);
            if (c == null) {
                c = new float[] { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE,
                    -Float.MAX_VALUE };
                byCube.put(cube, c);
            }
            int o = q * BakedGeometry.QUAD_FLOATS;
            for (int k = 0; k < 4; k++) {
                float x = -b.quads[o + k * 5] * 16;
                float y = b.quads[o + k * 5 + 1] * 16;
                float z = b.quads[o + k * 5 + 2] * 16;
                c[0] = Math.min(c[0], x);
                c[1] = Math.min(c[1], y);
                c[2] = Math.min(c[2], z);
                c[3] = Math.max(c[3], x);
                c[4] = Math.max(c[4], y);
                c[5] = Math.max(c[5], z);
            }
        }
        out.addAll(byCube.values());
    }

    private static float volume(float[] c) {
        return Math.max(c[3] - c[0], 0.1f) * Math.max(c[4] - c[1], 0.1f) * Math.max(c[5] - c[2], 0.1f);
    }

    /** The part's whole box on one bone (the bone of its first cut), or null if the fit has no cut of it. */
    public Piece whole(Part part) {
        return whole.get(part);
    }

    /**
     * The matrix that takes a piece's cut of its part (biped part pixels) onto its bone's box, in the bone's locator
     * frame (blocks, model space: y up, the front toward -z, x negated from the file). Each axis is stretched on its
     * own; the armor's inflation is stretched with it.
     *
     * @param pivot the bone's pivot in file pixels (x not negated)
     * @param out   column-major {@code float[16]}
     */
    public static void matrix(Piece piece, float[] pivot, float[] out) {
        Part part = piece.part;
        float[] b = piece.box;
        // the bone's box in the biped's orientation (x and y turned half a turn about z: y down), from the pivot
        float tx0 = b[0] - pivot[0];
        float tx1 = b[0] + b[3] - pivot[0];
        float ty0 = -(b[1] + b[4] - pivot[1]);
        float ty1 = -(b[1] - pivot[1]);
        float tz0 = b[2] - pivot[2];
        float tz1 = b[2] + b[5] - pivot[2];
        float sx = (tx1 - tx0) / part.width;
        float sy = (ty1 - ty0) / (piece.to - piece.from);
        float sz = (tz1 - tz0) / part.depth;
        float ox = tx0 - sx * part.x;
        float oy = ty0 - sy * piece.from;
        float oz = tz0 - sz * part.z;
        // Rz(180°) · S(1/16) · (stretch, then move)
        java.util.Arrays.fill(out, 0);
        out[0] = -sx / 16;
        out[5] = -sy / 16;
        out[10] = sz / 16;
        out[12] = -ox / 16;
        out[13] = -oy / 16;
        out[14] = oz / 16;
        out[15] = 1;
    }

    /** The Steve or Alex model (arms {@code arm} pixels wide): biped boxes cut in two at elbows, knees and waist. */
    private static ArmorFit biped(int arm) {
        List<Piece> p = new ArrayList<>();
        p.add(new Piece(Part.HEAD, -8, 0, "Head", -4, 24, -4, 8, 8, 8));
        p.add(new Piece(Part.HEADWEAR, -8, 0, "Head", -4, 24, -4, 8, 8, 8));
        p.add(new Piece(Part.BODY, 0, 6, "UpperBody", -4, 18, -2, 8, 6, 4));
        p.add(new Piece(Part.BODY, 6, 12, "UpBody", -4, 12, -2, 8, 6, 4));
        p.add(new Piece(Part.RIGHT_ARM, -2, 4, "RightArm", -4 - arm, 17.5f, -2, arm, 6, 4));
        p.add(new Piece(Part.RIGHT_ARM, 4, 10, "RightForeArm", -4 - arm, 11.5f, -2, arm, 6, 4));
        p.add(new Piece(Part.LEFT_ARM, -2, 4, "LeftArm", 4, 17.5f, -2, arm, 6, 4));
        p.add(new Piece(Part.LEFT_ARM, 4, 10, "LeftForeArm", 4, 11.5f, -2, arm, 6, 4));
        p.add(new Piece(Part.RIGHT_LEG, 0, 6, "RightLeg", -3.9f, 6.07942f, -2.00324f, 4, 6, 4));
        p.add(new Piece(Part.RIGHT_LEG, 6, 12, "RightLowerLeg", -3.9f, 0.07942f, -2.00324f, 4, 6, 4));
        p.add(new Piece(Part.LEFT_LEG, 0, 6, "LeftLeg", -0.1f, 6.07942f, -2.00324f, 4, 6, 4));
        p.add(new Piece(Part.LEFT_LEG, 6, 12, "LeftLowerLeg", -0.1f, 0.07942f, -2.00324f, 4, 6, 4));
        return new ArmorFit(p);
    }

    /**
     * The default model: smaller head (with its hair inside the helmet), one torso bone, arms and legs in two, feet
     * with toes. The boxes cover the model's cubes (sleeves, hair) so they stay inside the armor.
     */
    private static ArmorFit defaultModel() {
        List<Piece> p = new ArrayList<>();
        p.add(new Piece(Part.HEAD, -8, 0, "Head", -4.4f, 32.8f, -4.6f, 8.8f, 9.2f, 9.2f));
        p.add(new Piece(Part.HEADWEAR, -8, 0, "Head", -4.4f, 32.8f, -4.6f, 8.8f, 9.2f, 9.2f));
        p.add(new Piece(Part.BODY, 0, 12, "UpperBody", -3.7f, 20.6f, -3.3f, 7.4f, 11.4f, 5.4f));
        p.add(new Piece(Part.RIGHT_ARM, -2, 4, "RightArm", -5.7f, 24.9f, -1.7f, 3.25f, 7.3f, 3.4f));
        p.add(new Piece(Part.RIGHT_ARM, 4, 10, "RightForeArm", -5.725f, 18.075f, -1.575f, 3.2f, 7, 3.2f));
        p.add(new Piece(Part.LEFT_ARM, -2, 4, "LeftArm", 2.45f, 24.9f, -1.7f, 3.25f, 7.3f, 3.4f));
        p.add(new Piece(Part.LEFT_ARM, 4, 10, "LeftForeArm", 2.525f, 18.075f, -1.575f, 3.2f, 7, 3.2f));
        p.add(new Piece(Part.RIGHT_LEG, 0, 6, "RightLeg", -4.025f, 11.65f, -2, 3.65f, 10, 4));
        p.add(new Piece(Part.RIGHT_LEG, 6, 9.5f, "RightLowerLeg", -3.9f, 4, -1.9f, 3.4f, 7.7f, 4));
        p.add(new Piece(Part.RIGHT_LEG, 9.5f, 12, "RightFoot", -4.125f, 0, -4.4f, 3.875f, 4, 6.675f));
        p.add(new Piece(Part.LEFT_LEG, 0, 6, "LeftLeg", 0.375f, 11.65f, -2, 3.65f, 10, 4));
        p.add(new Piece(Part.LEFT_LEG, 6, 9.5f, "LeftLowerLeg", 0.5f, 4, -1.9f, 3.4f, 7.7f, 4));
        p.add(new Piece(Part.LEFT_LEG, 9.5f, 12, "LeftFoot", 0.25f, 0, -4.4f, 3.875f, 4, 6.675f));
        return new ArmorFit(p);
    }
}
