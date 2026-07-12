package games.brennan.dungeontrain.difficulty;

import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;

/**
 * Difficulty-driven flat bonus added to an item's <em>primary</em> AIS stat (attack
 * damage for weapons, armor for armor pieces). The bonus grows linearly with the
 * carriage's {@link DifficultyProgression#tierForTravelled difficulty tier}:
 * {@code rate * tier}, where the rate is {@link #PER_LEVEL_AXE} for axes and
 * {@link #PER_LEVEL_DEFAULT} for everything else. Tier 0 (and below) yields no bonus.
 *
 * <p>This is DT's <em>policy</em> over the generic flat-bonus mechanism AIS exposes via
 * {@code StatsModifier.applyStats(stack, rng, primaryStatBonus)} — AIS itself stays
 * unaware of "levels" or "axes". The pure {@link #bonusFor(boolean, int)} carries the
 * math so it is unit-testable without a Minecraft bootstrap, mirroring
 * {@link DifficultyProgression#rawTier}.</p>
 */
public final class ItemStatLevelScaling {

    /** Flat primary-stat bonus per difficulty tier for a non-axe item. */
    static final double PER_LEVEL_DEFAULT = 0.10;

    /** Steeper per-tier bonus for axes — they scale faster than other gear. */
    static final double PER_LEVEL_AXE = 0.15;

    private ItemStatLevelScaling() {}

    /** Per-tier bonus rate for an item, by whether it is an axe. Pure. */
    static double perLevelRate(boolean isAxe) {
        return isAxe ? PER_LEVEL_AXE : PER_LEVEL_DEFAULT;
    }

    /** Flat primary-stat bonus at {@code difficultyTier}; {@code 0.0} at tier ≤ 0. Pure. */
    static double bonusFor(boolean isAxe, int difficultyTier) {
        return difficultyTier <= 0 ? 0.0 : perLevelRate(isAxe) * difficultyTier;
    }

    /**
     * The post-material-cap difficulty tier used to keep gear scaling once its material
     * pool has ceilinged: {@code max(0, difficultyTier - materialCapLevel)}. Zero at or
     * below the cap (no bonus while the material is still improving toward netherite),
     * then climbs one-for-one above it. Feed the result to {@link #bonusFor} /
     * {@link #primaryStatBonus} so regular hostile gear keeps getting stronger past the
     * netherite plateau (see {@link ProceduralTiers#MATERIAL_CAP_LEVEL}). Pure (params
     * in) so it is unit-testable without a Minecraft bootstrap.
     */
    static int pastCapTier(int difficultyTier, int materialCapLevel) {
        return Math.max(0, difficultyTier - materialCapLevel);
    }

    /**
     * Flat amount to add to {@code stack}'s primary AIS stat at the given carriage
     * difficulty tier — pass straight to
     * {@code StatsModifier.applyStats(stack, rng, primaryStatBonus)}. Axes scale faster
     * ({@link #PER_LEVEL_AXE}); everything else uses {@link #PER_LEVEL_DEFAULT}.
     */
    public static double primaryStatBonus(ItemStack stack, int difficultyTier) {
        return bonusFor(stack.getItem() instanceof AxeItem, difficultyTier);
    }
}
