package games.brennan.dungeontrain.world;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down {@link StartingDimension#rollRespawnDimension(double)} — the
 * random-dimension draw applied at every respawn, whether the player clicks
 * "New World" / "Same World" (consumed by
 * {@code DeathScreenLayoutHandler.launchWorld}) or the vanilla "Respawn"
 * button (consumed by {@code RespawnDimensionEvents}). The function is a
 * pure mapping from a uniform {@code [0, 1)} value to a
 * {@link StartingDimension}, so the tests pin both the boundary conditions
 * and a large-sample distribution sanity check.
 *
 * <p>The boundaries matter more than the percentages — a future edit that
 * accidentally flips a {@code <} to {@code <=} (or shifts the End bucket
 * upward by 0.01) would silently change the gameplay distribution. The
 * boundary tests catch that; the distribution test catches an arithmetic
 * regression like swapping the bucket order.</p>
 */
final class StartingDimensionRollTest {

    @Test
    @DisplayName("r = 0.0 → END")
    void rollAtZeroIsEnd() {
        assertEquals(StartingDimension.END,
                StartingDimension.rollRespawnDimension(0.0));
    }

    @Test
    @DisplayName("r = 0.005 → END (well inside End bucket)")
    void rollInsideEndBucketIsEnd() {
        assertEquals(StartingDimension.END,
                StartingDimension.rollRespawnDimension(0.005));
    }

    @Test
    @DisplayName("r = 0.01 → NETHER (End upper bound is exclusive)")
    void rollAtEndUpperBoundIsNether() {
        assertEquals(StartingDimension.NETHER,
                StartingDimension.rollRespawnDimension(0.01));
    }

    @Test
    @DisplayName("r = 0.05 → NETHER (well inside Nether bucket)")
    void rollInsideNetherBucketIsNether() {
        assertEquals(StartingDimension.NETHER,
                StartingDimension.rollRespawnDimension(0.05));
    }

    @Test
    @DisplayName("r = 0.06 → OVERWORLD (Nether upper bound is exclusive)")
    void rollAtNetherUpperBoundIsOverworld() {
        assertEquals(StartingDimension.OVERWORLD,
                StartingDimension.rollRespawnDimension(0.06));
    }

    @Test
    @DisplayName("r = 0.5 → OVERWORLD (well inside Overworld bucket)")
    void rollInsideOverworldBucketIsOverworld() {
        assertEquals(StartingDimension.OVERWORLD,
                StartingDimension.rollRespawnDimension(0.5));
    }

    @Test
    @DisplayName("Distribution over 100k seeded draws is within tolerance")
    void distributionOver100kSeededDrawsMatchesTargets() {
        // Fixed seed so the test is deterministic. RandomSource.create(long)
        // returns a LegacyRandomSource — same algorithm vanilla uses for
        // worldgen, so the distribution it produces is the one that will
        // ship in-game.
        RandomSource rand = RandomSource.create(0xDEADBEEFL);
        int draws = 100_000;
        int endCount = 0;
        int netherCount = 0;
        int overworldCount = 0;
        for (int i = 0; i < draws; i++) {
            StartingDimension d = StartingDimension.rollRespawnDimension(rand.nextDouble());
            switch (d) {
                case END -> endCount++;
                case NETHER -> netherCount++;
                case OVERWORLD -> overworldCount++;
            }
        }

        // Tolerances: ±0.4 percentage points is roughly 4σ for n=100k at
        // p=0.01 (σ ≈ √(0.01·0.99/100000) ≈ 0.000315 → 0.0315 pp), so the
        // test is robust against natural sampling noise while still catching
        // a regression that shifts a bucket by ≥0.4 pp.
        double endPct = endCount / (double) draws;
        double netherPct = netherCount / (double) draws;
        double overworldPct = overworldCount / (double) draws;

        assertTrue(Math.abs(endPct - 0.01) < 0.004,
                "End rate " + endPct + " not within 0.004 of 0.01");
        assertTrue(Math.abs(netherPct - 0.05) < 0.008,
                "Nether rate " + netherPct + " not within 0.008 of 0.05");
        assertTrue(Math.abs(overworldPct - 0.94) < 0.008,
                "Overworld rate " + overworldPct + " not within 0.008 of 0.94");
        assertEquals(draws, endCount + netherCount + overworldCount,
                "Counts must sum to total draws — bucket gap or overlap bug");
    }
}
