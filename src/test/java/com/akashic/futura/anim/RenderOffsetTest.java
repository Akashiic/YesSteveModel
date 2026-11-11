package com.akashic.futura.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RenderOffsetTest {

    /** 1.20's crouching pose: 0.125 blocks lower; never flying, gliding, swimming, crawling, asleep, riding or dead. */
    @Test
    void onlyTheCrouchingPoseIsLowered() {
        PlayerSnapshot s = new PlayerSnapshot();
        assertEquals(0, RenderOffset.of(s));
        s.sneaking = true;
        assertEquals(-0.125f, RenderOffset.of(s));
        // in the air too, as 1.20 (shift held off the ground still crouches)
        s.onGround = false;
        assertEquals(-0.125f, RenderOffset.of(s));
        s.onGround = true;
        for (int i = 0; i < 6; i++) {
            PlayerSnapshot t = new PlayerSnapshot();
            t.sneaking = true;
            switch (i) {
                case 0:
                    t.flying = true;
                    break;
                case 1:
                    t.fallFlying = true;
                    break;
                case 2:
                    t.swimming = true;
                    break;
                case 3:
                    t.crawling = true;
                    break;
                case 4:
                    t.sleeping = true;
                    break;
                default:
                    t.riding = true;
            }
            assertEquals(0, RenderOffset.of(t), "case " + i);
        }
    }
}
