package com.akashic.futura.anim;

import java.util.Locale;

/**
 * Fishing on the models: what the {@code player.fishing} controller plays while the main hand holds a fishing rod.
 * Not from upstream (YSM has only {@code hold_mainhand:fishing} and {@code swing:fishing_rod}): the whole act of
 * fishing as one state machine, so the body follows the rod and the bobber instead of a generic swing.
 *
 * <pre>
 * draw ─► (ready) ─swing─► cast ─line out─► wait ◄──────────────┐
 *                                            │ bite ─► fight ⇄ reel
 *                                            │          │       │
 *                         line back ─► retrieve    catch / escape
 * </pre>
 *
 * The client sees some moments on its own: the rod coming to the hand ({@code fishing:draw}), the cast (a swing with
 * the line in, or the line going out without one, as when a plugin recasts), the line out ({@code fishing:wait}) and
 * the line coming back without a fish ({@code fishing:retrieve}). The fight is known only to the server; a mod that
 * hears it (Kunftig, from the {@code kunftig:rod} channel) says it with two external states
 * ({@code FuturaModels.setExternalState}): {@link #STATE}, a rod state name ({@code mordida}, {@code briga},
 * {@code recolher}, {@code captura}, {@code escapou}, {@code parada}, or the English names), and {@link #TENSION}, the
 * fight's tension from 0 to 1, which the clips read as {@code ctrl.fishing_tension}. A state set again (cleared and
 * set) starts again, like a second bite.
 * <p>
 * Every model falls back to the default model's {@code fishing:*} set, and a model can bring its own clips of the same
 * names. Without the clip of a phase the controller stops and the old {@code hold_mainhand:fishing} /
 * {@code swing:fishing_rod} pair plays, as before. No Minecraft classes.
 */
public final class FishingStates {

    /** A rod state name (Kunftig's {@code KunftigRods} names, or English ones); set again to restart it. */
    public static final String STATE = "fishing.state";
    /** The fight's tension, a number from 0 to 1 (as text). */
    public static final String TENSION = "fishing.tension";
    /** The item class of a fishing rod ({@code ItemInfo#classify}). */
    public static final String CLASSIFY = "fishing_rod";
    /** The animation namespace. */
    public static final String PREFIX = "fishing:";

    /**
     * Ticks after a cast during which the line going out is that same cast (the bobber appears one round trip after
     * the local click) rather than a new one.
     */
    static final float CAST_GRACE_TICKS = 40;
    /** Ticks after a predicted retrieve (a swing with the line out) during which the line coming back is that one. */
    static final float RETRIEVE_GRACE_TICKS = 20;
    /** How fast the smoothed tension follows the server's, per tick (it travels in steps of a hundredth). */
    static final float TENSION_RATE = 0.35f;
    /** How fast the heave follows the minigame key (sneak), per tick: about 90% in three ticks. */
    static final float HEAVE_RATE = 0.55f;

    /** What the rod is doing. */
    public enum Phase {

        /** No rod in the main hand. */
        NONE(null, false, 0),
        /** A rod in the hand, line in: nothing of ours plays (the hold and main controllers do). */
        READY(null, false, 0.15f),
        DRAW("draw", false, 0.1f),
        CAST("cast", false, 0.04f),
        WAIT("wait", true, 0.3f),
        BITE("bite", false, 0.03f),
        FIGHT("fight", true, 0.12f),
        REEL("reel", true, 0.15f),
        CATCH("catch", false, 0.05f),
        ESCAPE("escape", false, 0.06f),
        RETRIEVE("retrieve", false, 0.04f);

        /** The clip ({@code fishing:<name>}), or null for none. */
        public final String animation;
        /** Whether the clip loops (the others play once and hand over). */
        public final boolean loops;
        /** Beginning transition of the clip, in seconds. */
        public final float transitionSeconds;

        Phase(String name, boolean loops, float transitionSeconds) {
            this.animation = name == null ? null : PREFIX + name;
            this.loops = loops;
            this.transitionSeconds = transitionSeconds;
        }

        /** Whether the server's fight is on (a swing then is not a cast nor a retrieve). */
        boolean fighting() {
            return this == BITE || this == FIGHT || this == REEL;
        }
    }

    private FishingStates() {}

