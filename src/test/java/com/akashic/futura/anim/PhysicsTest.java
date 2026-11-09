package com.akashic.futura.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import com.akashic.futura.anim.physics.FirstOrder;
import com.akashic.futura.anim.physics.PhysicsManager;
import com.akashic.futura.anim.physics.SecondOrder;

/** {@code ysm.second_order} / {@code ysm.first_order} against values worked out by hand. */
class PhysicsTest {

    private static final float EPS = 1e-4f;

    @Test
    void secondOrderStepByStep() {
        // f = 1, ζ = 0.5, r = 0, a step input of 1, dt = 0.05 s
        // k1 = 0.5/π = 0.159155, k2 = 1/(2π)² = 0.0253303, k3 = 0
        // max step = sqrt(4·k2 + k1²) − k1 = 0.196725 -> one sub-step of 0.05
        SecondOrder s = new SecondOrder(1, 1, 0.5f, 0);
        s.update(0.05f);
        // y = 0 + h·0 = 0; ẏ = 0 + h·(1 − 0 − 0)/k2 = 1.973921
        assertEquals(0, s.getValue(), EPS);
        s.update(0.05f);
        // y = h·1.973921 = 0.098696
        assertEquals(0.098696f, s.getValue(), EPS);
        s.update(0.05f);
        // ẏ = 1.973921 + h·(1 − 0.098696 − k1·1.973921)/k2 = 3.132900; y = 0.098696 + h·3.132900 = 0.255341
        assertEquals(0.255341f, s.getValue(), EPS);
    }

    @Test
    void secondOrderResponseTermReactsToTheInputVelocity() {
        // r = 1: k3 = ζ/(2πf) = 0.0795775; first step ẋ = 1/0.05 = 20
        // ẏ = h·(k3·20 + 1)/k2 = 0.05·2.591550/0.0253303 = 5.115550 -> next y = 0.255778
        SecondOrder s = new SecondOrder(1, 1, 0.5f, 1);
        s.update(0.05f);
        assertEquals(0, s.getValue(), EPS);
        s.update(0.05f);
        assertEquals(0.255776f, s.getValue(), 2e-4f);
    }

    @Test
    void secondOrderSubdividesLongSteps() {
        // f = 5, ζ = 1: max step = 0.0263696 < 0.05 -> 2 sub-steps per update
        SecondOrder s = new SecondOrder(1, 5, 1, 1);
        s.update(0.05f);
        assertEquals(1.009549f, s.getValue(), EPS);
        s.update(0.05f);
        assertEquals(1.039207f, s.getValue(), EPS);
    }

    @Test
    void secondOrderClampsAndSurvivesZeroFrequency() {
        SecondOrder clamped = new SecondOrder(1, 50, 3, 0);
        SecondOrder reference = new SecondOrder(1, 5, 1, 0);
        for (int i = 0; i < 5; i++) {
            clamped.update(0.05f);
            reference.update(0.05f);
        }
        assertEquals(reference.getValue(), clamped.getValue(), 0);
        SecondOrder zero = new SecondOrder(1, 0, 1, 1);
        for (int i = 0; i < 5; i++) {
            zero.update(0.05f);
            assertFalse(Float.isNaN(zero.getValue()));
        }
        assertEquals(0, zero.getValue(), 0);
    }

    @Test
    void firstOrderStepByStep() {
        // R = 0.5, dt = 0.05: y1 = 0.1·1 = 0.1, y2 = 0.9·0.1 + 0.1 = 0.19, y3 = 0.9·0.19 + 0.1 = 0.271
        FirstOrder f = new FirstOrder(1, 0.5f);
        f.update(0.05f);
        assertEquals(0.1f, f.getValue(), EPS);
        f.update(0.05f);
        assertEquals(0.19f, f.getValue(), EPS);
        f.update(0.05f);
        assertEquals(0.271f, f.getValue(), EPS);
        FirstOrder instant = new FirstOrder(3, 0);
        instant.update(0.05f);
        assertEquals(3, instant.getValue(), 0);
    }

    @Test
    void managerReturnsTheRawInputFirstAndUpdatesFromRenderTicks() {
        PhysicsManager m = new PhysicsManager();
        // first call creates the filter and returns the input as is
        assertEquals(1, m.call("hair", 1, true, 0.5f, 1, 1), 0);
        assertNotNull(m.get("hair"));
        // the first update only sets the baseline (and only once render time is above 0)
        m.update(0);
        m.update(10);
        assertEquals(0, m.call("hair", 1, true, 0.5f, 1, 1), 0);
        // one tick later: dt = 0.05 s
        m.update(11);
        assertEquals(0.1f, m.call("hair", 1, true, 0.5f, 1, 1), EPS);
        // time not moving: no update
        m.update(11);
        assertEquals(0.1f, m.call("hair", 1, true, 0.5f, 1, 1), EPS);
        // the same name shares the filter, whatever the order
        assertSame(m.get("hair"), m.get("hair"));
        // an empty name is 0
        assertEquals(0, m.call("", 5, false, 1, 1, 1), 0);
        m.reset();
        assertEquals(7, m.call("hair", 7, false, 1, 1, 1), 0);
    }
}
