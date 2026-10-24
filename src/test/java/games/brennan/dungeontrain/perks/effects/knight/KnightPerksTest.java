package games.brennan.dungeontrain.perks.effects.knight;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Knight perks' pure maths: {@link KnightPerks}. */
class KnightPerksTest {

    private static final int N = 200_000;

    private static double meanSteps(int percent, long seed) {
        RandomSource random = RandomSource.create(seed);
        long total = 0;
        for (int i = 0; i < N; i++) total += KnightPerks.rollSteps(percent, random);
        return total / (double) N;
    }

    @Test
    void overflowStepsAreGuaranteedPlusRemainderChance() {
        RandomSource random = RandomSource.create(1L);
        for (int i = 0; i < 1000; i++) {
            assertEquals(0, KnightPerks.rollSteps(0, random));
            assertEquals(1, KnightPerks.rollSteps(100, random));
            assertEquals(2, KnightPerks.rollSteps(200, random));
            int s = KnightPerks.rollSteps(150, random);
            assertTrue(s == 1 || s == 2);
        }
        assertEquals(0.25, meanSteps(25, 7L), 0.01);
        assertEquals(1.05, meanSteps(105, 8L), 0.01);
    }

    @Test
    void critsClimbOnePercentPerRankUncapped() {
        assertEquals(0, KnightPerks.critPercent(0));
        assertEquals(25, KnightPerks.critPercent(25));
        assertEquals(101, KnightPerks.critPercent(101));
        assertEquals(0, KnightPerks.critPercent(-3));
    }

    @Test
    void critMultiplierSteps() {
        assertEquals(1f, KnightPerks.critMultiplier(0));
        assertEquals(1.5f, KnightPerks.critMultiplier(1));
        assertEquals(2f, KnightPerks.critMultiplier(2));
        assertEquals(2.5f, KnightPerks.critMultiplier(3));
    }

    @Test
    void critRollAtKeyRanks() {
        RandomSource random = RandomSource.create(42L);
        for (int i = 0; i < 1000; i++) {
            assertEquals(1f, KnightPerks.rollCritMultiplier(0, random));
            assertEquals(1.5f, KnightPerks.rollCritMultiplier(100, random));
            float m = KnightPerks.rollCritMultiplier(101, random);
            assertTrue(m == 1.5f || m == 2f);
        }
    }

    @Test
    void killBuffDurationAndLevel() {
        assertEquals(0, KnightPerks.strengthDurationTicks(0));
        assertEquals(40, KnightPerks.strengthDurationTicks(1));   // 2 s, Strength I
        assertEquals(50, KnightPerks.strengthDurationTicks(2));   // 2.5 s
        assertEquals(130, KnightPerks.strengthDurationTicks(10)); // 6.5 s, last rank of Strength I
        assertEquals(0, KnightPerks.strengthAmplifier(10));
        assertEquals(1, KnightPerks.strengthAmplifier(11));       // Strength II
        assertEquals(40, KnightPerks.strengthDurationTicks(11));  // back to 2 s
        assertEquals(50, KnightPerks.strengthDurationTicks(12));
        assertEquals(2, KnightPerks.strengthAmplifier(21));       // Strength III
        assertEquals(40, KnightPerks.strengthDurationTicks(21));
    }

    @Test
    void strengthRefreshRules() {
        assertTrue(KnightPerks.shouldRefreshStrength(-1, 0, 0, 40));
        assertTrue(KnightPerks.shouldRefreshStrength(0, 30, 0, 40));
        assertFalse(KnightPerks.shouldRefreshStrength(0, 100, 0, 40));
        assertTrue(KnightPerks.shouldRefreshStrength(0, 100, 1, 20));
        assertFalse(KnightPerks.shouldRefreshStrength(2, 10, 1, 200));
        assertFalse(KnightPerks.shouldRefreshStrength(-1, 0, 0, 0));
    }

    @Test
    void vampirismChanceClimbsAndOverflows() {
        assertEquals(0, KnightPerks.vampirismPercent(0));
        assertEquals(25, KnightPerks.vampirismPercent(1));
        assertEquals(30, KnightPerks.vampirismPercent(2));
        assertEquals(100, KnightPerks.vampirismPercent(16));
        assertEquals(105, KnightPerks.vampirismPercent(17));
        RandomSource random = RandomSource.create(3L);
        for (int i = 0; i < 1000; i++) {
            assertEquals(1f, KnightPerks.rollVampirismHeal(16, random));
            assertFalse(KnightPerks.rollVampirismHeal(0, random) > 0f);
        }
    }
}
