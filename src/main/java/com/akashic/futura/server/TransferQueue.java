package com.akashic.futura.server;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.akashic.futura.net.Message;
import com.akashic.futura.net.Protocol;
import com.akashic.futura.net.Sha256;

/**
 * One player's file downloads: requested hashes in order, each sent as chunks from offset 0, paced by a token bucket
 * ({@link #tick}: bytes per tick, with a burst of a few chunks). No Minecraft classes.
 */
public final class TransferQueue {

    private final int maxQueued;
    private final Deque<Sha256> queue = new ArrayDeque<>();
    private final Set<Sha256> queued = new HashSet<>();
    private Sha256 current;
    private int offset;
    private long allowance;
    private long sent;

    /** @param maxQueued files waiting at once (more requests are dropped) */
    public TransferQueue(int maxQueued) {
        this.maxQueued = maxQueued;
    }

    /**
     * Queues files. Unknown hashes are answered at once with {@link Message.FileUnavailable}.
     *
     * @return how many were queued
     */
    public int request(List<Sha256> hashes, Function<Sha256, byte[]> content, Consumer<Message> out) {
        int added = 0;
        for (Sha256 h : hashes) {
            if (content.apply(h) == null) {
                out.accept(new Message.FileUnavailable(h));
            } else if (!h.equals(current) && queued.size() < maxQueued && queued.add(h)) {
                queue.add(h);
                added++;
            }
        }
        return added;
    }

    /**
     * Adds this tick's allowance and sends chunks while it lasts.
     *
     * @param bytesPerTick the rate limit
     * @return bytes sent in this tick
     */
    public int tick(int bytesPerTick, Function<Sha256, byte[]> content, Consumer<Message> out) {
        if (current == null && queue.isEmpty()) {
            allowance = 0; // idle: no saved-up burst
            return 0;
        }
        long burst = Math.max((long) bytesPerTick, 2L * Protocol.CHUNK_DATA);
        allowance = Math.min(allowance + bytesPerTick, burst);
        int sentNow = 0;
        while (allowance > 0) {
            if (current == null) {
                current = queue.poll();
                if (current == null) {
                    break;
                }
                queued.remove(current);
                offset = 0;
            }
            byte[] data = content.apply(current);
            if (data == null) {
                out.accept(new Message.FileUnavailable(current)); // gone after a reload
                current = null;
                continue;
            }
            int length = Math.min(Protocol.CHUNK_DATA, data.length - offset);
            out.accept(
                new Message.FileChunk(current, data.length, offset, Arrays.copyOfRange(data, offset, offset + length)));
            offset += length;
            allowance -= Math.max(length, 64);
            sentNow += length;
            if (offset >= data.length) {
                current = null;
            }
        }
        sent += sentNow;
        return sentNow;
    }

    /** Forgets everything queued (the file being sent stops between chunks). */
    public void clear() {
        queue.clear();
        queued.clear();
        current = null;
        allowance = 0;
    }

    public boolean idle() {
        return current == null && queue.isEmpty();
    }

    public int queuedFiles() {
        return queue.size() + (current == null ? 0 : 1);
    }

    public long sentBytes() {
        return sent;
    }
}
