package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the pure math of {@link ContainerContentsRoller#taperedMaxChance(double, int, int, int)} —
 * the shared-community-book loot MAX that eases from its configured value down toward the community
 * pool's fair share {@code P/(P+V)} as a world reads more community books.
 *
 * <p>Server-free: the taper is pure arithmetic over (max, communityTotal, variantTotal, communityRead),
 * so the whole curve is exercised here rather than in the in-game Gate 2 flow.</p>
 */
final class SharedBookLootTaperTest {

    private static final double MAX = 0.75;
    private static final int P = 20;   // approved community books
    private static final int V = 144;  // hand-authored variants
    private static final double EVEN = (double) P / (double) (P + V); // ≈ 0.121951
    private static final double EPS = 1e-9;

    @Test
    @DisplayName("No community books read yet → flat configured max (today's behaviour)")
    void noneRead_holdsAtMax() {
        assertEquals(MAX, ContainerContentsRoller.taperedMaxChance(MAX, P, V, 0), EPS);
    }

    @Test
    @DisplayName("Entire community pool read → tapers to the fair share P/(P+V)")
    void allRead_reachesFairShare() {
        assertEquals(EVEN, ContainerContentsRoller.taperedMaxChance(MAX, P, V, P), EPS);
    }

    @Test
    @DisplayName("Half the pool read → linear midpoint between max and the fair share")
    void halfRead_isLinearMidpoint() {
        double expected = MAX - (MAX - EVEN) * 0.5;
        assertEquals(expected, ContainerContentsRoller.taperedMaxChance(MAX, P, V, P / 2), EPS);
    }

    @Test
    @DisplayName("Unknown / empty community pool (P<=0) → no taper, flat max")
    void unknownPool_holdsAtMax() {
        assertEquals(MAX, ContainerContentsRoller.taperedMaxChance(MAX, 0, V, 5), EPS);
        assertEquals(MAX, ContainerContentsRoller.taperedMaxChance(MAX, -3, V, 5), EPS);
    }

    @Test
    @DisplayName("communityRead beyond the pool size clamps the read-fraction at 1 (still the fair share)")
    void overRead_clampsToFairShare() {
        assertEquals(EVEN, ContainerContentsRoller.taperedMaxChance(MAX, P, V, P * 3), EPS);
    }

    @Test
    @DisplayName("Community pool large enough that the fair share exceeds max → holds at max (never tapers up)")
    void hugePool_neverTapersAboveMax() {
        int huge = 1000; // E = 1000/1144 ≈ 0.874 > 0.75
        assertEquals(MAX, ContainerContentsRoller.taperedMaxChance(MAX, huge, V, huge), EPS);
        assertEquals(MAX, ContainerContentsRoller.taperedMaxChance(MAX, huge, V, huge / 2), EPS);
    }

    @Test
    @DisplayName("Disabled feature (max<=0) → 0 regardless of pool state")
    void maxDisabled_returnsZero() {
        assertEquals(0.0, ContainerContentsRoller.taperedMaxChance(0.0, P, V, 0), EPS);
        assertEquals(0.0, ContainerContentsRoller.taperedMaxChance(-0.1, P, V, P), EPS);
    }

    @Test
    @DisplayName("Taper is monotonically non-increasing as more community books are read")
    void taperIsMonotonic() {
        double prev = ContainerContentsRoller.taperedMaxChance(MAX, P, V, 0);
        for (int read = 1; read <= P; read++) {
            double cur = ContainerContentsRoller.taperedMaxChance(MAX, P, V, read);
            assertTrue(cur <= prev + EPS,
                "effective max must not rise as community-books-read grows (read=" + read + ")");
            prev = cur;
        }
    }
}
