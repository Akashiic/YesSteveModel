package com.akashic.futura.anim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SwimStateTest {

    @Test
    void sprintingUnderWaterStartsAndSprintingInWaterKeepsIt() {
        SwimState s = new SwimState();
        assertFalse(s.update(true, true, false, false, false), "head above water: not yet");
        assertFalse(s.update(false, true, true, false, false), "not sprinting");
        assertTrue(s.update(true, true, true, false, false), "Ctrl under water");
        assertTrue(s.update(true, true, false, false, false), "surfacing keeps it while in water");
        assertFalse(s.update(true, false, false, false, false), "out of the water");
        assertTrue(s.update(true, true, true, false, false));
        assertFalse(s.update(false, true, true, false, false), "stops sprinting");
    }

    @Test
    void ridersAndFlyersDoNotSwim() {
        SwimState s = new SwimState();
        assertFalse(s.update(true, true, true, true, false));
        assertFalse(s.update(true, true, true, false, true));
    }
}
