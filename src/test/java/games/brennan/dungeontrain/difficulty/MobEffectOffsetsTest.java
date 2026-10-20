package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for {@link MobEffectOffsets} and the shifted effect curve it feeds. */
final class MobEffectOffsetsTest {

    @Test
    @DisplayName("a late mob waits for its first-appearance level")
    void lateMobOffset() {
        assertEquals(60, MobEffectOffsets.offsetFor("minecraft:warden", false));
        assertEquals(20, MobEffectOffsets.offsetFor("minecraft:shulker", false));
    }

    @Test
    @DisplayName("a mob not in the table starts at level 0")
    void unknownMobIsZero() {
        assertEquals(0, MobEffectOffsets.offsetFor("minecraft:zombie", false));
    }

    @Test
    @DisplayName("a baby waits five more levels than an adult")
    void babyAddsFive() {
        assertEquals(MobEffectOffsets.BABY_OFFSET, MobEffectOffsets.offsetFor("minecraft:zombie", true));
        assertEquals(20 + MobEffectOffsets.BABY_OFFSET, MobEffectOffsets.offsetFor("minecraft:shulker", true));
    }

    @Test
    @DisplayName("every offset is non-negative and every id is namespaced")
    void tableIsSane() {
        MobEffectOffsets.table().forEach((id, v) -> {
            assertTrue(v >= 0, id + " has a negative offset");
            assertTrue(id.contains(":"), id + " is not namespaced");
        });
    }

    @Test
    @DisplayName("no buffs at or below level 0; Speed is available just past level 15")
    void shiftedCurve() {
        assertTrue(ProceduralTiers.effectsFor(0).isEmpty());
        assertTrue(ProceduralTiers.effectsFor(-40).isEmpty());
        assertTrue(ProceduralTiers.effectsFor(15).isEmpty(), "Speed chance is 0 at its start level");
        assertFalse(ProceduralTiers.effectsFor(16).isEmpty(), "Speed rolls from 16");
        // A warden at difficulty 70 reads the curve at 10: still unbuffed.
        assertTrue(ProceduralTiers.effectsFor(70 - MobEffectOffsets.offsetFor("minecraft:warden", false)).isEmpty());
    }
}
