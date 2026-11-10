package com.akashic.futura.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akashic.futura.molang.MolangValue;

/** The local tracker, the store of the other players' variables and the ysm.sync seam. */
class RoamingSyncTest {

    private static Map<String, Float> vars(Object... kv) {
        Map<String, Float> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], ((Number) kv[i + 1]).floatValue());
        }
        return m;
    }

    @Test
    void trackerSendsChangesNotRepeats() {
        RoamingTracker t = new RoamingTracker();
        List<Message.RoamingUpdate> first = t.poll(vars("back", 1, "eye", 2), 0);
        assertEquals(1, first.size());
        assertTrue(first.get(0).reset, "the first send resets");
        assertEquals(vars("back", 1, "eye", 2), first.get(0).values);
        assertTrue(
            t.poll(vars("back", 1, "eye", 2), 100)
                .isEmpty(),
            "nothing changed");
        // a change, too soon after the last send (tick 0): waits, then goes alone with the last value
        assertTrue(
            t.poll(vars("back", 1, "eye", 3), 1)
                .isEmpty());
        List<Message.RoamingUpdate> later = t.poll(vars("back", 1, "eye", 4), RoamingTracker.MIN_INTERVAL);
        assertEquals(1, later.size());
        assertFalse(later.get(0).reset);
        assertEquals(vars("eye", 4), later.get(0).values, "the last value of a dragged slider");
        // a variable made missing: only a reset says so
        List<Message.RoamingUpdate> removed = t.poll(vars("eye", 4), 200);
        assertTrue(removed.get(0).reset);
        assertEquals(vars("eye", 4), removed.get(0).values);
        // a new model
        t.reset();
        assertTrue(
            t.poll(vars(), 300)
                .get(0).reset);
    }

    @Test
    void trackerFiltersAndSplits() {
        RoamingTracker t = new RoamingTracker();
        Map<String, Float> many = new LinkedHashMap<>();
        for (int i = 0; i < 70; i++) {
            many.put(String.format("v%02d", i), (float) i);
        }
        many.put("Bad", 1f);
        many.put("nan", Float.NaN);
        many.put("inf", Float.POSITIVE_INFINITY);
        List<Message.RoamingUpdate> out = t.poll(many, 0);
        assertEquals(4, out.size(), "64 variables in messages of 16");
        assertTrue(out.get(0).reset);
        assertFalse(out.get(1).reset, "only the first resets");
        int total = 0;
        for (Message.RoamingUpdate m : out) {
            assertTrue(m.values.size() <= Protocol.MAX_ROAMING_PER_MESSAGE);
            MessageCodec.encode(m); // within the codec's limits
            total += m.values.size();
        }
        assertEquals(Protocol.MAX_ROAMING_VARIABLES, total);
        assertFalse(
            t.sent()
                .containsKey("v64"),
            "the first 64 by name");
        assertFalse(
            t.sent()
                .containsKey("nan"));
    }

    @Test
    void remoteStoreKeepsPerPlayerAndVersions() {
        RemoteRoaming r = new RemoteRoaming();
        UUID p = new UUID(1, 2);
        assertEquals(0, r.version(p));
        r.apply(p, true, vars("back", 1));
        int v1 = r.version(p);
        r.apply(p, false, vars("eye", 2));
        assertEquals(vars("back", 1, "eye", 2), r.of(p));
        assertNotEquals(v1, r.version(p));
        r.apply(p, true, vars("size", 3));
        assertEquals(vars("size", 3), r.of(p));
        int v2 = r.version(p);
        r.forget(p);
        assertTrue(
            r.of(p)
                .isEmpty());
        assertNotEquals(v2, r.version(p), "a forgotten player's animator is cleared too");
        // at most 64 variables per player
        Map<String, Float> part = new LinkedHashMap<>();
        for (int m = 0; m < 5; m++) {
            part.clear();
            for (int i = 0; i < 16; i++) {
                part.put("v" + (m * 16 + i), 1f);
            }
            r.apply(p, false, part);
        }
        assertEquals(
            Protocol.MAX_ROAMING_VARIABLES,
            r.of(p)
                .size());
        // and a bounded number of players
        for (int i = 0; i < RemoteRoaming.MAX_PLAYERS + 10; i++) {
            r.apply(new UUID(9, i), false, vars("x", i));
        }
        assertEquals(RemoteRoaming.MAX_PLAYERS, r.players());
    }

    @Test
    void syncSeamTurnsArgumentsIntoFloats() {
        MolangValue[] args = new MolangValue[20];
        for (int i = 0; i < args.length; i++) {
            args[i] = MolangValue.of(i + 0.5);
        }
        args[1] = MolangValue.of("text");
        float[] f = MolangSync.toFloats(args);
        assertEquals(Protocol.MAX_SYNC_ARGS, f.length);
        assertEquals(0.5f, f[0]);
        assertEquals(0f, f[1], "a string is 0");
        List<Object> emitted = new ArrayList<>();
        MolangSync.setEmitter((source, a) -> {
            emitted.add(source);
            emitted.add(a);
        });
        try {
            Object owner = new Object();
            MolangSync.emit(owner, new MolangValue[] { MolangValue.of(3) });
            assertSame(owner, emitted.get(0));
            assertArrayEquals(new float[] { 3 }, (float[]) emitted.get(1));
            List<Object> received = new ArrayList<>();
            MolangSync.setReceiver((target, a) -> received.add(target));
            MolangSync.receive(owner, new float[] { 4 });
            assertSame(owner, received.get(0));
            assertSame(owner, MolangSync.lastTarget());
            assertArrayEquals(new float[] { 4 }, MolangSync.lastReceived());
        } finally {
            MolangSync.setEmitter(null);
            MolangSync.setReceiver(null);
        }
    }
}
