package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
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
 * Unit tests for the pure helpers behind the effectless-potion epoch effect:
 * {@link ContainerContentsRoller#potionEffectTierIndex(int)},
 * {@link ContainerContentsRoller#potionEffectIndex(BlockPos, long, int, int, int)} and
 * {@link ContainerContentsRoller#potionFormIndex(BlockPos, long, int, int)}.
 *
 * <p>Unlike the band-locked arrow effect ({@code ArrowEpochEffectTest}), the
 * potion <b>tier</b> escalates with distance but the specific effect + form are
 * <b>per-instance random</b> — keyed on the full {@code (localPos, worldSeed,
 * carriageIndex, slot)} so two potions in the same 50-carriage band can differ,
 * while a fixed chest/slot stays deterministic. These tests lock that contract;
 * the actual registry application ({@code applyEpochPotionEffect}) is covered by
 * the in-game Gate 2 flow.</p>
 */
final class PotionEpochEffectTest {

    @Test
    @DisplayName("potionEffectTierIndex: starts at 0, non-decreasing with distance, clamps at the top")
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
    void effectIndex_inRangeAndDegenerate() {
        long seed = 0xC0FFEEBEEF12L;
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                for (int slot = 0; slot < 6; slot++) {
                    int idx = ContainerContentsRoller.potionEffectIndex(
                        new BlockPos(x, 4, z), seed, 0, slot, 3);
                    assertTrue(idx >= 0 && idx < 3, "index out of range: " + idx);
                }
            }
        }
        BlockPos p = new BlockPos(1, 2, 3);
        assertEquals(0, ContainerContentsRoller.potionEffectIndex(p, seed, 7, 0, 1));
        assertEquals(0, ContainerContentsRoller.potionEffectIndex(p, seed, 7, 0, 0));
    }

    @Test
    @DisplayName("potionEffectIndex: varies across positions in the same band, stable for a fixed position")
    void effectIndex_variesButDeterministic() {
        long seed = 0x5EED_F00D_1234L;
        int carriage = 0; // a single band
        Set<Integer> seen = new HashSet<>();
        for (int x = 0; x < 20; x++) {
            for (int slot = 0; slot < 5; slot++) {
                BlockPos pos = new BlockPos(x, 4, 0);
                int a = ContainerContentsRoller.potionEffectIndex(pos, seed, carriage, slot, 3);
                int b = ContainerContentsRoller.potionEffectIndex(pos, seed, carriage, slot, 3);
                assertEquals(a, b, "same (pos,slot) must be deterministic at " + pos + " slot " + slot);
                seen.add(a);
            }
        }
        assertTrue(seen.size() >= 2,
            "potions within one band should NOT all share an effect index (got " + seen + ")");
    }

    @Test
    @DisplayName("potionFormIndex: in range [0,3), varies across positions, stable for a fixed position")
    void formIndex_inRangeVariesAndStable() {
        long seed = 0xF0F0_AAAA_5555L;
        Set<Integer> seen = new HashSet<>();
        for (int x = 0; x < 20; x++) {
            for (int slot = 0; slot < 5; slot++) {
                BlockPos pos = new BlockPos(x, 4, 1);
                int form = ContainerContentsRoller.potionFormIndex(pos, seed, 0, slot);
                assertTrue(form >= 0 && form < 3, "form out of range: " + form);
                assertEquals(form, ContainerContentsRoller.potionFormIndex(pos, seed, 0, slot),
                    "form must be deterministic for a fixed (pos,slot)");
                seen.add(form);
            }
        }
        assertTrue(seen.size() >= 2,
            "bottle form should vary across positions (got " + seen + ")");
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
            // Adjacent tiers must not share a potion, or crossing a band boundary
            // could land on the same effect and look like no progression.
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
