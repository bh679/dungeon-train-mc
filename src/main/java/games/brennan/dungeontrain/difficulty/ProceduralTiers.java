package games.brennan.dungeontrain.difficulty;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates a {@link DifficultyTier} on the fly for any non-negative level,
 * replacing the prior 5-rung data-driven {@code tiers.json} ladder with a
 * smooth procedural curve.
 *
 * <p>Levels 1–100 ramp through five armor and five weapon material bands
 * (leather → chainmail → iron → diamond → netherite). Bands overlap so
 * transitions are gradual: at level 2 you see rare leather; by level 10
 * leather is common and chainmail starts appearing rarely; by level 100
 * everything is netherite. Golden gear sprinkles in at a constant low
 * weight as a flavor "rare drop" across all levels.</p>
 *
 * <p>Above level 100 the gear pools stay pinned to netherite. Additional
 * potion effects layer in at progressively higher levels, and effect
 * amplifiers tick up every 50 levels — keeping post-cap progression
 * meaningful well beyond level 200.</p>
 */
public final class ProceduralTiers {

    /** Sentinel for "never fades / never caps." */
    private static final int TOP = Integer.MAX_VALUE;

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

    // Armor material progression. Each material peaks for ~20 levels
    // and overlaps with the next by ~10–15 levels.
    private static final MaterialCurve LEATHER   = new MaterialCurve(1,   5,  15,  30);
    private static final MaterialCurve CHAINMAIL = new MaterialCurve(10, 20,  35,  55);
    private static final MaterialCurve IRON      = new MaterialCurve(25, 40,  60,  80);
    private static final MaterialCurve DIAMOND   = new MaterialCurve(50, 70,  90, TOP);
    private static final MaterialCurve NETHERITE = new MaterialCurve(75,100, TOP, TOP);

    // Weapons. Same shape, slightly earlier ramps (you arm before you
    // armor up in DT progression).
    private static final MaterialCurve WOOD_W       = new MaterialCurve(1,   5,  10,  20);
    private static final MaterialCurve STONE_W      = new MaterialCurve(10, 20,  30,  45);
    private static final MaterialCurve IRON_W       = new MaterialCurve(25, 40,  55,  75);
    private static final MaterialCurve DIAMOND_W    = new MaterialCurve(50, 70,  85, TOP);
    private static final MaterialCurve NETHERITE_W  = new MaterialCurve(75,100, TOP, TOP);

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
     * the prior data-driven tiers; the remainder kick in above level 100
     * to keep difficulty climbing once gear is maxed at netherite.
     */
    private static final List<EffectCurve> EFFECTS = List.of(
        new EffectCurve("minecraft:speed",            30,  50, 30, 0.80),
        new EffectCurve("minecraft:strength",         50,  50, 40, 0.90),
        new EffectCurve("minecraft:resistance",       70,  50, 50, 0.80),
        new EffectCurve("minecraft:fire_resistance",  80, TOP, 40, 0.70),
        new EffectCurve("minecraft:regeneration",    100,  50, 50, 0.70),
        new EffectCurve("minecraft:jump_boost",      120,  50, 50, 0.60),
        new EffectCurve("minecraft:haste",           140,  50, 50, 0.60),
        new EffectCurve("minecraft:absorption",      160,  50, 50, 0.70),
        new EffectCurve("minecraft:slow_falling",    180, TOP, 50, 0.50)
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

    private static DifficultyTier.ArmorSet armorSet(int level) {
        double slotChance = Math.min(1.0, level / 25.0);
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

        double chance = Math.min(1.0, level / 20.0);
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
        // that for post-cap (level > 100) progression.
        int maxLevel = Math.min(50, level / 2);
        double chance = Math.min(1.0, level / 75.0);
        return new DifficultyTier.EnchantSpec(maxLevel, chance, false);
    }

    private static void addIfPositive(List<DifficultyTier.WeightedItem> out, String itemId, int weight) {
        if (weight <= 0) return;
        out.add(new DifficultyTier.WeightedItem(ResourceLocation.parse(itemId), weight));
    }
}
