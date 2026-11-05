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
 * behind, so a hitch never leaves the sound running late for good. A read the ring cannot fill whole
 * gets silence and leaves the cursor where it was: Minecraft must always get a buffer, or it ends the
 * sound, and a buffer half sound and half silence would crackle. Each such gap lets the cushion grow by
 * one buffer, so a reader settles just far enough behind to ride out ffmpeg's jitter.</p>
 *
 * <p>The cushion has to cover how ffmpeg writes (measured 2026-11, idle client): 4 KB bursts with
 * pauses of 170 to 210 ms between them every second, and at the live edge a stall of one to three
 * seconds whenever the next segment is late, after which {@code -readrate_catchup} writes at twice
 * real time until it is back on schedule. Over a minute the writes average exactly real time, so a
 * reader's lag swings by a stall's length and otherwise holds. A 40 ms cushion with a 200 ms
 * pull-forward turned the pauses alone into a stutter (every pause an underrun, every burst after one a
 * pull-forward: 42% of a minute silence and as much skipped), and a pull-forward shorter than a stall
 * undid the cushion the underruns had grown just before the next stall needed it. A reader that
 * underran through a stall settles a whole stall's length behind (its cursor stood still while the
 * catch-up wrote the stall back), so the pull-forward is well past the longest stall seen (3 s, a
 * viewer who joined a young playlist right at its edge), and the cushion is left to settle where the
 * stream's jitter puts it; the perf line reports how far behind that is.</p>
 */
public final class LiveAudioRing {

    /** 24 000 samples a second, two bytes each. */
    public static final int BYTES_PER_SECOND = 48_000;
    /** Eight seconds of sound: past {@link #MAX_LAG} plus a catch-up burst. */
    static final int CAPACITY = 8 * BYTES_PER_SECOND;
    /** How far behind the newest sample a reader starts: 500 ms, past the pauses between ffmpeg's bursts. */
    static final long TARGET_LAG = BYTES_PER_SECOND * 500L / 1000;
    /** A reader further behind than this (6 s: a real hitch, twice the longest stall seen) is pulled back to {@link #TARGET_LAG}. */
    static final long MAX_LAG = BYTES_PER_SECOND * 6000L / 1000;

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
     * Fill {@code dst} with {@code len} bytes from {@code cursor} (a negative cursor is a new reader) and
     * return the next cursor; when the ring does not hold all {@code len} yet, silence, and the cursor
     * stays. {@code dst} is filled with absolute puts from its position, which it keeps.
     */
    public synchronized long read(long cursor, ByteBuffer dst, int len) {
        long from = cursorFor(cursor, head);
        int n = head - from >= len ? len : 0;
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
