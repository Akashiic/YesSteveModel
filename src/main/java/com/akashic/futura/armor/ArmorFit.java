package com.akashic.futura.armor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where worn armor goes on a built-in model: each part of the vanilla biped armor (the head, the body, the arms and
 * legs) is cut where the model bends (elbows, knees, ankles) and every cut is stretched over the box of one of the
 * model's bones, so the armor follows the model's own proportions and animation. Only the default model and the
 * Steve/Alex models have a fit: a custom model draws no armor.
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

    /** The fit of a model, or null when it draws no armor. */
    public static ArmorFit of(String modelKey) {
        return modelKey == null ? null : FITS.get(modelKey);
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
