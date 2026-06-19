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
 * Unit tests for the pure helpers behind the per-50-carriage effectless-potion
 * effect: {@link ContainerContentsRoller#potionEffectTierIndex(int)},
 * {@link ContainerContentsRoller#potionEffectIndex(long, int, int)} and
 * {@link ContainerContentsRoller#potionFormIndex(long, int)}. Mirrors
 * {@code ArrowEpochEffectTest} — both share the same epoch math
 * ({@code epochLevel}/{@code epochTierIndex}/{@code epochPotionIndex}); here we
 * lock in the potion-specific tier table + sticky form roll.
 *
 * <p>The actual potion application ({@code applyEpochPotionEffect}) touches the
 * vanilla potion registry and is covered by the in-game Gate 2 flow.</p>
 */
final class PotionEpochEffectTest {

    @Test
    @DisplayName("potionEffectTierIndex: starts at 0, non-decreasing, clamps at the top tier")
    void tierIndex_monotonicAndClamped() {
        int prev = ContainerContentsRoller.potionEffectTierIndex(0);
        assertEquals(0, prev);
        for (int level = 1; level <= 200; level++) {
            int tier = ContainerContentsRoller.potionEffectTierIndex(level);
            assertTrue(tier >= prev, "tier must be non-decreasing at level " + level);
            assertTrue(tier >= 0, "tier must be non-negative");
            prev = tier;
        }
        int top = ContainerContentsRoller.potionEffectTierIndex(10_000);
        assertEquals(top, ContainerContentsRoller.potionEffectTierIndex(10_001));
        assertTrue(top >= 1, "table should expose more than one tier");
    }

    @Test
    @DisplayName("potionEffectIndex: in range, and degenerate pools collapse to 0")
    void effectIndex_inRange() {
        long seed = 0xC0FFEEBEEF12L;
        for (int carriage = 0; carriage < 500; carriage++) {
            int idx = ContainerContentsRoller.potionEffectIndex(seed, carriage, 3);
            assertTrue(idx >= 0 && idx < 3, "index out of range: " + idx);
        }
        assertEquals(0, ContainerContentsRoller.potionEffectIndex(seed, 123, 1));
        assertEquals(0, ContainerContentsRoller.potionEffectIndex(seed, 123, 0));
    }

    @Test
    @DisplayName("potionEffectIndex: identical for every carriage inside a 50-block (sticky)")
    void effectIndex_stickyWithinBlock() {
        long seed = 0x5EED_F00D_1234L;
        int poolSize = 3;
        int block0 = ContainerContentsRoller.potionEffectIndex(seed, 0, poolSize);
        for (int c = 0; c < 50; c++) {
            assertEquals(block0, ContainerContentsRoller.potionEffectIndex(seed, c, poolSize),
                "carriage " + c + " should match block 0");
        }
        int block1 = ContainerContentsRoller.potionEffectIndex(seed, 50, poolSize);
        for (int c = 50; c < 100; c++) {
            assertEquals(block1, ContainerContentsRoller.potionEffectIndex(seed, c, poolSize),
                "carriage " + c + " should match block 1");
        }
    }

    @Test
    @DisplayName("potionEffectIndex: the block/level actually influences the roll")
    void effectIndex_variesAcrossBlocks() {
        long seed = 0xABCDEF98765L;
        boolean sawZero = false, sawOne = false;
        for (int level = 0; level <= 100; level++) {
            int idx = ContainerContentsRoller.potionEffectIndex(seed, level * 50, 2);
            if (idx == 0) sawZero = true;
            if (idx == 1) sawOne = true;
        }
        assertTrue(sawZero && sawOne,
            "both pool entries should occur across 101 blocks (level must affect the roll)");
    }

    @Test
    @DisplayName("potionFormIndex: in range [0,3) and sticky within a 50-block")
    void formIndex_inRangeAndSticky() {
        long seed = 0xF0F0_AAAA_5555L;
        // In range for a long sweep.
        for (int carriage = 0; carriage < 600; carriage++) {
            int form = ContainerContentsRoller.potionFormIndex(seed, carriage);
            assertTrue(form >= 0 && form < 3, "form out of range: " + form);
        }
        // Sticky within each of the first few bands.
        for (int block = 0; block < 4; block++) {
            int first = block * 50;
            int expected = ContainerContentsRoller.potionFormIndex(seed, first);
            for (int c = first; c < first + 50; c++) {
                assertEquals(expected, ContainerContentsRoller.potionFormIndex(seed, c),
                    "carriage " + c + " form should match block " + block);
            }
        }
    }

    @Test
    @DisplayName("tier table: distinct, non-empty, no long_ aliases, no adjacent overlap")
    void tierTable_isDistinctlyEscalating() {
        List<List<ResourceLocation>> tiers = ContainerContentsRoller.potionEffectTiersView();
        assertTrue(tiers.size() >= 2, "need at least two tiers to escalate");
        for (int t = 0; t < tiers.size(); t++) {
            List<ResourceLocation> tier = tiers.get(t);
            assertFalse(tier.isEmpty(), "tier " + t + " must list at least one potion");
            // No duplicate ids within a tier.
            assertEquals(tier.size(), new HashSet<>(tier).size(),
                "tier " + t + " has duplicate ids");
            for (ResourceLocation id : tier) {
                // long_* variants render with the SAME display name as their base
                // ("long_swiftness" → "Potion of Swiftness"), so they would read
                // as "no change" to the player. Forbid them in the escalation table.
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
}
