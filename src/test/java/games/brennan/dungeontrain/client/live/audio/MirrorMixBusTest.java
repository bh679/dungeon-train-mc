package games.brennan.dungeontrain.client.live.audio;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MirrorMixBusTest {

    /** A mirror whose every sample frame is (n, -n) for the n-th frame it mixes. */
    private static final class Counting implements MirrorMixBus.Renderer {
        long frames;
        boolean mixed = true;

        @Override
        public boolean render(ByteBuffer out, int n) {
            ShortBuffer s = out.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer();
            for (int f = 0; f < n; f++) {
                short v = (short) (frames + f);
                s.put(2 * f, v);
                s.put(2 * f + 1, (short) -v);
            }
            frames += n;
            return mixed;
        }
    }

    private static short[] read(MirrorMixBus bus, MirrorMixBus.Cursor c, int frames) {
        ByteBuffer out = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder());
        bus.read(c, out, frames);
        short[] left = new short[frames];
        ShortBuffer s = out.asShortBuffer();
        for (int f = 0; f < frames; f++) left[f] = s.get(2 * f);
        return left;
    }

    @Test
    void twoReadersHearTheSameSamplesAndTheMirrorMixesOnce() {
        Counting mirror = new Counting();
        MirrorMixBus bus = new MirrorMixBus(mirror);
        MirrorMixBus.Cursor a = bus.open();
        MirrorMixBus.Cursor b = bus.open();
        short[] fromA = read(bus, a, 1000);
        short[] fromB = read(bus, b, 1000);
        assertEquals(1000, mirror.frames, "the second reader is served from the ring");
        for (int i = 0; i < 1000; i++) assertEquals(fromA[i], fromB[i]);
        assertEquals(999, fromB[999]);
    }

    @Test
    void aLateReaderStartsAtTheNewestSample() {
        Counting mirror = new Counting();
        MirrorMixBus bus = new MirrorMixBus(mirror);
        read(bus, bus.open(), 500);
        short[] late = read(bus, bus.open(), 10);
        assertEquals(500, late[0]);
    }

    @Test
    void aReaderFarBehindSkipsToTheOldestHeldSample() {
        Counting mirror = new Counting();
        MirrorMixBus bus = new MirrorMixBus(mirror);
        MirrorMixBus.Cursor slow = bus.open();
        MirrorMixBus.Cursor fast = bus.open();
        for (int i = 0; i < 5; i++) read(bus, fast, MirrorMixBus.CAPACITY_FRAMES / 2);
        // fast is 2.5 rings ahead; slow can only get the last ring's worth.
        long head = 5L * (MirrorMixBus.CAPACITY_FRAMES / 2);
        short[] got = read(bus, slow, 1);
        assertEquals((short) (head - MirrorMixBus.CAPACITY_FRAMES), got[0]);
    }

    @Test
    void anUnrenderedSliceIsSilence() {
        Counting mirror = new Counting();
        mirror.mixed = false;
        MirrorMixBus bus = new MirrorMixBus(mirror);
        short[] got = read(bus, bus.open(), 600);
        for (short v : got) assertEquals(0, v);
    }
}
