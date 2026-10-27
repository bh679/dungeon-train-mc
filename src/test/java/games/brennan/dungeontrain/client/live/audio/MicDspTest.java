package games.brennan.dungeontrain.client.live.audio;

import org.junit.jupiter.api.Test;

import java.nio.ShortBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicDspTest {

    @Test
    void theMixIsTheGameWithTheVoiceOnBothSidesAndTheGameAloneAfter() {
        ShortBuffer game = ShortBuffer.wrap(new short[] {100, -200, 300, -400});
        short[] mic = {10, 20};
        ShortBuffer out = ShortBuffer.allocate(8);
        MicDsp.mixInterleave4(game, mic, 2, out);
        assertArrayEquals(new short[] {110, -190, 100, -200, 320, -380, 300, -400}, out.array());
    }

    @Test
    void aLoudVoiceOverLoudGameClipsInsteadOfWrapping() {
        ShortBuffer game = ShortBuffer.wrap(new short[] {30_000, -30_000});
        ShortBuffer out = ShortBuffer.allocate(4);
        MicDsp.mixInterleave4(game, new short[] {10_000}, 1, out);
        assertEquals(Short.MAX_VALUE, out.get(0));
        assertEquals(-20_000, out.get(1));
        assertEquals(30_000, out.get(2), "the game-only track is untouched");
        MicDsp.mixInterleave4(game, new short[] {-10_000}, 1, out);
        assertEquals(Short.MIN_VALUE, out.get(1));
    }

    @Test
    void gainScalesAndClips() {
        short[] s = {1000, -1000, 20_000};
        MicDsp.applyGain(s, 3, 2f);
        assertArrayEquals(new short[] {2000, -2000, Short.MAX_VALUE}, s);
        short[] half = {1000, -1000};
        MicDsp.applyGain(half, 2, 0.5f);
        assertArrayEquals(new short[] {500, -500}, half);
        short[] mute = {1000};
        MicDsp.applyGain(mute, 1, 0f);
        assertEquals(0, mute[0]);
    }

    @Test
    void fortyEightKilohertzPassesStraightThrough() {
        MicDsp.Resampler r = new MicDsp.Resampler(48_000, 48_000);
        short[] in = {1, 2, 3, 4};
        short[] out = new short[r.maxOut(4)];
        assertEquals(4, r.process(in, 4, out));
        assertEquals(3, out[2]);
    }

    @Test
    void fortyFourOneComesOutAtFortyEightAcrossBlocks() {
        MicDsp.Resampler r = new MicDsp.Resampler(44_100, 48_000);
        int total = 0;
        short[] block = new short[441];
        short[] out = new short[r.maxOut(block.length)];
        int last = Integer.MIN_VALUE;
        boolean rising = true;
        for (int b = 0; b < 100; b++) {
            // A slow ramp through every block: resampled, it must still only rise.
            for (int i = 0; i < block.length; i++) block[i] = (short) (b * block.length + i - 22_050);
            int n = r.process(block, block.length, out);
            for (int i = 0; i < n; i++) {
                if (out[i] < last) rising = false;
                last = out[i];
            }
            total += n;
        }
        // One second in at 44.1 kHz is one second out at 48 kHz, give or take the carried sample.
        assertTrue(Math.abs(total - 48_000) <= 2, "got " + total);
        assertTrue(rising, "block joins must not jump back");
    }

    @Test
    void theGateClosesOnQuietAfterItsHoldAndOpensOnSpeech() {
        MicDsp.NoiseGate gate = new MicDsp.NoiseGate(400, 960);
        short[] loud = filled(480, 3000);
        short[] quiet = filled(480, 50);
        assertTrue(gate.process(loud, 480));
        assertEquals(3000, loud[0]);
        assertTrue(gate.process(quiet, 480), "held open: 10 ms after speech");
        quiet = filled(480, 50);
        assertTrue(gate.process(quiet, 480), "held open: 20 ms after speech");
        quiet = filled(480, 50);
        assertFalse(gate.process(quiet, 480), "past the hold: shut");
        assertEquals(0, quiet[0]);
        loud = filled(480, 3000);
        assertTrue(gate.process(loud, 480), "speech opens it again at once");
    }

    @Test
    void aFreshGateStartsShutOnSilence() {
        short[] quiet = filled(480, 10);
        assertFalse(new MicDsp.NoiseGate(400, 960).process(quiet, 480));
    }

    @Test
    void rmsOfAConstant() {
        assertEquals(500, MicDsp.rms(filled(10, -500), 10), 1e-9);
        assertEquals(0, MicDsp.rms(new short[0], 0), 1e-9);
    }

    private static short[] filled(int n, int v) {
        short[] s = new short[n];
        java.util.Arrays.fill(s, (short) v);
        return s;
    }
}
