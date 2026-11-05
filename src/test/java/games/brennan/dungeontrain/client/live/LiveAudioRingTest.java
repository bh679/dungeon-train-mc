package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveAudioRingTest {

    private static byte[] ramp(int len, int start) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) b[i] = (byte) (start + i);
        return b;
    }

    private static byte[] bytes(ByteBuffer b, int len) {
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) out[i] = b.get(i);
        return out;
    }

    @Test
    void aNewReaderStartsJustBehindTheNewestSample() {
        long head = LiveAudioRing.BYTES_PER_SECOND; // a second in
        assertEquals(head - LiveAudioRing.TARGET_LAG, LiveAudioRing.cursorFor(-1, head));
        assertEquals(0, LiveAudioRing.cursorFor(-1, 10), "not before the start of the timeline");
    }

    @Test
    void aReaderTooFarBehindIsPulledForwardButNotOneWithinTheWindow() {
        long head = 10 * LiveAudioRing.BYTES_PER_SECOND;
        long inWindow = head - LiveAudioRing.MAX_LAG;
        assertEquals(inWindow, LiveAudioRing.cursorFor(inWindow, head), "200 ms behind still plays in order");
        long late = head - LiveAudioRing.MAX_LAG - 2;
        assertEquals(head - LiveAudioRing.TARGET_LAG, LiveAudioRing.cursorFor(late, head),
            "a hitch never leaves the sound running late");
    }

    @Test
    void cursorsStayOnSampleBoundaries() {
        assertEquals(0, LiveAudioRing.cursorFor(-1, 5 + LiveAudioRing.TARGET_LAG) % 2);
        assertEquals(0, LiveAudioRing.cursorFor(1001, 2001) % 2);
        assertEquals(1000, LiveAudioRing.cursorFor(1001, 1001), "never past the newest sample");
    }

    @Test
    void readsWholeBuffersOrSilenceWithoutRunningAhead() {
        LiveAudioRing ring = new LiveAudioRing();
        byte[] data = ramp(400, 1);
        ring.write(data, 0, data.length);
        ByteBuffer out = ByteBuffer.allocate(100);
        long next = ring.read(200, out, 100); // within the lag window
        assertEquals(300, next);
        byte[] want = new byte[100];
        System.arraycopy(data, 200, want, 0, 100);
        assertArrayEquals(want, bytes(out, 100));

        // Only 100 bytes left and 200 asked: silence, never half sound and half silence (a crackle),
        // and the cursor waits instead of skipping the next sound, so the cushion grows.
        ByteBuffer again = ByteBuffer.allocate(200);
        assertEquals(300, ring.read(next, again, 200));
        assertArrayEquals(new byte[200], bytes(again, 200));
        ring.write(data, 0, 100);
        assertEquals(500, ring.read(next, again, 200), "once the ring holds a whole buffer, it plays");
    }

    @Test
    void wrapsAroundTheRing() {
        LiveAudioRing ring = new LiveAudioRing();
        byte[] fill = new byte[LiveAudioRing.CAPACITY - 50];
        ring.write(fill, 0, fill.length);
        byte[] tail = ramp(100, 7);
        ring.write(tail, 0, tail.length);
        long head = ring.head();
        ByteBuffer out = ByteBuffer.allocate(100);
        long next = ring.read(head - 100, out, 100);
        assertEquals(head, next);
        assertArrayEquals(tail, bytes(out, 100), "spans the end of the buffer and its start");
    }

    @Test
    void aWriteLargerThanTheRingKeepsTheNewest() {
        LiveAudioRing ring = new LiveAudioRing();
        byte[] big = ramp(LiveAudioRing.CAPACITY + 10, 0);
        ring.write(big, 0, big.length);
        assertEquals(big.length, ring.head());
        ByteBuffer out = ByteBuffer.allocate(10);
        ring.read(ring.head() - 10, out, 10);
        byte[] want = new byte[10];
        System.arraycopy(big, big.length - 10, want, 0, 10);
        assertArrayEquals(want, bytes(out, 10));
    }

    @Test
    void lagIsReportedInMilliseconds() {
        assertEquals(1000, LiveAudioRing.lagMs(0, LiveAudioRing.BYTES_PER_SECOND));
        assertEquals(0, LiveAudioRing.lagMs(10, 5));
    }

    @Test
    void aSpeakerBufferIsShortSoTheSoundStaysNearThePicture() {
        // Minecraft keeps four buffers queued: 4 x 60 ms plus the 500 ms starting cushion is under a second.
        long queuedMs = 4L * LiveAudioStream.CHUNK_BYTES * 1000 / LiveAudioRing.BYTES_PER_SECOND;
        long cushionMs = LiveAudioRing.TARGET_LAG * 1000 / LiveAudioRing.BYTES_PER_SECOND;
        assertTrue(queuedMs + cushionMs <= 1000, "audio starts within a second of the picture: " + (queuedMs + cushionMs));
        assertTrue(queuedMs >= 200, "the queue rides out a late client tick: " + queuedMs);
        assertTrue(LiveAudioRing.CAPACITY > LiveAudioRing.MAX_LAG + LiveAudioRing.BYTES_PER_SECOND,
            "the ring holds the furthest a reader may lag plus a catch-up burst");
        assertEquals(24_000f, LiveAudioStream.FORMAT.getSampleRate());
        assertEquals(1, LiveAudioStream.FORMAT.getChannels(), "mono, so OpenAL can place it in the world");
    }

    /**
     * ffmpeg writes in bursts: on an idle client, 4 KB reads with pauses of up to about 210 ms between
     * bursts (measured 2026-11), while the sound engine reads a buffer every 40 to 60 ms. Replayed
     * against the ring, that cadence must give whole buffers every time: no silence, no pull-forward.
     */
    @Test
    void ridesOutFfmpegsWritePausesWithoutSilenceOrSkips() {
        LiveAudioRing ring = new LiveAudioRing();
        int chunk = LiveAudioStream.CHUNK_BYTES;
        long bytesPerMs = LiveAudioRing.BYTES_PER_SECOND / 1000;
        // Writer: one second of sound a second, delivered as a 4 KB-chunk burst every 210 ms.
        long pauseMs = 210;
        int burst = (int) (pauseMs * bytesPerMs);
        long written = 0, nextWriteMs = 0;
        long cursor = -1;
        int underruns = 0, pullbacks = 0;
        byte[] src = new byte[burst];
        ByteBuffer dst = ByteBuffer.allocate(chunk);
        // One second of priming before the reader starts, as a speaker only starts once sound is there.
        for (long ms = 0; ms < 1000; ms += pauseMs) { ring.write(src, 0, burst); written += burst; nextWriteMs = ms + pauseMs; }
        // The reader drains at the rate the writer fills: one buffer's worth of time between reads.
        long stepMs = chunk / bytesPerMs;
        for (long ms = 1000; ms < 31_000; ms += stepMs) {
            while (ms >= nextWriteMs) {
                ring.write(src, 0, burst);
                written += burst;
                nextWriteMs += pauseMs;
            }
            long before = cursor;
            long head = ring.head();
            if (before >= 0 && head - before > LiveAudioRing.MAX_LAG) pullbacks++;
            cursor = ring.read(cursor, dst, chunk);
            if (before >= 0 && cursor == before) underruns++;
        }
        assertEquals(0, underruns, "every read was a whole buffer of sound");
        assertEquals(0, pullbacks, "write jitter never pulls the reader forward");
        assertTrue(written > 0);
    }

    /**
     * At the live edge the stream stalls for up to three seconds whenever a segment is late, then
     * ffmpeg writes at twice real time until it is back on schedule (measured 2026-11: stalls of 1.1
     * to 3 s every segment for a viewer who joined a young playlist at its edge, writes averaging
     * exactly real time over a minute). The first stall longer than the starting cushion costs silence
     * while the cushion grows (to a whole stall's length: the cursor stands still through a stall);
     * after that the reader must ride every cycle out whole, and the catch-up burst must never pull it
     * forward (which would shed the cushion again).
     */
    @Test
    void settlesBehindAStallingStreamAndIsNeverPulledForwardByTheCatchUp() {
        LiveAudioRing ring = new LiveAudioRing();
        int chunk = LiveAudioStream.CHUNK_BYTES;
        long bytesPerMs = LiveAudioRing.BYTES_PER_SECOND / 1000;
        long stepMs = chunk / bytesPerMs;
        long stallMs = 3000, periodMs = 8040;
        byte[] src = new byte[(int) (stepMs * bytesPerMs * 2)];
        ByteBuffer dst = ByteBuffer.allocate(chunk);
        long cursor = -1;
        int pullbacks = 0, underrunsAfterFirstCycle = 0;
        // A second of priming, then the reader starts.
        ring.write(new byte[LiveAudioRing.BYTES_PER_SECOND], 0, LiveAudioRing.BYTES_PER_SECOND);
        long owed = 0; // sound the writer is behind schedule by, bytes
        for (long ms = 0; ms < 60_000; ms += stepMs) {
            long inPeriod = ms % periodMs;
            long due = stepMs * bytesPerMs;
            if (inPeriod < stallMs) {
                owed += due; // stalled: nothing written, the schedule slips
            } else if (owed > 0) {
                ring.write(src, 0, (int) (due * 2)); // catch-up: twice real time
                owed -= due;
            } else {
                ring.write(src, 0, (int) due);
            }
            long before = cursor;
            long head = ring.head();
            if (before >= 0 && head - before > LiveAudioRing.MAX_LAG) pullbacks++;
            cursor = ring.read(cursor, dst, chunk);
            if (before >= 0 && cursor == before && ms >= periodMs) underrunsAfterFirstCycle++;
        }
        assertEquals(0, pullbacks, "the catch-up burst never pulls the reader forward");
        assertEquals(0, underrunsAfterFirstCycle, "once the cushion has grown past a stall, no more silence");
    }
}
