package games.brennan.dungeontrain.client.live.deathrec;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * The death recorder's memory: the encoder's MPEG-TS output, held in arrival order and trimmed to
 * the last {@code windowMs} while the player is alive. Nothing reaches disk until a death.
 *
 * <p>The stream is kept as whole 188-byte transport packets: a read that ends mid-packet keeps the
 * tail back until the rest arrives, so trimming (whole chunks off the front) always leaves a stream
 * that starts on a packet. A player can decode such a cut; ffmpeg resends the stream tables often
 * and the encoder puts a keyframe every second, so at most a second at the front is lost.</p>
 *
 * <p>{@link #hold} is the death: trimming stops, so the pre-roll stays and everything after it is
 * kept, up to {@code holdCapMs} (a death screen left open for an hour must not fill memory).
 * {@link #takeAndReset} hands the whole recording over and starts afresh for the next life.</p>
 *
 * <p>Thread-safe: the encoder's reader thread appends, the render thread holds and takes.</p>
 */
final class DeathRecRing {

    static final int PACKET = 188;

    private record Chunk(byte[] bytes, long atMs) {}

    private final long windowMs;
    private final long holdCapMs;
    private final Deque<Chunk> chunks = new ArrayDeque<>();
    /** Bytes of a packet still waiting for its end. */
    private final byte[] partial = new byte[PACKET];
    private int partialLen;
    private long size;
    private long holdStartMs = -1;

    DeathRecRing(long windowMs, long holdCapMs) {
        this.windowMs = windowMs;
        this.holdCapMs = holdCapMs;
    }

    /** {@code len} bytes of encoder output that arrived at {@code nowMs}. */
    synchronized void append(byte[] data, int len, long nowMs) {
        if (len <= 0) return;
        int off = 0;
        if (partialLen > 0) {
            int need = Math.min(PACKET - partialLen, len);
            System.arraycopy(data, 0, partial, partialLen, need);
            partialLen += need;
            off = need;
            if (partialLen < PACKET) return;
            push(partial.clone(), nowMs);
            partialLen = 0;
        }
        int whole = (len - off) / PACKET * PACKET;
        if (whole > 0) push(Arrays.copyOfRange(data, off, off + whole), nowMs);
        int rest = len - off - whole;
        if (rest > 0) {
            System.arraycopy(data, off + whole, partial, 0, rest);
            partialLen = rest;
        }
        trim(nowMs);
    }

    /** Whole packets only. Past the cap they are dropped (after alignment, so later packets stay whole). */
    private void push(byte[] bytes, long nowMs) {
        if (holdStartMs >= 0 && nowMs - holdStartMs > holdCapMs) return; // capped: the recording ends here
        chunks.addLast(new Chunk(bytes, nowMs));
        size += bytes.length;
    }

    private void trim(long nowMs) {
        if (holdStartMs >= 0) return;
        while (chunks.size() > 1 && chunks.peekFirst().atMs() < nowMs - windowMs) {
            size -= chunks.removeFirst().bytes().length;
        }
    }

    /** The player died: keep what is held and everything that follows. Repeat calls keep the first start. */
    synchronized void hold(long nowMs) {
        if (holdStartMs < 0) holdStartMs = nowMs;
    }

    synchronized boolean holding() {
        return holdStartMs >= 0;
    }

    /** Bytes held now, whole packets only. */
    synchronized long size() {
        return size;
    }

    /** Milliseconds from the oldest held chunk to {@code nowMs}; 0 when empty. */
    synchronized long spanMs(long nowMs) {
        Chunk first = chunks.peekFirst();
        return first == null ? 0 : nowMs - first.atMs();
    }

    /** Everything held, as one stream; the ring is then empty and recording into memory again. */
    synchronized byte[] takeAndReset() {
        byte[] out = new byte[(int) Math.min(size, Integer.MAX_VALUE - 8)];
        int pos = 0;
        for (Chunk c : chunks) {
            int n = Math.min(c.bytes().length, out.length - pos);
            System.arraycopy(c.bytes(), 0, out, pos, n);
            pos += n;
        }
        chunks.clear();
        size = 0;
        holdStartMs = -1;
        // The packet in flight belongs to the stream after this cut; keep it so the next one stays aligned.
        return out;
    }
}
