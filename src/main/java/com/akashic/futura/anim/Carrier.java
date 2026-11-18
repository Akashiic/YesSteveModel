package com.akashic.futura.anim;

/**
 * What carries a player who stands on something that moves and is no entity (a Kunftig display: a ship's deck): the
 * external state {@link #KEY}, {@code "dx:dz"}, how far that thing moved under him this tick, in blocks. Another
 * player's speed is read from how his position changes, and on a moving deck that is the deck's motion: his model
 * walked and ran while he stood still. The mod that carries him says how much of the change is not his own. Without
 * Minecraft.
 */
public final class Carrier {

    public static final String KEY = "carrier";
    /** No tick moves a player farther than this, in blocks: a value beyond it is not believed. */
    public static final double MAX = 8;

    private Carrier() {}

    public static String value(double dx, double dz) {
        return Math.round(dx * 1000) / 1000.0 + ":" + Math.round(dz * 1000) / 1000.0;
    }

    /** {dx, dz}; zeros when there is no value or it cannot be read. */
    public static double[] parse(String value) {
        if (value != null) {
            int i = value.indexOf(':');
            if (i > 0) {
                try {
                    double dx = Double.parseDouble(value.substring(0, i));
                    double dz = Double.parseDouble(value.substring(i + 1));
                    if (Math.abs(dx) <= MAX && Math.abs(dz) <= MAX) {
                        return new double[] { dx, dz };
                    }
                } catch (NumberFormatException e) {
                    // not a carrier: nothing carries him
                }
            }
        }
        return new double[] { 0, 0 };
    }

    /** The carrier of a player among his states. */
    public static double[] of(ExternalStates.Entry states) {
        return parse(states == null ? null : states.get(KEY));
    }
}
