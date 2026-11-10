package com.akashic.futura.audio;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Reads the packets of the first logical stream of an Ogg file (RFC 3533): pages are checked (capture pattern, version,
 * CRC), packets are put back together across pages, and each packet knows the granule position of the page it ends
 * when it is the last one ending there. Pages of other logical streams are skipped; the reading stops at the end of
 * the first stream (a chained file plays its first link). No Minecraft classes.
 */
public final class OggPacketReader {

    /** Larger packets are refused (an OpusTags packet may carry cover art; audio packets are a few KiB). */
    public static final int MAX_PACKET = 16 << 20;

    private static final int HEADER = 27;
    private static final int CONTINUED = 0x01;
    private static final int END_OF_STREAM = 0x04;
    private static final int[] CRC = crcTable();

    /** A packet: {@link #data} holds exactly its bytes. */
    public static final class Packet {

        public final byte[] data;
        /** The granule position of the page this packet ends, if it is the last packet ending there; else -1. */
        public final long granule;
        /** True for the last packet of the stream (the last one ending on the end-of-stream page). */
        public final boolean last;

        Packet(byte[] data, long granule, boolean last) {
            this.data = data;
            this.granule = granule;
            this.last = last;
        }
    }

    private final InputStream in;
    private final byte[] header = new byte[HEADER + 255];
    private final ArrayDeque<Packet> ready = new ArrayDeque<>();
    private byte[] partial = new byte[0];
    private int partialLength;
    private boolean partialOpen;
    private long serial;
    private boolean haveSerial;
    private boolean ended;

    public OggPacketReader(InputStream in) {
        this.in = in;
    }

    /** The next packet, or null at the end of the stream. */
    public Packet next() throws IOException {
        while (ready.isEmpty()) {
            if (ended || !readPage()) {
                ended = true;
                return null;
            }
        }
        return ready.poll();
    }

    /** Reads one page of our stream into {@link #ready}; false at the end of the input. */
    private boolean readPage() throws IOException {
        while (true) {
            int first = in.read();
            if (first < 0) {
                // the input ends between pages: a stream without its end-of-stream flag
                return false;
            }
            header[0] = (byte) first;
            readFully(header, 1, HEADER - 1);
            if (header[0] != 'O' || header[1] != 'g' || header[2] != 'g' || header[3] != 'S') {
                throw new IOException("not an Ogg page (bad capture pattern)");
            }
            if (header[4] != 0) {
                throw new IOException("unsupported Ogg version " + (header[4] & 0xff));
            }
            int type = header[5] & 0xff;
            long granule = le64(header, 6);
            long pageSerial = le32(header, 14) & 0xffffffffL;
            long crc = le32(header, 22) & 0xffffffffL;
            int segments = header[26] & 0xff;
            readFully(header, HEADER, segments);
            int bodyLength = 0;
            for (int i = 0; i < segments; i++) {
                bodyLength += header[HEADER + i] & 0xff;
            }
            byte[] body = new byte[bodyLength];
            readFully(body, 0, bodyLength);
            if (crc != pageCrc(header, HEADER + segments, body)) {
                throw new IOException("corrupt Ogg page (CRC mismatch)");
            }
            if (!haveSerial) {
                serial = pageSerial;
                haveSerial = true;
            } else if (pageSerial != serial) {
                continue; // another logical stream multiplexed in the file
            }
            if ((type & CONTINUED) == 0 && partialOpen) {
                partialLength = 0; // the rest of a packet never came: drop it, as libogg does
                partialOpen = false;
            }
            boolean skipContinuation = (type & CONTINUED) != 0 && !partialOpen;
            boolean completed = false;
            int offset = 0;
            for (int i = 0; i < segments; i++) {
                int lacing = header[HEADER + i] & 0xff;
                if (!skipContinuation) {
                    append(body, offset, lacing);
                }
                offset += lacing;
                if (lacing < 255) {
                    if (skipContinuation) {
                        skipContinuation = false; // the tail of a packet whose start we never saw
                    } else {
                        completed = true;
                        ready.add(new Packet(Arrays.copyOf(partial, partialLength), -1, false));
                        partialLength = 0;
                        partialOpen = false;
                    }
                } else if (!skipContinuation) {
                    partialOpen = true;
                }
            }
            boolean eos = (type & END_OF_STREAM) != 0;
            if (completed) {
                // the page's granule position belongs to the last packet ending on it
                Packet tail = ready.pollLast();
                ready.add(new Packet(tail.data, granule, eos));
            }
            if (eos) {
                ended = true;
            }
            return true;
        }
    }

    private void append(byte[] body, int offset, int length) throws IOException {
        if (partialLength + length > MAX_PACKET) {
            throw new IOException("Ogg packet larger than " + MAX_PACKET + " bytes");
        }
        if (partialLength + length > partial.length) {
            partial = Arrays.copyOf(partial, Math.max(partialLength + length, partial.length * 2));
        }
        System.arraycopy(body, offset, partial, partialLength, length);
        partialLength += length;
    }

    private void readFully(byte[] buffer, int offset, int length) throws IOException {
        int done = 0;
        while (done < length) {
            int n = in.read(buffer, offset + done, length - done);
            if (n < 0) {
                throw new EOFException("truncated Ogg page");
            }
            done += n;
        }
    }

    private static long le64(byte[] b, int at) {
        return (le32(b, at) & 0xffffffffL) | ((long) le32(b, at + 4) << 32);
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
    }

    /** The page CRC: Ogg's CRC-32 (polynomial 0x04c11db7, no reflection) with the CRC field read as zero. */
    private static long pageCrc(byte[] header, int headerLength, byte[] body) {
        int crc = 0;
        for (int i = 0; i < headerLength; i++) {
            int b = i >= 22 && i < 26 ? 0 : header[i] & 0xff;
            crc = (crc << 8) ^ CRC[((crc >>> 24) ^ b) & 0xff];
        }
        for (byte value : body) {
            crc = (crc << 8) ^ CRC[((crc >>> 24) ^ (value & 0xff)) & 0xff];
        }
        return crc & 0xffffffffL;
    }

    private static int[] crcTable() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04c11db7 : r << 1;
            }
            table[i] = r;
        }
        return table;
    }
}
