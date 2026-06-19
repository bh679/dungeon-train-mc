package games.brennan.dungeontrain.editor;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure helpers behind the per-50-carriage "Uncraftable"
 * tipped-arrow effect: {@link ContainerContentsRoller#arrowEffectLevel(int)},
 * {@link ContainerContentsRoller#arrowEffectTierIndex(int)} and
 * {@link ContainerContentsRoller#arrowPotionIndex(long, int, int)}.
 *
 * <p>The actual potion application ({@code applyEpochArrowEffect}) touches the
 * vanilla potion registry and is covered by the in-game Gate 2 flow; here we
 * lock in the deterministic math that makes the effect sticky-per-block and
 * escalating with progression.</p>
 */
final class ArrowEpochEffectTest {

    @Test
    @DisplayName("arrowEffectLevel: groups carriages into 50-blocks, symmetric for backward gen")
    void level_groupsIntoBlocks() {
        assertEquals(0, ContainerContentsRoller.arrowEffectLevel(0));
        assertEquals(0, ContainerContentsRoller.arrowEffectLevel(49));
        assertEquals(1, ContainerContentsRoller.arrowEffectLevel(50));
        assertEquals(1, ContainerContentsRoller.arrowEffectLevel(99));
        assertEquals(2, ContainerContentsRoller.arrowEffectLevel(100));
        // Backward generation (negative indices) mirrors forward distance.
        assertEquals(0, ContainerContentsRoller.arrowEffectLevel(-1));
        assertEquals(0, ContainerContentsRoller.arrowEffectLevel(-49));
        assertEquals(1, ContainerContentsRoller.arrowEffectLevel(-50));
        assertEquals(2, ContainerContentsRoller.arrowEffectLevel(-100));
    }

    @Test
    @DisplayName("arrowEffectTierIndex: starts at 0, non-decreasing, clamps at the top tier")
    void tierIndex_monotonicAndClamped() {
        int prev = ContainerContentsRoller.arrowEffectTierIndex(0);
        assertEquals(0, prev);
        for (int level = 1; level <= 200; level++) {
            int tier = ContainerContentsRoller.arrowEffectTierIndex(level);
            assertTrue(tier >= prev, "tier must be non-decreasing at level " + level);
            assertTrue(tier >= 0, "tier must be non-negative");
            prev = tier;
        }
        // Far-future levels are clamped to a stable maximum tier.
        int top = ContainerContentsRoller.arrowEffectTierIndex(10_000);
        assertEquals(top, ContainerContentsRoller.arrowEffectTierIndex(10_001));
        assertTrue(top >= 1, "table should expose more than one tier");
    }

    @Test
    @DisplayName("arrowPotionIndex: in range, and degenerate pools collapse to 0")
    void potionIndex_inRange() {
        long seed = 0xDEADBEEFCAFEL;
        for (int carriage = 0; carriage < 500; carriage++) {
            int idx = ContainerContentsRoller.arrowPotionIndex(seed, carriage, 6);
            assertTrue(idx >= 0 && idx < 6, "index out of range: " + idx);
        }
        assertEquals(0, ContainerContentsRoller.arrowPotionIndex(seed, 123, 1));
        assertEquals(0, ContainerContentsRoller.arrowPotionIndex(seed, 123, 0));
    }

    @Test
    @DisplayName("arrowPotionIndex: identical for every carriage inside a 50-block (sticky)")
    void potionIndex_stickyWithinBlock() {
        long seed = 0x1234_5678_9ABCL;
        int poolSize = 4;
        // Block 0 (carriages 0..49) all share one index.
        int block0 = ContainerContentsRoller.arrowPotionIndex(seed, 0, poolSize);
        for (int c = 0; c < 50; c++) {
            assertEquals(block0, ContainerContentsRoller.arrowPotionIndex(seed, c, poolSize),
                "carriage " + c + " should match block 0");
        }
        // Block 1 (carriages 50..99) all share one index too.
        int block1 = ContainerContentsRoller.arrowPotionIndex(seed, 50, poolSize);
        for (int c = 50; c < 100; c++) {
            assertEquals(block1, ContainerContentsRoller.arrowPotionIndex(seed, c, poolSize),
                "carriage " + c + " should match block 1");
        }
    }

    @Test
    @DisplayName("tier table: distinct, non-empty, no long_ aliases, no adjacent overlap")
    void tierTable_isDistinctlyEscalating() {
        List<List<ResourceLocation>> tiers = ContainerContentsRoller.arrowEffectTiersView();
        assertTrue(tiers.size() >= 2, "need at least two tiers to escalate");
        for (int t = 0; t < tiers.size(); t++) {
            List<ResourceLocation> tier = tiers.get(t);
            assertFalse(tier.isEmpty(), "tier " + t + " must list at least one potion");
            for (ResourceLocation id : tier) {
                // long_* variants render with the SAME display name as their base
                // ("long_slowness" → "Arrow of Slowness"), so they would read as
                // "no change" to the player. Forbid them in the escalation table.
                assertFalse(id.getPath().startsWith("long_"),
                    "tier " + t + " uses a same-name duration variant: " + id);
            }
            // Adjacent tiers must not share a potion, or crossing a 50-carriage
            // boundary could land on the same effect and look like no change.
            if (t > 0) {
                Set<ResourceLocation> prev = new HashSet<>(tiers.get(t - 1));
                for (ResourceLocation id : tier) {
                    assertFalse(prev.contains(id),
                        "tier " + t + " overlaps tier " + (t - 1) + " on " + id);
                }
            }
        }
    }

    @Test
    @DisplayName("arrowPotionIndex: the block/level actually influences the roll")
    void potionIndex_variesAcrossBlocks() {
        long seed = 0xABCDEF12345L;
        boolean sawZero = false, sawOne = false;
        for (int level = 0; level <= 100; level++) {
            int carriage = level * 50; // first carriage of each block
            int idx = ContainerContentsRoller.arrowPotionIndex(seed, carriage, 2);
            if (idx == 0) sawZero = true;
            if (idx == 1) sawOne = true;
        }
        assertTrue(sawZero && sawOne,
            "both pool entries should occur across 101 blocks (level must affect the roll)");
    }
}
