package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A placed TV is heard 4.5 blocks away at 1x1, 9 at 3x3, further for bigger grids, and fades straight to the range. */
class LiveSpeakerRangeTest {

    @Test
    void aLoneTvReachesFourAndAHalfBlocks() {
        assertEquals(4.5, LiveSpeakerRange.range(1), 1e-9);
    }

    @Test
    void aThreeByThreeReachesNineBlocks() {
        assertEquals(9, LiveSpeakerRange.range(3), 1e-9);
    }

    @Test
    void sizesLieOnAStraightLineWithNoCeiling() {
        assertEquals(6.75, LiveSpeakerRange.range(2), 1e-9);
        assertEquals(11.25, LiveSpeakerRange.range(4), 1e-9);
        assertEquals(20.25, LiveSpeakerRange.range(8), 1e-9, "keeps growing past 3x3");
    }

    @Test
    void sizesBelowOneCountAsOne() {
        assertEquals(4.5, LiveSpeakerRange.range(0), 1e-9);
        assertEquals(4.5, LiveSpeakerRange.range(-3), 1e-9);
    }

    @Test
    void gainIsFullAtTheScreenAndNothingAtTheRange() {
        assertEquals(1, LiveSpeakerRange.gain(0, 9), 1e-6);
        assertEquals(1, LiveSpeakerRange.gain(-1, 9), 1e-6);
        assertEquals(0, LiveSpeakerRange.gain(9, 9), 1e-6);
        assertEquals(0, LiveSpeakerRange.gain(30, 9), 1e-6);
        assertEquals(0.5f, LiveSpeakerRange.gain(9, 18), 1e-6);
    }

    @Test
    void gainOnlyFallsWithDistance() {
        float last = 1;
        for (double d = 0; d <= 18; d += 0.25) {
            float g = LiveSpeakerRange.gain(d, 18);
            assertTrue(g <= last, "gain rose at " + d);
            last = g;
        }
    }

    @Test
    void aZeroRangeIsSilent() {
        assertEquals(0, LiveSpeakerRange.gain(0, 0), 1e-6);
    }

    @Test
    void tvsAlongReadsVistasPixelSize() {
        assertEquals(1, LiveSpeakerRange.tvsAlong(12), "one TV: 16 - 4 bezel");
        assertEquals(3, LiveSpeakerRange.tvsAlong(44));
        assertEquals(8, LiveSpeakerRange.tvsAlong(124));
        assertEquals(1, LiveSpeakerRange.tvsAlong(0));
    }
}
