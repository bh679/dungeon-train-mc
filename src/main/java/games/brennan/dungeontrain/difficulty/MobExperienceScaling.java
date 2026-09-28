package games.brennan.dungeontrain.difficulty;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * How much experience a monster drops, given how dangerous it actually is.
 *
 * <p>DT gears hostile mobs hard as the train progresses — tiered armor and weapons, enchantments
 * rolled past their vanilla caps ({@link EnchantLevelCap}), AIS stat bonuses past the netherite
 * material cap ({@link ItemStatLevelScaling}), and stacked infinite buffs from
 * {@link ProceduralTiers}. Vanilla pays the same handful of XP for all of it, so the reward curve
 * flattens exactly where the difficulty curve steepens. This scales the drop with the mob's gear
 * and buffs so a late-train kill is worth the fight.</p>
 *
 * <p>The score is read off the <em>living mob at death</em>, not from a recorded difficulty tier.
 * That keeps it honest about the roll (an unlucky bare mob at tier 40 stays cheap), survives a
 * progress reset or respawn, needs nothing new in entity NBT, and rewards a naturally-armored
 * vanilla mob on the same terms.</p>
 *
 * <p>{@link #scaledXp(int, double, int, int)} is the pure core, kept package-private and free of
 * config reads so it is unit-testable without a Minecraft bootstrap — the same split as
 * {@link ItemStatLevelScaling#bonusFor} and {@link DifficultyProgression#rawTier}.</p>
 */
public final class MobExperienceScaling {

    private MobExperienceScaling() {}

    /**
     * The experience {@code entity} should drop, scaled by its worn gear and active buffs.
     * Returns {@code baseXp} unchanged when the feature is off, the mob is bare and unbuffed, or
     * {@code baseXp} is not positive (a mob dropping nothing keeps dropping nothing).
     */
    public static int scaledXp(LivingEntity entity, int baseXp) {
        if (entity == null || baseXp <= 0) return baseXp;
        if (!DungeonTrainConfig.getMobExperienceScalingEnabled()) return baseXp;

        double statPoints = 0;
        int enchantLevels = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            statPoints += ItemPowerScore.baseStat(stack);
            enchantLevels += ItemPowerScore.totalEnchantmentLevels(stack);
        }

        return scaledXp(baseXp, statPoints, enchantLevels, beneficialEffectPoints(entity));
    }

    /**
     * One point per level of every <em>beneficial</em> active effect (amplifier 0 = Speed I = 1
     * point). Harmful and neutral effects score nothing — a mob crippled by Weakness is not a
     * harder fight, and paying a bonus for one would reward the player for their own debuff.
     */
    static int beneficialEffectPoints(LivingEntity entity) {
        int points = 0;
        for (MobEffectInstance instance : entity.getActiveEffects()) {
            if (instance.getEffect().value().getCategory() != MobEffectCategory.BENEFICIAL) continue;
            points += instance.getAmplifier() + 1;
        }
        return points;
    }

    /** Config-reading overload of the pure core. */
    static int scaledXp(int baseXp, double statPoints, int enchantLevels, int effectPoints) {
        return scaledXp(baseXp, statPoints, enchantLevels, effectPoints,
                DungeonTrainConfig.getMobExperiencePerStatPoint(),
                DungeonTrainConfig.getMobExperiencePerEnchantLevel(),
                DungeonTrainConfig.getMobExperiencePerEffectPoint(),
                DungeonTrainConfig.getMobExperienceMaxMultiplier());
    }

    /**
     * The pure core: {@code round(baseXp × min(maxMultiplier, 1 + Σ weighted points))}.
     *
     * <p>Negative point totals cannot reduce the drop below {@code baseXp} — the multiplier floors
     * at 1, so this only ever adds. Pure; no config, no Minecraft state.</p>
     */
    static int scaledXp(int baseXp,
                        double statPoints,
                        int enchantLevels,
                        int effectPoints,
                        double perStatPoint,
                        double perEnchantLevel,
                        double perEffectPoint,
                        double maxMultiplier) {
        if (baseXp <= 0) return baseXp;
        double bonus = Math.max(0, statPoints) * perStatPoint
                + Math.max(0, enchantLevels) * perEnchantLevel
                + Math.max(0, effectPoints) * perEffectPoint;
        if (!Double.isFinite(bonus) || bonus <= 0) return baseXp;

        double multiplier = Math.min(Math.max(1.0, maxMultiplier), 1.0 + bonus);
        long scaled = Math.round(baseXp * multiplier);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(baseXp, scaled));
    }
}
