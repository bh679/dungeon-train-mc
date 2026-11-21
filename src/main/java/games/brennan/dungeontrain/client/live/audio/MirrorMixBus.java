package games.brennan.dungeontrain.client.live.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Lets more than one encoder hear the mirror. {@link AudioMirror}'s loopback device mixes only
 * when asked, and every ask moves it on, so two pumps calling it directly would each get every
 * other slice of the sound. The bus renders the mirror once into a ring of recent samples (an
 * absolute sample index, {@link #CAPACITY_FRAMES} long) and each reader keeps its own cursor into
 * it. A read past what has been rendered renders the gap first.
 *
 * <p>Readers are the Live Feed's stream and the death recorder ({@code deathrec.DeathRecorder}).
 * Both are paced by their own encoder's frame count against real time, so they stay close together.
 * A reader that falls more than the ring behind skips ahead to the oldest sample still held (a
 * stalled pump, not a normal case).</p>
 *
 * <p>Mix format as {@link MirrorDevice}: 48 kHz stereo s16, native byte order. A slice the mirror
 * could not render (closed, or silenced for an unapproved sound) is held as silence. Thread-safe.</p>
 */
public final class MirrorMixBus {

    /** Two seconds of sound: far more than two pumps polling every few milliseconds drift apart. */
    static final int CAPACITY_FRAMES = 2 * MirrorDevice.RATE;
    /** Rendered in slices of at most this many sample frames, as the pump does. */
    static final int RENDER_CHUNK_FRAMES = 480;

    /** The mirror's mixer: fills {@code frames} sample frames from {@code out}'s position, or says it could not. */
    @FunctionalInterface
    interface Renderer {
        boolean render(ByteBuffer out, int frames);
    }

    /** One reader's position in the bus's sample timeline. */
    public static final class Cursor {
        private long next;

        private Cursor(long next) {
            this.next = next;
        }

        long next() {
            return next;
        }
    }

    private static final MirrorMixBus SHARED = new MirrorMixBus(AudioMirror::render);

    private final Renderer renderer;
    private final short[] ring = new short[CAPACITY_FRAMES * MirrorDevice.CHANNELS];
    private final ByteBuffer scratch = ByteBuffer
        .allocateDirect(RENDER_CHUNK_FRAMES * MirrorDevice.BYTES_PER_FRAME)
        .order(ByteOrder.nativeOrder());
    /** Sample frames rendered since the bus was last reset. */
    private long head;

    MirrorMixBus(Renderer renderer) {
        this.renderer = renderer;
    }

    /** The bus over the live mirror. */
    public static MirrorMixBus shared() {
        return SHARED;
    }

    /** Forget every sample: the mirror was (re)opened, and what came before belongs to no reader. */
    public synchronized void reset() {
        head = 0;
    }

    /** A new reader starts at the newest sample: it hears from now on, never the past. */
    public synchronized Cursor open() {
        return new Cursor(head);
    }

    /**
     * Copies the next {@code frames} sample frames for {@code cursor} into {@code out}, from its
     * position (which is not moved), rendering the mirror first where nobody has yet.
     */
    public synchronized void read(Cursor cursor, ByteBuffer out, int frames) {
        if (frames <= 0) return;
        if (frames > CAPACITY_FRAMES) throw new IllegalArgumentException("read larger than the bus: " + frames);
        long oldest = head - CAPACITY_FRAMES;
        if (cursor.next < oldest) cursor.next = oldest;
        if (cursor.next > head) cursor.next = head; // a reset happened under this reader
        renderUpTo(cursor.next + frames);
        ShortBuffer dst = out.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer();
        int channels = MirrorDevice.CHANNELS;
        for (int f = 0; f < frames; f++) {
            int slot = (int) Math.floorMod(cursor.next + f, (long) CAPACITY_FRAMES) * channels;
            for (int c = 0; c < channels; c++) dst.put(f * channels + c, ring[slot + c]);
        }
        cursor.next += frames;
    }

    private void renderUpTo(long target) {
        int channels = MirrorDevice.CHANNELS;
        while (head < target) {
            int n = (int) Math.min(target - head, RENDER_CHUNK_FRAMES);
            scratch.clear();
            boolean mixed = renderer.render(scratch, n);
            ShortBuffer src = scratch.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer();
            for (int f = 0; f < n; f++) {
                int slot = (int) Math.floorMod(head + f, (long) CAPACITY_FRAMES) * channels;
                for (int c = 0; c < channels; c++) ring[slot + c] = mixed ? src.get(f * channels + c) : 0;
            }
            head += n;
        }
    }
}
