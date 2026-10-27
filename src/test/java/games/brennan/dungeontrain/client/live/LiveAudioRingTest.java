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
        // Minecraft keeps four buffers queued: 4 x 40 ms plus the 40 ms cushion is 200 ms.
        long queuedMs = 4L * LiveAudioStream.CHUNK_BYTES * 1000 / LiveAudioRing.BYTES_PER_SECOND;
        long cushionMs = LiveAudioRing.TARGET_LAG * 1000 / LiveAudioRing.BYTES_PER_SECOND;
        assertTrue(queuedMs + cushionMs <= 200, "audio within ~200 ms of the picture: " + (queuedMs + cushionMs));
        assertEquals(24_000f, LiveAudioStream.FORMAT.getSampleRate());
        assertEquals(1, LiveAudioStream.FORMAT.getChannels(), "mono, so OpenAL can place it in the world");
    }
}
