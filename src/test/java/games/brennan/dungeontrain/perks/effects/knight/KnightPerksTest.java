package games.brennan.dungeontrain.perks.effects.knight;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Knight perks' pure maths: {@link KnightPerks}. */
class KnightPerksTest {

    @Test
    void critChanceIsOnePercentPerRankCappedAtCertain() {
        assertEquals(0f, KnightPerks.critChance(0));
        assertEquals(0.01f, KnightPerks.critChance(1), 1e-6);
        assertEquals(0.5f, KnightPerks.critChance(50), 1e-6);
        assertEquals(1f, KnightPerks.critChance(100));
        assertEquals(1f, KnightPerks.critChance(250));
        assertEquals(0f, KnightPerks.critChance(-3));
    }

    @Test
    void rollNeverCritsAtRankZeroAndAlwaysAtHundred() {
        RandomSource random = RandomSource.create(42L);
        for (int i = 0; i < 1000; i++) {
            assertFalse(KnightPerks.rollCrit(0, random));
            assertTrue(KnightPerks.rollCrit(100, random));
        }
    }

    @Test
    void rollLandsNearRankPercent() {
        RandomSource random = RandomSource.create(7L);
        int rank = 25;
        int n = 200_000;
        int hits = 0;
        for (int i = 0; i < n; i++) if (KnightPerks.rollCrit(rank, random)) hits++;
        assertEquals(0.25, hits / (double) n, 0.01);
    }

    @Test
    void strengthLastsTwoSecondsPerRank() {
        assertEquals(0, KnightPerks.strengthDurationTicks(0));
        assertEquals(40, KnightPerks.strengthDurationTicks(1));
        assertEquals(200, KnightPerks.strengthDurationTicks(5));
        assertEquals(0, KnightPerks.strengthDurationTicks(-1));
    }

    @Test
    void killBuffHealsHalfAHeartFlat() {
        assertEquals(0f, KnightPerks.killBuffHeal(0));
        assertEquals(1f, KnightPerks.killBuffHeal(1));
        assertEquals(1f, KnightPerks.killBuffHeal(9));
    }

    @Test
    void vampirismHealsHalfAHeartPerRank() {
        assertEquals(0f, KnightPerks.vampirismHeal(0));
        assertEquals(1f, KnightPerks.vampirismHeal(1));
        assertEquals(4f, KnightPerks.vampirismHeal(4));
    }

    @Test
    void strengthRefreshOnlyExtends() {
        assertTrue(KnightPerks.shouldRefreshStrength(0, 40));
        assertTrue(KnightPerks.shouldRefreshStrength(30, 40));
        assertFalse(KnightPerks.shouldRefreshStrength(100, 40));
        assertFalse(KnightPerks.shouldRefreshStrength(40, 40));
        assertFalse(KnightPerks.shouldRefreshStrength(0, 0));
    }
}