    /**
     * The phase a rod state name asks for: Kunftig's {@code KunftigRods} names and English ones, case and blanks
     * ignored, an optional {@code fishing:} prefix dropped.
     *
     * @return null for null, blank or an unknown name
     */
    public static Phase fromState(String state) {
        if (state == null) {
            return null;
        }
        String s = state.trim()
            .toLowerCase(Locale.ROOT);
        if (s.startsWith(PREFIX)) {
            s = s.substring(PREFIX.length());
        }
        switch (s) {
            case "parada":
            case "idle":
                return Phase.READY;
            case "sacar":
            case "draw":
                return Phase.DRAW;
            case "lancar":
            case "lançar":
            case "cast":
                return Phase.CAST;
            case "espera":
            case "wait":
                return Phase.WAIT;
            case "mordida":
            case "bite":
                return Phase.BITE;
            case "briga":
            case "briga_erro": // AkashicFishing's miss reaction: the fight goes on (the tension jolts the pose)
            case "fight":
                return Phase.FIGHT;
            case "recolher":
            case "reel":
                return Phase.REEL;
            case "captura":
            case "captura_rara": // AkashicFishing's bigger catch parties (épico, lendário and mítico)
            case "captura_mitica":
            case "catch":
                return Phase.CATCH;
            case "escapou":
            case "fuga":
            case "escape":
                return Phase.ESCAPE;
            case "retrieve":
                return Phase.RETRIEVE;
            default:
                return null;
        }
    }

