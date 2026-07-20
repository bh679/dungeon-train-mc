package games.brennan.dungeontrain.difficulty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the tunnel-chest loot difficulty frame.
 *
 * <p>{@code TunnelPlacer} keys its deterministic rolls on the tile's raw world-X, but loot
 * stat scaling is defined in <em>carriage</em> units. Before the fix the raw world-X was
 * passed straight into the difficulty tier, inflating tunnel-chest stats by roughly the
 * carriage length. {@code TunnelPlacer.diffIndexForTile} now applies the same
 * {@code floorDiv(worldX, carriageLength)} mapping that
 * {@link DifficultyProgression#levelAtWorldX} uses.
 *
 * <p>Exercises the pure tier helper ({@code rawTier}) rather than the config-reading
 * {@code positionTier} wrapper, matching the {@code DifficultyProgressionTest} pattern —
 * no NeoForge bootstrap required. {@code diffIndexForTile} itself needs a live
 * {@code ServerLevel}, so these tests assert the mapping it implements. Lives in the
 * {@code difficulty} package (not {@code tunnel}) because {@code rawTier} is
 * package-private — testing through it beats widening production visibility.
 */
final class TunnelLootDiffIndexTest {

    /** Default carriagesPerTier (matches {@code DungeonTrainConfig.DEFAULT_CARRIAGES_PER_TIER}). */
    private static final int CPT = 20;

    /** Default carriage length. */
    private static final int CARRIAGE_LENGTH = 10;

    /** The mapping {@code TunnelPlacer.diffIndexForTile} applies. */
    private static int diffIndex(int worldX, int carriageLength) {
        return Math.floorDiv(worldX, Math.max(1, carriageLength));
    }

    @Test
    @DisplayName("a tunnel tile's diffIndex matches the carriage pIdx covering the same world-X")
    void diffIndex_matchesCarriageFrame() {
        // World-X 1000 with 10-block carriages is carriage 100 — not carriage 1000.
        assertEquals(100, diffIndex(1000, CARRIAGE_LENGTH));

        // ...and so it must roll the same tier a carriage at pIdx 100 rolls.
        assertEquals(
            DifficultyProgression.rawTier(100, CPT),
            DifficultyProgression.rawTier(diffIndex(1000, CARRIAGE_LENGTH), CPT));
    }

    @Test
    @DisplayName("regression: raw world-X inflated the tier by ~carriageLength")
    void rawWorldX_inflatesTier() {
        int worldX = 1000;
        int buggy = DifficultyProgression.rawTier(worldX, CPT);
        int fixed = DifficultyProgression.rawTier(diffIndex(worldX, CARRIAGE_LENGTH), CPT);

        assertNotEquals(buggy, fixed, "the fix must actually change the tier");
        assertEquals(50, buggy);  // worldX 1000 read as carriage 1000
        assertEquals(5, fixed);   // correct: carriage 100
        assertTrue(buggy > fixed);
    }

    @Test
    @DisplayName("tiles near spawn now roll tier 0 instead of an already-elevated tier")
    void nearSpawn_rollsTierZero() {
        // ~20 carriages out: still tier 1 in the correct frame...
        assertEquals(1, DifficultyProgression.rawTier(diffIndex(200, CARRIAGE_LENGTH), CPT));
        // ...but the raw world-X read it as tier 10.
        assertEquals(10, DifficultyProgression.rawTier(200, CPT));

        // The first carriage-length of track is tier 0 either way.
        assertEquals(0, DifficultyProgression.rawTier(diffIndex(0, CARRIAGE_LENGTH), CPT));
    }

    @Test
    @DisplayName("diffIndex floors toward negative infinity so backward track scales symmetrically")
    void diffIndex_floorsNegative() {
        // floorDiv, not integer division — -1 must not round toward 0 into carriage 0.
        assertEquals(-1, diffIndex(-1, CARRIAGE_LENGTH));
        assertEquals(-1, diffIndex(-10, CARRIAGE_LENGTH));
        assertEquals(-2, diffIndex(-11, CARRIAGE_LENGTH));

        // rawTier takes abs, so a backward tile scales like its forward twin.
        assertEquals(
            DifficultyProgression.rawTier(diffIndex(1000, CARRIAGE_LENGTH), CPT),
            DifficultyProgression.rawTier(diffIndex(-1000, CARRIAGE_LENGTH), CPT));
    }

    @Test
    @DisplayName("a zero/negative carriage length can't divide by zero")
    void diffIndex_guardsDivisor() {
        assertEquals(1000, diffIndex(1000, 0));
        assertEquals(1000, diffIndex(1000, -5));
    }
}
