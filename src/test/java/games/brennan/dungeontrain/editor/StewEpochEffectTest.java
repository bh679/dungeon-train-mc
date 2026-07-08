package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure helpers behind the effectless-suspicious-stew
 * effect: {@link ContainerContentsRoller#stewEffectIndex(BlockPos, long, int, int, int)}.
 *
 * <p>Unlike the arrow/potion epoch effects, suspicious stew does <b>not</b>
 * escalate with distance travelled — vanilla suspicious stew effects don't
 * scale with anything, they're just "whichever flower it was mixed with" —
 * so every stew, anywhere on the train, picks uniformly from the full
 * {@link ContainerContentsRoller#stewEffectsView()} pool. The effect is
 * per-instance random — keyed on the full {@code (localPos, worldSeed,
 * carriageIndex, slot)} — so two stews in the same chest can differ, while a
 * fixed chest/slot stays deterministic. These tests lock that contract; the
 * actual registry application ({@code applyEpochStewEffect}) is covered by
 * the in-game Gate 2 flow.</p>
 */
final class StewEpochEffectTest {

    @Test
    @DisplayName("stewEffectIndex: in range, and degenerate pools collapse to 0")
    void effectIndex_inRangeAndDegenerate() {
        long seed = 0xC0FFEEBEEF12L;
        for (int x = 0; x < 12; x++) {
            for (int z = 0; z < 12; z++) {
                for (int slot = 0; slot < 6; slot++) {
                    int idx = ContainerContentsRoller.stewEffectIndex(
                        new BlockPos(x, 4, z), seed, 0, slot, 3);
                    assertTrue(idx >= 0 && idx < 3, "index out of range: " + idx);
                }
            }
        }
        BlockPos p = new BlockPos(1, 2, 3);
        assertEquals(0, ContainerContentsRoller.stewEffectIndex(p, seed, 7, 0, 1));
        assertEquals(0, ContainerContentsRoller.stewEffectIndex(p, seed, 7, 0, 0));
    }

    @Test
    @DisplayName("stewEffectIndex: varies across positions, stable for a fixed position")
    void effectIndex_variesButDeterministic() {
        long seed = 0x5EED_F00D_1234L;
        int carriage = 0;
        Set<Integer> seen = new HashSet<>();
        for (int x = 0; x < 20; x++) {
            for (int slot = 0; slot < 5; slot++) {
                BlockPos pos = new BlockPos(x, 4, 0);
                int a = ContainerContentsRoller.stewEffectIndex(pos, seed, carriage, slot, 9);
                int b = ContainerContentsRoller.stewEffectIndex(pos, seed, carriage, slot, 9);
                assertEquals(a, b, "same (pos,slot) must be deterministic at " + pos + " slot " + slot);
                seen.add(a);
            }
        }
        assertTrue(seen.size() >= 2,
            "stews should NOT all share an effect index (got " + seen + ")");
    }

    @Test
    @DisplayName("stewEffectIndex: also varies across distant carriages (no escalation lock-in)")
    void effectIndex_variesAcrossCarriages() {
        long seed = 0xABCD_1234_5678L;
        BlockPos pos = new BlockPos(2, 4, 2);
        Set<Integer> seen = new HashSet<>();
        for (int carriage = 0; carriage < 400; carriage += 10) {
            seen.add(ContainerContentsRoller.stewEffectIndex(pos, seed, carriage, 0, 9));
        }
        assertTrue(seen.size() >= 2,
            "stews at different carriage positions should still vary (got " + seen + ")");
    }

    @Test
    @DisplayName("effect pool: exactly the vanilla suspicious-stew effect set, no duplicates")
    void effectPool_matchesVanillaSet() {
        List<ResourceLocation> pool = ContainerContentsRoller.stewEffectsView();
        assertTrue(pool.size() >= 5, "pool should cover a meaningful spread of vanilla effects");
        assertEquals(pool.size(), new HashSet<>(pool).size(), "pool must not contain duplicates");

        // Every vanilla flower-derived effect (Blocks.java): saturation, night_vision,
        // fire_resistance, blindness, weakness, regeneration, jump_boost, poison, wither.
        Set<String> expected = Set.of("saturation", "night_vision", "fire_resistance",
            "blindness", "weakness", "regeneration", "jump_boost", "poison", "wither");
        Set<String> actual = new HashSet<>();
        for (ResourceLocation id : pool) actual.add(id.getPath());
        assertEquals(expected, actual, "pool must match the exact vanilla suspicious-stew effect set");
    }
}
