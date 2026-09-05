package games.brennan.dungeontrain.client.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The odds behind the menu splash. The property that matters is the ratio: each update line is
 * meant to come up five times as often as any one hand-written quote — not the four of them
 * together, which would be a quarter of the intended frequency.
 */
class SplashPickerTest {

    /** The pool the mod actually ships: four timeframes against the 19 quotes in splashes.txt. */
    private static final int QUOTES = 19;

    @Test
    void eachLineIsFiveTimesLikelierThanAnyOneQuote() {
        // 4 lines x 5 = 20 against 19 quotes, so a line comes up on 20/39 of rolls and each
        // individual line on 5/39 — five times a single quote's 1/39.
        int hits = 0;
        int perLine = 0;
        for (int i = 0; i < 39_000; i++) {
            int pick = SplashPicker.pick(4, QUOTES, i / 39_000.0);
            if (pick >= 0) hits++;
            if (pick == 0) perLine++;
        }
        assertEquals(20_000, hits, 50, "an update line on 20/39 of rolls");
        assertEquals(5_000, perLine, 50, "any one line on 5/39 of rolls");
    }

    @Test
    void everyLineIsReachableAndTheyShareTheOdds() {
        int[] seen = new int[4];
        for (int i = 0; i < 39_000; i++) {
            int pick = SplashPicker.pick(4, QUOTES, i / 39_000.0);
            if (pick >= 0) seen[pick]++;
        }
        for (int i = 0; i < seen.length; i++) {
            assertEquals(5_000, seen[i], 50, "line " + i + " takes an equal share");
        }
    }

    @Test
    void fewerLinesMeansProportionallyFewerHits() {
        // A quiet day drops timeframes out of the pool; the survivors keep their own 5:1 odds
        // rather than absorbing the missing lines' share.
        int hits = 0;
        for (int i = 0; i < 24_000; i++) {
            if (SplashPicker.pick(1, QUOTES, i / 24_000.0) >= 0) hits++;
        }
        assertEquals(5_000, hits, 50, "one line: 5/24 of rolls");
    }

    @Test
    void nothingToSayLeavesTheSplashToVanilla() {
        assertEquals(-1, SplashPicker.pick(0, QUOTES, 0.0));
        assertEquals(-1, SplashPicker.pick(0, QUOTES, 0.999));
        assertNull(SplashPicker.choose(List.of(), QUOTES, 0.0));
    }

    @Test
    void anEmptyQuotePoolLeavesOnlyOurs() {
        // A resource pack could empty splashes.txt. Ours then win every roll, which is correct —
        // there is nothing else to show.
        assertTrue(SplashPicker.pick(4, 0, 0.0) >= 0);
        assertTrue(SplashPicker.pick(4, 0, 0.999) >= 0);
        // A nonsense count must not invert the odds.
        assertTrue(SplashPicker.pick(4, -7, 0.5) >= 0);
    }

    @Test
    void theTopOfTheRollRangeStaysInBounds() {
        // roll is [0,1) but floating point at the boundary must not index past the last line.
        assertEquals(3, SplashPicker.pick(4, 0, 0.99999999));
        assertEquals(-1, SplashPicker.pick(4, QUOTES, 0.99999999));
    }

    @Test
    void chooseMapsTheIndexOntoTheOfferedTimeframes() {
        List<UpdateStats.Timeframe> available =
                List.of(UpdateStats.Timeframe.WEEK, UpdateStats.Timeframe.YEAR);
        assertEquals(UpdateStats.Timeframe.WEEK, SplashPicker.choose(available, 0, 0.0));
        assertEquals(UpdateStats.Timeframe.YEAR, SplashPicker.choose(available, 0, 0.9));
    }
}
