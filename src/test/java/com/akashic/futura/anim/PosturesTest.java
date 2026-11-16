package com.akashic.futura.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class PosturesTest {

    @Test
    void readsWhatItWrites() {
        assertTrue(Postures.sits(Postures.SIT));
        assertFalse(Postures.lies(Postures.SIT));
        String lie = Postures.lie(-135.5f);
        assertTrue(Postures.lies(lie));
        assertFalse(Postures.sits(lie));
        assertEquals(-135.5f, Postures.headYaw(lie), 1e-6);
    }

    @Test
    void aBrokenValueStandsOrPointsNowhere() {
        assertFalse(Postures.sits(null));
        assertFalse(Postures.lies(null));
        assertFalse(Postures.lies("lying"));
        assertEquals(0f, Postures.headYaw("lie:abc"), 0);
        assertEquals(0f, Postures.headYaw("lie:NaN"), 0);
        assertEquals(0f, Postures.headYaw(Postures.SIT), 0);
    }

    /** Vanilla's bed table: south (+z, yaw 0) 90, west (yaw 90) 0, north (yaw 180) 270, east (yaw 270) 180. */
    @Test
    void theTurnIsVanillasBedTurn() {
        assertEquals(90f, Postures.bedDegrees(0), 0);
        assertEquals(0f, Postures.bedDegrees(90), 0);
        assertEquals(270f, (Postures.bedDegrees(180) + 360) % 360, 0);
        assertEquals(180f, (Postures.bedDegrees(270) + 360) % 360, 0);
    }

    @Test
    void theRiseIsOptionalAndBounded() {
        assertEquals(0f, Postures.rise(Postures.lie(10)), 0);
        assertEquals(1.17f, Postures.rise("lie:10.0:1.17"), 1e-6);
        assertEquals(10f, Postures.headYaw("lie:10.0:1.17"), 0);
        assertEquals(2f, Postures.rise("lie:0:99"), 0);
        assertEquals(0f, Postures.rise("lie:0:-3"), 0);
        assertEquals(0f, Postures.rise("lie:0:x"), 0);
    }

    @Test
    void itIsAnExternalState() {
        ExternalStates states = new ExternalStates();
        UUID id = UUID.randomUUID();
        assertNull(Postures.of(states.of(id)));
        states.set(id, Postures.KEY, Postures.SIT);
        assertEquals(Postures.SIT, Postures.of(states.of(id)));
        states.set(id, Postures.KEY, null);
        assertNull(Postures.of(states.of(id)));
    }
}
