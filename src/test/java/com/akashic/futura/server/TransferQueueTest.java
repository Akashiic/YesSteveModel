package com.akashic.futura.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.akashic.futura.net.Message;
import com.akashic.futura.net.MessageCodec;
import com.akashic.futura.net.Protocol;
import com.akashic.futura.net.Sha256;

class TransferQueueTest {

    private final Map<Sha256, byte[]> content = new HashMap<>();
    private final List<Message> out = new ArrayList<>();

    private Sha256 add(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        Sha256 h = Sha256.of(b);
        content.put(h, b);
        return h;
    }

    @Test
    void sendsFilesInOrderWithinTheRate() {
        Sha256 a = add(100_000, 1);
        Sha256 b = add(0, 2);
        TransferQueue q = new TransferQueue(100);
        Sha256 unknown = Sha256.of(new byte[] { 42 });
        assertEquals(2, q.request(Arrays.asList(a, a, b, unknown), content::get, out::add));
        assertTrue(out.get(0) instanceof Message.FileUnavailable);
        out.clear();

        int perTick = 10_000;
        int ticks = 0;
        long sent = 0;
        while (!q.idle() && ticks < 1000) {
            sent += q.tick(perTick, content::get, out::add);
            ticks++;
            // never more than the allowance plus one chunk ahead
            assertTrue(sent <= (long) ticks * perTick + Protocol.CHUNK_DATA, "tick " + ticks);
        }
        assertEquals(100_000, sent);
        assertTrue(ticks >= 100_000 / perTick - 1, "ticks " + ticks);

        // the chunks rebuild the file, each payload under 30 KiB, and the empty file is one empty chunk
        ByteArrayOutputStream rebuilt = new ByteArrayOutputStream();
        int empty = 0;
        for (Message m : out) {
            Message.FileChunk c = (Message.FileChunk) m;
            assertTrue(MessageCodec.encode(c).length <= Protocol.TARGET_PAYLOAD);
            if (c.hash.equals(a)) {
                assertEquals(rebuilt.size(), c.offset);
                rebuilt.write(c.data, 0, c.data.length);
            } else {
                assertEquals(0, c.total);
                empty++;
            }
        }
        assertArrayEquals(content.get(a), rebuilt.toByteArray());
        assertEquals(1, empty);
        assertEquals(100_000, q.sentBytes());
    }

    @Test
    void capsTheQueueAndAnswersFilesGoneAfterAReload() {
        TransferQueue q = new TransferQueue(2);
        Sha256 a = add(10, 1);
        Sha256 b = add(10, 2);
        Sha256 c = add(10, 3);
        assertEquals(2, q.request(Arrays.asList(a, b, c), content::get, out::add));
        content.remove(b);
        q.tick(1_000_000, content::get, out::add);
        assertTrue(out.get(0) instanceof Message.FileChunk);
        assertTrue(out.get(1) instanceof Message.FileUnavailable);
        assertEquals(2, out.size());
        q.request(Arrays.asList(a), content::get, out::add);
        q.clear();
        assertTrue(q.idle());
    }
}
