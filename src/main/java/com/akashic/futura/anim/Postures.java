package com.akashic.futura.anim;

/**
 * A posture another mod puts a player in ({@code FuturaModels.setPosture}, API 7), kept as the external state
 * {@link #KEY}: sitting where there is no vehicle (a chair, a bench: the model's {@code sit}, as on a vehicle that is
 * neither pig, mount nor boat) or lying where there is no bed (a bunk, a hammock: the model's {@code sleep}, laid down
 * with the head towards a yaw). Without Minecraft.
 */
public final class Postures {

    /** The external state's key. */
    public static final String KEY = "posture";
    /** Value: sitting. */
    public static final String SIT = "sit";
    /**
     * Value prefix: lying, followed by the yaw the head points to, in degrees (Minecraft's: 0 is +z, 90 is -x), and
     * optionally by {@code :} and how much higher than the player's position the model is drawn, in blocks (a local
     * player lowered so that his eyes are near the pillow).
     */
    public static final String LIE = "lie:";
    /** Half the body of one who lies: the model is laid with its middle on the player's position. */
    public static final float HALF_BODY = 0.9f;

    private Postures() {}

    /** The state's value for a lying player. */
    public static String lie(float headYaw) {
        return LIE + headYaw;
    }

    public static boolean sits(String value) {
        return SIT.equals(value);
    }

    public static boolean lies(String value) {
        return value != null && value.startsWith(LIE);
    }

    private static float number(String value, int index) {
        if (!lies(value)) {
            return 0;
        }
        String[] parts = value.substring(LIE.length())
            .split(":", -1);
        if (index >= parts.length) {
            return 0;
        }
        try {
            float v = Float.parseFloat(parts[index]);
            return Float.isNaN(v) || Float.isInfinite(v) ? 0 : v;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The yaw the head of a lying player points to; 0 when the value has none that can be read. */
    public static float headYaw(String value) {
        return number(value, 0);
    }

    /** How much higher than the position the lying model is drawn; 0 when the value does not say, at most 2. */
    public static float rise(String value) {
        return Math.max(0, Math.min(2, number(value, 1)));
    }

    /**
     * The turn about y vanilla gives a sleeper ({@code getBedOrientationInDegrees}) for a head pointing to
     * {@code headYaw}: a bed to the south (+z, yaw 0) is 90, to the west (-x, yaw 90) is 0.
     */
    public static float bedDegrees(float headYaw) {
        return 90 - headYaw;
    }

    /** The value of {@link #KEY} among a player's states, or null. */
    public static String of(ExternalStates.Entry states) {
        return states == null ? null : states.get(KEY);
    }
}
