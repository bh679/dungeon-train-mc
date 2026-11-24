package games.brennan.dungeontrain.difficulty;

import games.brennan.adventureitemstats.api.StatsModifier;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lucky mobs: {@link #LUCKY_SHARE} of the mobs at every effect level carry gear rolled close to
 * chest quality. The share goes to the mobs whose potion effects rolled best — the most effects at
 * their stage's top level ({@link ProceduralTiers#effectsFor}) — judged against each mob's own effect
 * level, so its offset ({@link MobEffectOffsets}) moves it onto its own caps.
 *
 * <p><b>Who.</b> At an effect level the number of effects at their top level follows a fixed
 * distribution (each effect is there with its chance, and at the top of its range with
 * {@code 1 / (cap - floor + 1)}). The {@link Cutoff} picks every mob with at least {@code count}
 * and part of those with {@code count - 1}, so exactly {@link #LUCKY_SHARE} of mobs are lucky. Where a
 * level has no effects every mob counts 0 and the same rule picks a flat {@link #LUCKY_SHARE}. Mobs
 * that never get the effect pass (creepers; PlayerMobs and echoes, which roll gear only) are never
 * lucky — the caller does not ask.</p>
 *
 * <p><b>What.</b> Every piece a lucky mob gets lands at a fraction of the best that same item could
 * have from a chest at this level: AIS's top roll ({@link #CHEST_BEST_FACTOR}) plus the chest's flat
 * bonus ({@link ItemStatLevelScaling#primaryStatBonus} at the full tier). The fraction is uniform over
 * [{@link #MIN_FRACTION}, 1], drawn per piece, and a lucky piece never comes out below its normal roll.
 * Material is not upgraded. Lucky pieces drop at {@link #LUCKY_DROP_CHANCE}.</p>
 */
public final class LuckyGear {

    /** Share of mobs at each effect level that are lucky. */
    static final double LUCKY_SHARE = 0.10;

    /** Drop chance of a lucky mob's gear (normal difficulty gear drops at 0.05). */
    public static final float LUCKY_DROP_CHANCE = 0.25f;

    /** Lowest fraction of the chest-best stats a lucky piece rolls. */
    static final double MIN_FRACTION = 0.5;

    /** AIS's best possible multiplier on an item's effective stat — the top of a chest roll. */
    static final double CHEST_BEST_FACTOR = 1.5;

    private static final Map<Integer, Cutoff> CUTOFFS = new ConcurrentHashMap<>();

    private LuckyGear() {}

    /**
     * Mobs with at least {@code count} effects at their top level are lucky; mobs with exactly
     * {@code count - 1} are lucky with probability {@code partialShare}; the rest are not.
     */
    record Cutoff(int count, double partialShare) {}

    /** Probability a mob at {@code effectLevel} with {@code maxCount} top-level effects is lucky. */
    public static double luckyChance(int effectLevel, int maxCount) {
        Cutoff cutoff = CUTOFFS.computeIfAbsent(effectLevel,
                level -> cutoffFor(maxEffectDistribution(ProceduralTiers.effectsFor(level))));
        return chanceFor(cutoff, maxCount);
    }

    /** True when {@code amplifier} is the top of {@code spec}'s range at its level. */
    public static boolean isAtCap(int amplifier, DifficultyTier.EffectSpec spec) {
        return amplifier >= spec.amplifier();
    }

    /** Pure: the lucky probability for {@code maxCount} under {@code cutoff}. */
    static double chanceFor(Cutoff cutoff, int maxCount) {
        if (maxCount >= cutoff.count()) return 1.0;
        if (maxCount == cutoff.count() - 1) return cutoff.partialShare();
        return 0.0;
    }

    /**
     * Pure: {@code dist[k]} = probability that exactly {@code k} of {@code specs} are rolled and at their
     * top level. An effect is rolled with its chance; its amplifier is uniform over [floor, cap].
     */
    static double[] maxEffectDistribution(List<DifficultyTier.EffectSpec> specs) {
        double[] dist = {1.0};
        for (DifficultyTier.EffectSpec spec : specs) {
            double p = atCapProbability(spec);
            double[] next = new double[dist.length + 1];
            for (int k = 0; k < dist.length; k++) {
                next[k] += dist[k] * (1.0 - p);
                next[k + 1] += dist[k] * p;
            }
            dist = next;
        }
        return dist;
    }

    /** Pure: chance an effect is rolled at all and lands on the top of its range. */
    static double atCapProbability(DifficultyTier.EffectSpec spec) {
        int cap = Math.max(0, spec.amplifier());
        int floor = Math.max(0, Math.min(spec.minAmplifier(), cap));
        double chance = Math.max(0.0, Math.min(1.0, spec.chance()));
        return chance / (cap - floor + 1);
    }

    /** Pure: the cutoff that makes exactly {@link #LUCKY_SHARE} of a {@code dist} population lucky. */
    static Cutoff cutoffFor(double[] dist) {
        double[] tail = new double[dist.length + 1];
        for (int k = dist.length - 1; k >= 0; k--) tail[k] = tail[k + 1] + dist[k];
        int count = 1;
        while (tail[count] > LUCKY_SHARE) count++;
        double above = tail[count];
        double band = tail[count - 1] - above;
        double partial = band <= 0.0 ? 0.0 : (LUCKY_SHARE - above) / band;
        return new Cutoff(count, Math.max(0.0, Math.min(1.0, partial)));
    }

    /** Pure: the AIS factor for a lucky piece rolled at {@code fraction} of the chest best. */
    static double luckyFactor(double fraction) {
        return CHEST_BEST_FACTOR * fraction;
    }

    /** Fraction of the chest best a lucky piece rolls: uniform over [{@link #MIN_FRACTION}, 1]. */
    static double rollFraction(RandomSource rng) {
        return MIN_FRACTION + (1.0 - MIN_FRACTION) * rng.nextDouble();
    }

    /**
     * Roll AIS stats onto an unrolled {@code stack}. A normal piece takes the plain AIS roll plus
     * {@code normalBonus}. A lucky piece rolls both that and a lucky roll at a fraction of the chest
     * best for {@code tierIndex}, and keeps whichever has the stronger primary stat.
     *
     * @return the fraction rolled for a lucky piece, or {@code -1} for a normal one
     */
    public static double applyStats(ItemStack stack, RandomSource rng, double normalBonus,
                                    int tierIndex, boolean lucky) {
        if (!lucky) {
            StatsModifier.applyStats(stack, rng, normalBonus);
            return -1.0;
        }
        ItemStack normal = stack.copy();
        StatsModifier.applyStats(normal, rng, normalBonus);
        double fraction = rollFraction(rng);
        double chestBonus = ItemStatLevelScaling.primaryStatBonus(stack, tierIndex);
        StatsModifier.applyStatsAtFactor(stack, luckyFactor(fraction), fraction * chestBonus);
        if (primaryAmount(normal) > primaryAmount(stack)) {
            ItemAttributeModifiers kept = normal.get(DataComponents.ATTRIBUTE_MODIFIERS);
            if (kept != null) stack.set(DataComponents.ATTRIBUTE_MODIFIERS, kept);
        }
        return fraction;
    }

    /** Sum of the primary-stat modifiers AIS writes: attack damage, armor, Ranged Damage. */
    static double primaryAmount(ItemStack stack) {
        ItemAttributeModifiers mods = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (mods == null) return 0.0;
        Attribute ranged = StatsModifier.rangedDamageAttribute().<Attribute>map(Holder::value).orElse(null);
        double sum = 0.0;
        for (ItemAttributeModifiers.Entry entry : mods.modifiers()) {
            Attribute attr = entry.attribute().value();
            if (attr == Attributes.ATTACK_DAMAGE.value() || attr == Attributes.ARMOR.value() || attr == ranged) {
                sum += entry.modifier().amount();
            }
        }
        return sum;
    }
}
