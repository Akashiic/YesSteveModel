package com.akashic.futura.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The first-person arms see the body's roaming variables and keep their other variables to themselves. */
class SharedRoamingTest {

    @Test
    void roamingGoesToTheSharedStoreAndTheRestStaysLocal() {
        MolangVariables body = new MolangVariables();
        MolangVariables arm = new MolangVariables(body);
        body.set("roaming.hat", MolangValue.of(1));
        assertEquals(
            1,
            arm.get("roaming.hat")
                .asNumber(),
            0);

        arm.set("roaming.hat", MolangValue.of(3));
        arm.set("local", MolangValue.of(5));
        assertEquals(
            3,
            body.get("roaming.hat")
                .asNumber(),
            0);
        assertFalse(body.has("local"));
        assertTrue(arm.has("local"));

        arm.remove("roaming.hat");
        assertNull(body.getOrNull("roaming.hat"));

        body.set("roaming.kept", MolangValue.ONE);
        arm.clear();
        assertTrue(arm.has("roaming.kept"));
        assertFalse(arm.has("local"));
    }

    @Test
    void expressionsWriteThroughTheSharedStore() {
        FakeContext body = new FakeContext();
        MolangVariables arm = new MolangVariables(body.variables);
        MolangContext armContext = new MolangContext() {

            @Override
            public MolangValue call(String namespace, String name, MolangValue[] args) {
                return null;
            }

            @Override
            public MolangVariables variables() {
                return arm;
            }
        };
        Molang.parse("v.roaming.a = 2; v.b = 4;")
            .evaluate(armContext);
        assertEquals(
            2,
            body.variables.get("roaming.a")
                .asNumber(),
            0);
        assertFalse(body.variables.has("b"));
        assertEquals(
            6,
            Molang.parse("r.a + v.b")
                .evalNumber(armContext),
            0);
    }
}
