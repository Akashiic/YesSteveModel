package com.akashic.futura.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RemoteMotionTest {

    private int tick;

    private void step(RemoteMotion m, double dx, double dy, boolean ground) {
        m.update(++tick, dx, dy, 0, ground, false, false);
    }

    @Test
    void walkingOnTheGroundIsWalking() {
        RemoteMotion m = new RemoteMotion();
        for (int i = 0; i < 10; i++) {
            step(m, 0.2, 0, true);
        }
        assertTrue(m.onGround());
        assertFalse(m.flying());
        assertEquals(0.2, m.motionX(), 1e-9, "ground speed from the positions");
        assertEquals(10 * 0.2 * 0.6, m.walked(), 1e-4);
    }

    @Test
    void aJumpLeavesTheGroundAtOnceAndLands() {
        RemoteMotion m = new RemoteMotion();
        step(m, 0, 0, true);
        double[] arc = { 0.42, 0.33, 0.25, 0.17, 0.1, 0.02, -0.06, -0.14, -0.22, -0.3, -0.37, -0.25 };
        for (double dy : arc) {
            step(m, 0.1, dy, false);
            assertFalse(m.onGround(), "in the air at dy " + dy);
            assertFalse(m.flying());
        }
        step(m, 0.1, 0, true);
        assertTrue(m.onGround());
    }

    @Test
    void stairsDownKeepTheGroundBriefly() {
        RemoteMotion m = new RemoteMotion();
        step(m, 0.2, 0, true);
        step(m, 0.2, -0.17, false);
        step(m, 0.2, -0.17, false);
        assertTrue(m.onGround(), "between two steps");
        step(m, 0.2, -0.16, true);
        assertTrue(m.onGround());
        // a real fall: after the grace ticks he is in the air
        for (int i = 0; i < 5; i++) {
            step(m, 0, -0.1 * (i + 1), false);
        }
        assertFalse(m.onGround());
    }

    @Test
    void hoveringInTheAirIsFlyingUntilHeLandsOrFalls() {
        RemoteMotion m = new RemoteMotion();
        step(m, 0, 0, true);
        step(m, 0, 0.4, false);
        for (int i = 0; i < RemoteMotion.HOVER_TICKS; i++) {
            step(m, 0.5, 0, false);
        }
        assertTrue(m.flying());
        step(m, 0.5, 0.3, false);
        assertTrue(m.flying(), "flying up stays flying");
        step(m, 0, -0.6, false);
        assertFalse(m.flying(), "falling fast ends it");
        for (int i = 0; i < RemoteMotion.HOVER_TICKS; i++) {
            step(m, 0, 0, false);
        }
        assertTrue(m.flying());
        step(m, 0, -0.1, true);
        assertFalse(m.flying());
        assertTrue(m.onGround());
    }

    @Test
    void theSameTickIsCountedOnce() {
        RemoteMotion m = new RemoteMotion();
        m.update(5, 1, 0, 0, true, false, false);
        assertFalse(m.stale(5));
        m.update(5, 1, 0, 0, true, false, false);
        assertEquals(0.6, m.walked(), 1e-6);
        assertTrue(m.stale(6));
    }
}
