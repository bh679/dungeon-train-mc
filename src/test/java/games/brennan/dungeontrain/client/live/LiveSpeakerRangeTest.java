package games.brennan.dungeontrain.client.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A placed TV is heard 9 blocks away when it is one TV, 18 at the largest grid, and fades straight to the range. */
class LiveSpeakerRangeTest {

    @Test
    void aLoneTvReachesNineBlocks() {
        assertEquals(9, LiveSpeakerRange.range(1, 8), 1e-9);
    }

    @Test
    void theLargestGridReachesEighteenBlocks() {
        assertEquals(18, LiveSpeakerRange.range(8, 8), 1e-9);
        assertEquals(18, LiveSpeakerRange.range(24, 24), 1e-9);
    }

    @Test
    void sizesBetweenLieOnAStraightLine() {
        assertEquals(13.5, LiveSpeakerRange.range(5, 9), 1e-9, "midpoint of 1..9");
        assertEquals(9 + 9.0 / 7, LiveSpeakerRange.range(2, 8), 1e-9);
    }

    @Test
    void sizesOutsideTheGridClamp() {
        assertEquals(9, LiveSpeakerRange.range(0, 8), 1e-9);
        assertEquals(9, LiveSpeakerRange.range(-3, 8), 1e-9);
        assertEquals(18, LiveSpeakerRange.range(12, 8), 1e-9, "wider than the config allows: still the largest");
    }

    @Test
    void aOneTvMaximumGivesEveryTvTheLargestRange() {
        assertEquals(18, LiveSpeakerRange.range(1, 1), 1e-9);
        assertEquals(18, LiveSpeakerRange.range(1, 0), 1e-9);
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
