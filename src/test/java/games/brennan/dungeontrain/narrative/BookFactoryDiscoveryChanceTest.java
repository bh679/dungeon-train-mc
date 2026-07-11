package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks down the pure narrative-lectern discovery chance math
 * ({@link BookFactory#discoveryChance}): {@code fairShare × warm-up-ramp}, LETTER-granular. Player
 * stories stay at 0% until a threshold fraction of the mod letters is read, then ramp to and settle at
 * the pool-size fair share {@code P/(P+V)}. Server-free, so it runs as a plain unit test.
 */
final class BookFactoryDiscoveryChanceTest {

    private static final double EPS = 1e-9;

    @Test
    @DisplayName("empty / unknown pool (P<=0) → chance 0")
    void emptyPool() {
        assertEquals(0.0, BookFactory.discoveryChance(0, 10, 10, 0.3), EPS);
        assertEquals(0.0, BookFactory.discoveryChance(-1, 10, 10, 0.3), EPS);
    }

    @Test
    @DisplayName("below the ramp threshold → chance 0 (incl. exactly at the threshold)")
    void belowThreshold() {
        // V=10, T=0.3 → 0% until more than 3 letters read
        assertEquals(0.0, BookFactory.discoveryChance(90, 10, 0, 0.3), EPS);
        assertEquals(0.0, BookFactory.discoveryChance(90, 10, 2, 0.3), EPS);
        assertEquals(0.0, BookFactory.discoveryChance(90, 10, 3, 0.3), EPS); // exactly at threshold
    }

    @Test
    @DisplayName("fully warmed up (all mod letters read) → settles at the fair share P/(P+V)")
    void settlesAtFairShare() {
        assertEquals(0.9, BookFactory.discoveryChance(90, 10, 10, 0.3), EPS); // 90/100 → built-in 1 in 10
        assertEquals(0.5, BookFactory.discoveryChance(10, 10, 10, 0.3), EPS); // 10/20  → built-in 1 in 2
    }

    @Test
    @DisplayName("mid-ramp = fairShare × clamp((read/V − T)/(1 − T))")
    void midRamp() {
        // V=10, T=0.3, read=5 → ramp = (0.5-0.3)/0.7; fairShare = 0.9
        assertEquals(0.9 * (0.2 / 0.7), BookFactory.discoveryChance(90, 10, 5, 0.3), EPS);
        // read=8 → ramp = (0.8-0.3)/0.7
        assertEquals(0.9 * (0.5 / 0.7), BookFactory.discoveryChance(90, 10, 8, 0.3), EPS);
    }

    @Test
    @DisplayName("threshold 0 ramps linearly from the first letter; threshold 1 only at full read")
    void thresholdEdges() {
        assertEquals(0.9 * 0.2, BookFactory.discoveryChance(90, 10, 2, 0.0), EPS); // linear from zero
        assertEquals(0.0, BookFactory.discoveryChance(90, 10, 9, 1.0), EPS);       // T=1 → 0 until full
        assertEquals(0.9, BookFactory.discoveryChance(90, 10, 10, 1.0), EPS);      // full read → fair share
    }

    @Test
    @DisplayName("V=0 (no mod stories) → ramp is full, chance is P/(P+0) = 1")
    void noModStories() {
        assertEquals(1.0, BookFactory.discoveryChance(5, 0, 0, 0.3), EPS);
    }
}
