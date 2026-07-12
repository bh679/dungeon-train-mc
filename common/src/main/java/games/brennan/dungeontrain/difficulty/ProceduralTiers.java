package games.brennan.dungeontrain.difficulty;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates a {@link DifficultyTier} on the fly for any non-negative level,
 * replacing the prior 5-rung data-driven {@code tiers.json} ladder with a
 * smooth procedural curve.
 *
 * <p>Levels 1–50 ramp through five armor and five weapon material bands
 * (leather → chainmail → iron → diamond → netherite). Bands overlap so
 * transitions are gradual: at level 1 you see rare leather; by level 5
 * leather is common and chainmail starts appearing rarely; by level 50
 * everything is netherite. Golden gear sprinkles in at a constant low
 * weight as a flavor "rare drop" across all levels.</p>
 *
 * <p>Above level 50 the gear pools stay pinned to netherite. Additional
 * potion effects layer in at progressively higher levels, and effect
 * amplifiers tick up every 25 levels — keeping post-cap progression
 * meaningful well beyond level 100.</p>
 */
public final class ProceduralTiers {

    /** Sentinel for "never fades / never caps." */
    private static final int TOP = Integer.MAX_VALUE;

    /**
     * Difficulty level at which the armor/weapon <em>material</em> pool reaches its
     * ceiling — netherite full weight, i.e. {@code NETHERITE.fullStart}. Gear material
     * does not improve beyond this level (the pool stays a diamond+netherite mix
     * forever). Downstream scaling anchors here: {@link ItemStatLevelScaling#pastCapTier}
     * uses it so per-tier stat bonuses only take over once material has capped, letting
     * hostile gear keep getting stronger past the plateau. Keep in sync with
     * {@code NETHERITE.fullStart} below.
     */
    public static final int MATERIAL_CAP_LEVEL = 50;

    /**
     * Trapezoidal weight curve for one armor or weapon material.
     *
     * <p>The weight ramps from 5 at {@code rampStart} up to 100 at
     * {@code fullStart}, holds at 100 until {@code fullEnd}, and ramps
     * back to 0 by {@code rampEnd}. {@code rampEnd == TOP} = top tier;
     * weight stays at 100 forever once peak is reached.</p>
     */
    private record MaterialCurve(int rampStart, int fullStart, int fullEnd, int rampEnd) {
        int weight(int level) {
            if (level < rampStart) return 0;
            if (level < fullStart) {
                int span = fullStart - rampStart;
                if (span <= 0) return 100;
                return 5 + (95 * (level - rampStart)) / span;
            }
            if (level <= fullEnd) return 100;
            if (rampEnd == TOP) return 100;
            if (level < rampEnd) {
                int span = rampEnd - fullEnd;
                if (span <= 0) return 0;
                return Math.max(1, 100 - (100 * (level - fullEnd)) / span);
            }
            return 0;
        }
    }

    // Armor material progression. Each material peaks for ~10 levels and
    // overlaps with the next by ~5–8 levels. Halved from the original
    // 100-level scale so netherite caps at level 50.
    private static final MaterialCurve LEATHER   = new MaterialCurve(1,   3,   8,  15);
    private static final MaterialCurve CHAINMAIL = new MaterialCurve(5,  10,  18,  28);
    private static final MaterialCurve IRON      = new MaterialCurve(13, 20,  30,  40);
    private static final MaterialCurve DIAMOND   = new MaterialCurve(25, 35,  45, TOP);
    private static final MaterialCurve NETHERITE = new MaterialCurve(38, 50, TOP, TOP);

    // Weapons. Same shape, slightly earlier ramps (you arm before you
    // armor up in DT progression).
    private static final MaterialCurve WOOD_W       = new MaterialCurve(1,   3,   5,  10);
    private static final MaterialCurve STONE_W      = new MaterialCurve(5,  10,  15,  23);
    private static final MaterialCurve IRON_W       = new MaterialCurve(13, 20,  28,  38);
    private static final MaterialCurve DIAMOND_W    = new MaterialCurve(25, 35,  43, TOP);
    private static final MaterialCurve NETHERITE_W  = new MaterialCurve(38, 50, TOP, TOP);

