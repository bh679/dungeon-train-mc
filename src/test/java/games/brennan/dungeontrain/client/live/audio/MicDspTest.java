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
    void theBalanceTurnsTheGameDownInTheMixOnly() {
        ShortBuffer game = ShortBuffer.wrap(new short[] {1000, -1000});
        ShortBuffer out = ShortBuffer.allocate(4);
        MicDsp.mixInterleave4(game, new short[] {100}, 1, out, 0.5f);
        assertArrayEquals(new short[] {600, -400, 1000, -1000}, out.array());
    }

    @Test
    void theBalanceRunsFromGameOnlyToMicOnlyWithBothFullAtHalf() {
        assertEquals(1f, MicDsp.gameGain(0));
        assertEquals(0f, MicDsp.micGain(0));
        assertEquals(1f, MicDsp.gameGain(50));
        assertEquals(1f, MicDsp.micGain(50));
        assertEquals(0.5f, MicDsp.gameGain(75), 1e-6);
        assertEquals(1f, MicDsp.micGain(75));
        assertEquals(1f, MicDsp.gameGain(25));
        assertEquals(0.5f, MicDsp.micGain(25), 1e-6);
        assertEquals(0f, MicDsp.gameGain(100));
        assertEquals(1f, MicDsp.micGain(100));
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

    @Test
    void theMeterReadsSilenceAsEmptyAndALoudVoiceAsFull() {
        assertEquals(0, MicDsp.LevelMeter.normalised(0), 1e-9);
        assertEquals(0, MicDsp.LevelMeter.normalised(50), 1e-9, "under the floor");
        assertEquals(1, MicDsp.LevelMeter.normalised(20_000), 1e-9, "over the ceiling");
        double mid = MicDsp.LevelMeter.normalised(1000);
        assertTrue(mid > 0.2 && mid < 0.8, "ordinary speech sits mid-scale: " + mid);
    }

    @Test
    void theMeterRisesFastAndFallsSlowly() {
        MicDsp.LevelMeter m = new MicDsp.LevelMeter(48_000);
        float up = 0;
        for (int i = 0; i < 4; i++) up = m.update(filled(480, 20_000), 480);
        assertTrue(up > 0.9f, "40 ms of shouting fills it: " + up);
        float down = m.update(filled(480, 0), 480);
        assertTrue(down > 0.9f, "10 ms of quiet barely drops it: " + down);
        for (int i = 0; i < 40; i++) down = m.update(filled(480, 0), 480);
        assertEquals(0f, down, "empty 400 ms after the voice stops");
        m.update(filled(480, 20_000), 480);
        m.reset();
        assertEquals(0f, m.update(new short[0], 0));
    }

    private static short[] filled(int n, int v) {
        short[] s = new short[n];
        java.util.Arrays.fill(s, (short) v);
        return s;
    }
}
