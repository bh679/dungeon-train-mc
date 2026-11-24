package games.brennan.dungeontrain.difficulty;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LuckyGearTest {

    private static final double EPS = 1.0e-9;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Expected lucky share over the whole population at {@code level}: sum of P(count) x chance(count). */
    private static double populationShare(int level) {
        double[] dist = LuckyGear.maxEffectDistribution(ProceduralTiers.effectsFor(level));
        double share = 0.0;
        for (int k = 0; k < dist.length; k++) share += dist[k] * LuckyGear.luckyChance(level, k);
        return share;
    }

    @ParameterizedTest(name = "effect level {0}")
    @ValueSource(ints = {-5, 0, 1, 14, 16, 17, 20, 30, 40, 50, 60, 100, 120, 200, 250, 330, 400})
    @DisplayName("exactly 10% of mobs are lucky at every effect level")
    void exactlyTenPercent(int level) {
        assertEquals(LuckyGear.LUCKY_SHARE, populationShare(level), EPS);
    }

    @ParameterizedTest(name = "effect level {0}")
    @ValueSource(ints = {-5, 0, 1, 10, 15})
    @DisplayName("levels with no effects pick a flat 10% of mobs")
    void noEffectStageIsFlat(int level) {
        assertEquals(LuckyGear.LUCKY_SHARE, LuckyGear.luckyChance(level, 0), EPS);
    }

    @ParameterizedTest(name = "effect level {0}")
    @ValueSource(ints = {17, 30, 50, 100, 200, 400})
    @DisplayName("more effects at their top level never lowers the chance")
    void monotonicInCount(int level) {
        double previous = -1.0;
        for (int k = 0; k <= 10; k++) {
            double chance = LuckyGear.luckyChance(level, k);
            assertTrue(chance >= previous, "count " + k);
            previous = chance;
        }
        assertEquals(1.0, LuckyGear.luckyChance(level, 10), EPS);
    }

    @Test
    @DisplayName("cutoff picks the top of the distribution")
    void cutoffShape() {
        // 50% have 0, 30% have 1, 20% have 2 → everyone with 2 is too many, so half of the twos.
        LuckyGear.Cutoff cutoff = LuckyGear.cutoffFor(new double[] {0.5, 0.3, 0.2});
        assertEquals(3, cutoff.count());
        assertEquals(0.5, cutoff.partialShare(), EPS);
    }

    @Test
    @DisplayName("an effect counts at its cap with chance / range size")
    void atCapProbability() {
        var spec = new DifficultyTier.EffectSpec(null, 0, 3, -1, 0.8);
        assertEquals(0.2, LuckyGear.atCapProbability(spec), EPS);
        var fixed = new DifficultyTier.EffectSpec(null, 0, 0, -1, 0.7);
        assertEquals(0.7, LuckyGear.atCapProbability(fixed), EPS);
        var floored = new DifficultyTier.EffectSpec(null, 2, 3, -1, 0.6);
        assertEquals(0.3, LuckyGear.atCapProbability(floored), EPS);
    }

    @Test
    @DisplayName("Monte Carlo: rolling real mobs lands at 10%")
    void monteCarlo() {
        RandomSource rng = RandomSource.create(1234L);
        for (int level : List.of(20, 60, 150)) {
            List<DifficultyTier.EffectSpec> specs = ProceduralTiers.effectsFor(level);
            int lucky = 0;
            int mobs = 100_000;
            for (int i = 0; i < mobs; i++) {
                int atCap = 0;
                for (DifficultyTier.EffectSpec spec : specs) {
                    if (rng.nextDouble() >= spec.chance()) continue;
                    int amp = DifficultyApplier.rollAmplifier(spec.minAmplifier(), spec.amplifier(), rng);
                    if (LuckyGear.isAtCap(amp, spec)) atCap++;
                }
                if (rng.nextDouble() < LuckyGear.luckyChance(level, atCap)) lucky++;
            }
            assertEquals(0.10, lucky / (double) mobs, 0.005, "level " + level);
        }
    }

    @Test
    @DisplayName("lucky factor spans half to all of AIS's best roll")
    void luckyFactorRange() {
        assertEquals(0.75, LuckyGear.luckyFactor(0.5), EPS);
        assertEquals(1.5, LuckyGear.luckyFactor(1.0), EPS);
    }

    private static double armor(ItemStack stack) {
        ItemAttributeModifiers mods = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        double sum = 0.0;
        for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
            if (e.attribute().value() == Attributes.ARMOR.value()) sum += e.modifier().amount();
        }
        return sum;
    }

    @Test
    @DisplayName("lucky iron chestplate at level 30 lands at 50-100% of the chest best (6 to 12)")
    void luckyChestplateRange() {
        RandomSource rng = RandomSource.create(99L);
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = 0; i < 2_000; i++) {
            ItemStack stack = new ItemStack(Items.IRON_CHESTPLATE);
            LuckyGear.applyStats(stack, rng, 0.0, 30, true);
            double a = armor(stack);
            min = Math.min(min, a);
            max = Math.max(max, a);
        }
        // chest best: 6 x 1.5 + 0.1 x 30 = 12; lucky floor = half of it, and never below a normal roll
        assertTrue(min >= 6.0 - EPS, "min " + min);
        assertTrue(max <= 12.0 + EPS, "max " + max);
        assertTrue(max > 11.5, "max " + max);
    }

    @Test
    @DisplayName("a normal piece rolls plain AIS stats")
    void normalChestplateRange() {
        RandomSource rng = RandomSource.create(7L);
        for (int i = 0; i < 500; i++) {
            ItemStack stack = new ItemStack(Items.IRON_CHESTPLATE);
            assertEquals(-1.0, LuckyGear.applyStats(stack, rng, 0.0, 30, false), EPS);
            double a = armor(stack);
            assertTrue(a >= 3.0 - EPS && a <= 9.0 + EPS, "armor " + a);
        }
    }
}