    /** Golden gear stays at a constant low weight at every level — flavor sprinkle. */
    private static final int GOLDEN_WEIGHT = 2;

    /**
     * Potion effect curve. {@code startLevel} is where the effect first
     * appears at any chance > 0; {@code ampEveryLevels} = how many levels
     * between amplifier increments (or {@link #TOP} to lock at amp 0).
     * {@code chanceRampLevels} = how many levels to climb from 0 to
     * {@code maxChance}.
     */
    private record EffectCurve(String id, int startLevel, int ampEveryLevels, int chanceRampLevels, double maxChance) {
        int amplifier(int level) {
            if (ampEveryLevels == TOP) return 0;
            return Math.max(0, (level - startLevel) / ampEveryLevels);
        }
        double chance(int level) {
            if (level < startLevel) return 0.0;
            double progress = (double) (level - startLevel) / chanceRampLevels;
            return Math.min(maxChance, progress * maxChance);
        }
    }

    /**
     * Effects in order of when they enter the pool. The first four mirror
     * the prior data-driven tiers; the remainder kick in above level 50
     * to keep difficulty climbing once gear is maxed at netherite. All
     * thresholds halved from the original 100-level scale.
     */
    private static final List<EffectCurve> EFFECTS = List.of(
        new EffectCurve("minecraft:speed",           15,  25, 15, 0.80),
        new EffectCurve("minecraft:strength",        25,  25, 20, 0.90),
        new EffectCurve("minecraft:resistance",      35,  25, 25, 0.80),
        new EffectCurve("minecraft:fire_resistance", 40, TOP, 20, 0.70),
        new EffectCurve("minecraft:regeneration",    50,  25, 25, 0.70),
        new EffectCurve("minecraft:jump_boost",      60,  25, 25, 0.60),
        new EffectCurve("minecraft:haste",           70,  25, 25, 0.60),
        new EffectCurve("minecraft:absorption",      80,  25, 25, 0.70),
        new EffectCurve("minecraft:slow_falling",    90, TOP, 25, 0.50)
    );

    private ProceduralTiers() {}

    /**
     * Build a tier for the given level. {@code level <= 0} returns
     * {@code null} (callers treat that as vanilla baseline — no gear, no
     * effects).
     */
    public static DifficultyTier tierFor(int level) {
        if (level <= 0) return null;
        return new DifficultyTier(
            "level_" + level,
            armorSet(level),
            weaponSet(level),
            effectsAt(level),
            enchantAt(level)
        );
    }

    /**
     * Dominant mob weapon material at {@code level}, as a stage rank:
     * 0 = none ({@code level <= 0}), 1 = wood, 2 = stone, 3 = iron, 4 = diamond,
     * 5 = netherite. "Dominant" = the highest-weight material in the weapon pool at
     * that level — the band shown in the difficulty chart. Ties resolve to the lower
     * material (diamond over netherite at their shared cap), which is irrelevant to
     * callers that clamp at diamond.
     *
     * <p>Used by {@code VillagerTrainSpawnEvents} to pair the villager trade-level
     * cap to the mob weapon stage, so the two progressions stay in lockstep even if
     * the material curves below are retuned.</p>
     */
    public static int dominantWeaponStage(int level) {
        if (level <= 0) return 0;
        int[] weights = {
            WOOD_W.weight(level),
            STONE_W.weight(level),
            IRON_W.weight(level),
            DIAMOND_W.weight(level),
            NETHERITE_W.weight(level),
        };
        int rank = 1;
        int best = weights[0];
        for (int i = 1; i < weights.length; i++) {
            if (weights[i] > best) {
                best = weights[i];
                rank = i + 1;
            }
        }
        return rank;
    }