    /** The tension in {@code value} (a number as text), held to 0..1; 0 for null or not a number. */
    public static float parseTension(String value) {
        if (value == null) {
            return 0;
        }
        try {
            float t = Float.parseFloat(value.trim());
            return t > 0 ? Math.min(1f, t) : 0f; // NaN and negatives are 0
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Whether the player holds a fishing rod in the main hand (or has a line out, which needs one). */
    public static boolean holdsRod(PlayerInputs in) {
        return CLASSIFY.equals(in.mainhand().classify) || in.isFishing();
    }

    /**
     * Whether the {@code player.fishing} controller takes the rod's swings: the player holds a rod and the model (or
     * the default model behind it) has {@code fishing:cast}. Then {@code swing:fishing_rod} does not play.
     */
    public static boolean claimsSwing(PlayerInputs in, AnimationLibrary library) {
        return holdsRod(in) && library.get(Phase.CAST.animation) != null;
    }

    /**
     * The main state during the fight: standing instead of crouching. Plugins use sneak as the minigame key (the
     * AkashicFishing fight is a run of Shift presses), so each press would drop the body into the crouch and back; the
     * fight's clip turns the key into a heave of the rod instead ({@code ctrl.fishing_heave}).
     */
    public static String braced(String mainState) {
        if ("sneaking".equals(mainState)) {
            return MainStates.IDLE;
        }
        if ("sneak".equals(mainState)) {
            return "walk";
        }
        return mainState;
    }

    /** The fight's tension the player's inputs carry, 0 to 1. */
    public static float tension(PlayerInputs in) {
        return parseTension(in.externalState(TENSION));
    }

    /**
     * One player's rod, frame by frame. {@link #update} once per animated frame (the {@code player.fishing}
     * predicate); {@link #serial} changes with every phase started, so the controller restarts a phase started again.
     */
    public static final class Tracker {

        private Phase phase = Phase.NONE;
        private float since;
        private int serial;
        private boolean seen;
        private boolean lastLine;
        private int lastExternal = -1;
        private String lastExternalValue;
        private boolean lastSwinging;
        private float lastCast = Float.NEGATIVE_INFINITY;
        private float lastRetrieve = Float.NEGATIVE_INFINITY;
        private float lastNow = Float.NaN;
        private float tension;
        private float heave;

        public Phase phase() {
            return phase;
        }

        /** Changes with every phase started (the same phase too). */
        public int serial() {
            return serial;
        }

        /**
         * The fight's tension, 0 to 1, eased toward the server's ({@link #TENSION}) so a step does not jerk the pose:
         * {@code ctrl.fishing_tension}.
         */
        public float tension() {
            return tension;
        }

        /**
         * 0 to 1: how hard the player heaves on the rod. During the fight the minigame key is sneak, so each press
         * pulls the rod up and back ({@code ctrl.fishing_heave}); eased, 0 outside the fight.
         */
        public float heave() {
            return heave;
        }

        /** Ticks into the current phase at {@code now}. */
        public float elapsed(float now) {
            return Math.max(0, now - since);
        }

        /**
         * Advances to {@code now} (animation ticks).
         *
         * @param library where the clips are looked up (their length ends the phases that play once)
         * @return the phase to play
         */
        public Phase update(PlayerInputs in, float now, AnimationLibrary library) {
            boolean holds = holdsRod(in);
            boolean line = in.isFishing();
            boolean swinging = in.isSwinging() && !in.isSwingingOffhand();
            boolean swingStart = swinging && (in.swingTime() == 0 || !lastSwinging);
            int external = in.externalGeneration(STATE);
            String externalValue = in.externalState(STATE);
            // the generation, and the value too: a store that was recreated (a new world) counts from 1 again
            boolean externalChanged = external != lastExternal
                || (externalValue == null ? lastExternalValue != null : !externalValue.equals(lastExternalValue));
            lastExternal = external;
            lastExternalValue = externalValue;
            lastSwinging = swinging;
            ease(in, now);
            if (!holds || in.isSleeping()) {
                phase = Phase.NONE;
                lastLine = line;
                seen = true;
                return phase;
            }
            if (phase == Phase.NONE) {
                // a rod comes to the hand: drawn, unless the player was just seen (already holding it); seen in the
                // middle of a fight (he came close), straight into it
                Phase asked = fromState(in.externalState(STATE));
                if (line && (asked == Phase.FIGHT || asked == Phase.REEL)) {
                    begin(asked, now);
                } else {
                    begin(
                        line ? Phase.WAIT
                            : seen && library.get(Phase.DRAW.animation) != null ? Phase.DRAW : Phase.READY,
                        now);
                }
                lastLine = line;
                seen = true;
                return phase;
            }
            seen = true;

            // the server's word (through the external state) first: the fight
            if (externalChanged) {
                Phase asked = fromState(in.externalState(STATE));
                if (asked == Phase.CAST) {
                    cast(now);
                } else if (asked == Phase.READY) {
                    if (phase.fighting()) {
                        // back to rest in the middle of the fight with the line still out: the fish got away (a rod
                        // without a fuga state is told parada); with the line gone, the fight just ended
                        begin(line ? Phase.ESCAPE : Phase.READY, now);
                    }
                } else if (asked == Phase.FIGHT && phase == Phase.FIGHT) {
                    // a miss reaction and back (briga_erro, briga): the loop goes on
                } else if (asked != null && asked != Phase.DRAW) {
                    begin(asked, now);
                }
            }

            // what the client sees: swings and the line
            if (swingStart && !phase.fighting() && phase != Phase.CATCH) {
                // the line as it was before this frame: a swing that comes with the bobber is the cast
                if (!lastLine) {
                    cast(now);
                } else if (phase != Phase.RETRIEVE) {
                    begin(Phase.RETRIEVE, now); // a right click reels the line in (the bobber goes a moment later)
                    lastRetrieve = now;
                }
            }
            if (line && !lastLine) {
                // the bobber appeared: the cast just started, or one without a swing (a plugin recasting)
                if (now - lastCast > CAST_GRACE_TICKS) {
                    cast(now);
                } else if (phase != Phase.CAST) {
                    begin(Phase.WAIT, now);
                }
            } else if (!line && lastLine) {
                // the line came back: a catch or an escape keeps playing; otherwise the rod flicks back
                if (phase != Phase.CATCH && phase != Phase.ESCAPE
                    && !(phase == Phase.RETRIEVE && now - lastRetrieve <= RETRIEVE_GRACE_TICKS)) {
                    begin(Phase.RETRIEVE, now);
                    lastRetrieve = now;
                }
            }
            lastLine = line;

            // a clip that plays once hands over at its end
            if (!phase.loops && phase.animation != null) {
                AnimationClip clip = library.get(phase.animation);
                // the controller blends in first, then plays the clip: the phase lasts both
                if (clip == null || elapsed(now) >= clip.length + phase.transitionSeconds * 20) {
                    begin(line ? Phase.WAIT : Phase.READY, now);
                }
            } else if (phase.loops && !line) {
                begin(Phase.READY, now); // the line is in: nothing to wait for or fight
            }
            if (phase == Phase.READY && line) {
                begin(Phase.WAIT, now);
            }
            return phase;
        }

        private void ease(PlayerInputs in, float now) {
            float dt = Float.isNaN(lastNow) ? 0 : Math.max(0, Math.min(20, now - lastNow));
            lastNow = now;
            float targetTension = FishingStates.tension(in);
            float targetHeave = phase.fighting() && in.isSneaking() ? 1 : 0;
            tension = approach(tension, targetTension, TENSION_RATE, dt);
            heave = approach(heave, targetHeave, HEAVE_RATE, dt);
        }

        /** Moves {@code from} toward {@code to} by {@code rate} of the gap per tick, frame-rate independent. */
        static float approach(float from, float to, float rate, float ticks) {
            if (ticks <= 0) {
                return from;
            }
            float k = 1 - (float) Math.pow(1 - rate, ticks);
            float v = from + (to - from) * k;
            return Math.abs(v - to) < 1e-3f ? to : v;
        }

        private void cast(float now) {
            if (phase == Phase.CAST && now - lastCast <= CAST_GRACE_TICKS) {
                return; // the click started it; the bobber appearing (or the server) is the same cast
            }
            begin(Phase.CAST, now);
            lastCast = now;
        }

        private void begin(Phase p, float now) {
            phase = p;
            since = now;
            serial++;
        }
    }
}
