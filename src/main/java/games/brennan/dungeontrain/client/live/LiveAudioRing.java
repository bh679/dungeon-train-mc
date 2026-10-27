package games.brennan.dungeontrain.client.live;

import java.nio.ByteBuffer;

/**
 * One live feed's sound, as it leaves ffmpeg: 24 kHz mono signed 16-bit little-endian PCM (Vista's
 * {@code PcmAudioSource.FORMAT}; mono is also what OpenAL needs to place a sound in the world), kept
 * for the last {@link #CAPACITY} bytes on an absolute timeline. {@link LiveHlsSession}'s audio thread
 * writes it; every speaker playing the feed ({@link LiveAudioStream}) reads it with a cursor of its own.
 *
 * <p>The audio leaves the same ffmpeg run as the picture, so it is in step with it as it arrives; what
 * this keeps in step is playback. A reader plays {@link #TARGET_LAG} behind the newest sample (a cushion
 * for the bursts ffmpeg writes in) and is pulled forward whenever it falls more than {@link #MAX_LAG}
 * behind, so a hitch never leaves the sound running late for good. A reader that runs out of samples
 * gets silence and stays where it is: Minecraft must always get a buffer, or it ends the sound.</p>
 */
public final class LiveAudioRing {

    /** 24 000 samples a second, two bytes each. */
    public static final int BYTES_PER_SECOND = 48_000;
    /** Three seconds of sound: far more than any reader may lag. */
    static final int CAPACITY = 3 * BYTES_PER_SECOND;
    /** How far behind the newest sample a reader plays: 60 ms. */
    static final long TARGET_LAG = BYTES_PER_SECOND * 60L / 1000;
    /** A reader further behind than this (200 ms) is pulled back to {@link #TARGET_LAG}. */
    static final long MAX_LAG = BYTES_PER_SECOND * 200L / 1000;

    private final byte[] buf = new byte[CAPACITY];
    /** Bytes ever written: the timeline position one past the newest sample. */
    private long head;

    /** Audio thread: append {@code len} bytes. */
    public synchronized void write(byte[] src, int off, int len) {
        if (len >= CAPACITY) {
            off += len - CAPACITY;
            head += len - CAPACITY;
            len = CAPACITY;
        }
        int at = (int) (head % CAPACITY);
        int first = Math.min(len, CAPACITY - at);
        System.arraycopy(src, off, buf, at, first);
        if (first < len) System.arraycopy(src, off + first, buf, 0, len - first);
        head += len;
    }

    /** The timeline position one past the newest sample. */
    public synchronized long head() {
        return head;
    }

    /**
     * Fill {@code dst} from {@code cursor} (a negative cursor is a new reader) and return the next one.
     * Bytes the ring does not have yet are left silent, and the cursor does not move past them.
     * {@code dst} is filled with absolute puts from its position, which it keeps.
     */
    public synchronized long read(long cursor, ByteBuffer dst, int len) {
        long from = cursorFor(cursor, head);
        int n = (int) Math.min(len, head - from);
        int base = dst.position();
        for (int i = 0; i < len; i++) dst.put(base + i, (byte) 0);
        int at = (int) (from % CAPACITY);
        int first = Math.min(n, CAPACITY - at);
        dst.put(base, buf, at, first);
        if (first < n) dst.put(base + first, buf, 0, n - first);
        return from + n;
    }

    /**
     * Where a reader at {@code cursor} plays from, the newest sample being {@code head - 1}: a new reader
     * ({@code cursor < 0}) or one more than {@link #MAX_LAG} behind starts {@link #TARGET_LAG} behind;
     * anything else stays put. Always on a sample boundary. Pure, for the tests.
     */
    static long cursorFor(long cursor, long head) {
        if (cursor < 0 || head - cursor > MAX_LAG) return align(Math.max(0, head - TARGET_LAG));
        return align(Math.min(cursor, head));
    }

    /** How far behind the newest sample a reader at {@code cursor} is, in milliseconds (for the perf line). */
    static long lagMs(long cursor, long head) {
        return Math.max(0, head - cursor) * 1000 / BYTES_PER_SECOND;
    }

    private static long align(long pos) {
        return pos & ~1L;
    }
}