    private static DifficultyTier.ArmorSet armorSet(int level) {
        double slotChance = Math.min(1.0, level / 12.5);
        return new DifficultyTier.ArmorSet(
            armorPool(level, "helmet"),
            armorPool(level, "chestplate"),
            armorPool(level, "leggings"),
            armorPool(level, "boots"),
            slotChance
        );
    }

    private static List<DifficultyTier.WeightedItem> armorPool(int level, String slotSuffix) {
        List<DifficultyTier.WeightedItem> out = new ArrayList<>();
        addIfPositive(out, "minecraft:leather_"   + slotSuffix, LEATHER.weight(level));
        addIfPositive(out, "minecraft:chainmail_" + slotSuffix, CHAINMAIL.weight(level));
        addIfPositive(out, "minecraft:iron_"      + slotSuffix, IRON.weight(level));
        addIfPositive(out, "minecraft:diamond_"   + slotSuffix, DIAMOND.weight(level));
        addIfPositive(out, "minecraft:netherite_" + slotSuffix, NETHERITE.weight(level));
        addIfPositive(out, "minecraft:golden_"    + slotSuffix, GOLDEN_WEIGHT);
        return out;
    }

    private static DifficultyTier.WeaponSet weaponSet(int level) {
        List<DifficultyTier.WeightedItem> pool = new ArrayList<>();
        // Swords carry the full material weight.
        addIfPositive(pool, "minecraft:wooden_sword",    WOOD_W.weight(level));
        addIfPositive(pool, "minecraft:stone_sword",     STONE_W.weight(level));
        addIfPositive(pool, "minecraft:iron_sword",      IRON_W.weight(level));
        addIfPositive(pool, "minecraft:diamond_sword",   DIAMOND_W.weight(level));
        addIfPositive(pool, "minecraft:netherite_sword", NETHERITE_W.weight(level));
        addIfPositive(pool, "minecraft:golden_sword",    GOLDEN_WEIGHT);
        // Axes carry half weight — present but less common than swords.
        addIfPositive(pool, "minecraft:wooden_axe",    WOOD_W.weight(level)      / 2);
        addIfPositive(pool, "minecraft:stone_axe",     STONE_W.weight(level)     / 2);
        addIfPositive(pool, "minecraft:iron_axe",      IRON_W.weight(level)      / 2);
        addIfPositive(pool, "minecraft:diamond_axe",   DIAMOND_W.weight(level)   / 2);
        addIfPositive(pool, "minecraft:netherite_axe", NETHERITE_W.weight(level) / 2);

        double chance = Math.min(1.0, level / 10.0);
        return new DifficultyTier.WeaponSet(pool, chance);
    }

    private static List<DifficultyTier.EffectSpec> effectsAt(int level) {
        List<DifficultyTier.EffectSpec> out = new ArrayList<>();
        for (EffectCurve ec : EFFECTS) {
            double chance = ec.chance(level);
            if (chance <= 0) continue;
            out.add(new DifficultyTier.EffectSpec(
                ResourceLocation.parse(ec.id()),
                ec.amplifier(level),
                -1, // infinite duration
                chance
            ));
        }
        return out;
    }

    private static DifficultyTier.EnchantSpec enchantAt(int level) {
        // Vanilla enchanting-table levels cap at 30; we let it climb past
        // that for post-cap (level > 50) progression.
        int maxLevel = Math.min(50, level);
        double chance = Math.min(1.0, level / 37.5);
        return new DifficultyTier.EnchantSpec(maxLevel, chance, false);
    }

    private static void addIfPositive(List<DifficultyTier.WeightedItem> out, String itemId, int weight) {
        if (weight <= 0) return;
        out.add(new DifficultyTier.WeightedItem(ResourceLocation.parse(itemId), weight));
    }
}
