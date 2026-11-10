package games.brennan.dungeontrain.compat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * What a piece of gear is worth to a villager, from its stats alone — tuned with Brennan on the
 * comparison page (claude.ai artifact "Gear Value Comparison"), whose defaults these constants are.
 *
 * <p>An item is scored against the iron version of the same slot: iron keeps the price Trade
 * Everything gives it today, and anything else is priced by {@code (points ÷ iron points)^curve},
 * where the curve is a power up to the diamond ratio and its tangent beyond, so past-diamond rolls
 * grow steadily instead of exploding. Durability counts as {@code √(durability left ÷ iron's max)}.
 * Material never enters directly — netherite is worth more than diamond only by how much better
 * its stats are.</p>
 *
 * <p>Enchantments that change a stat (Sharpness, Protection, Efficiency, Unbreaking …) add that
 * stat and go through the same formula, so they are worth more on better gear. The rest are a set
 * emerald price on a diamond item times a per-material multiplier. Mending is
 * {@value #MENDING_MULTIPLIER}× what the item's durability is worth.</p>
 *
 * <p>Pure: no Minecraft classes, so it is unit-tested without a bootstrap. Values are emeralds
 * (before Trade Everything's payout margin).</p>
 */
public final class GearValueFormula {

    /** Gear slots the formula prices. Each is scored against the iron item of the same slot. */
    public enum Slot {
        SWORD(Category.WEAPON), AXE(Category.TOOL), PICKAXE(Category.TOOL), SHOVEL(Category.TOOL), HOE(Category.TOOL),
        HELMET(Category.ARMOR), CHESTPLATE(Category.ARMOR), LEGGINGS(Category.ARMOR), BOOTS(Category.ARMOR);

        public final Category category;

        Slot(Category category) {
            this.category = category;
        }
    }

    public enum Category { WEAPON, TOOL, ARMOR }

    /**
     * One item's stats. {@code damage} and {@code attackSpeed} are effective (player base included),
     * {@code miningTier} is 0 wood/gold, 1 stone, 2 iron, 3 diamond/netherite. {@code material} is the
     * vanilla prefix ({@code diamond}, {@code leather} …) for the enchantment multiplier.
     */
    public record Stats(Slot slot, String material, double damage, double attackSpeed, double miningSpeed,
                        int miningTier, double armor, double toughness, double knockbackResistance,
                        double durability, double maxDurability, boolean fireImmune) {

        Stats withDamage(double v) {
            return new Stats(slot, material, v, attackSpeed, miningSpeed, miningTier, armor, toughness,
                knockbackResistance, durability, maxDurability, fireImmune);
        }

        Stats withMiningSpeed(double v) {
            return new Stats(slot, material, damage, attackSpeed, v, miningTier, armor, toughness,
                knockbackResistance, durability, maxDurability, fireImmune);
        }

        Stats withArmor(double v) {
            return new Stats(slot, material, damage, attackSpeed, miningSpeed, miningTier, v, toughness,
                knockbackResistance, durability, maxDurability, fireImmune);
        }

        Stats withDurability(double left, double max) {
            return new Stats(slot, material, damage, attackSpeed, miningSpeed, miningTier, armor, toughness,
                knockbackResistance, left, max, fireImmune);
        }
    }

    /** The iron and diamond items of a slot, and iron's current Trade Everything price in emeralds. */
    public record Reference(Stats iron, Stats diamond, double ironEmeralds) {}

    // ---- stat curve ----
    /** Curve exponent for swords. */
    static final double WEAPON_CURVE = 5;
    /** Curve exponent for pickaxes, axes, shovels and hoes. */
    static final double TOOL_CURVE = 4;
    /** Curve exponent for armour. */
    static final double ARMOR_CURVE = 2;
    /** Tool points per point of mining speed. */
    static final double MINING_SPEED_POINTS = 0.3;
    /** Tool points per mining tier. */
    static final double MINING_TIER_POINTS = 1;
    /** Armour value bonus per point of toughness. */
    static final double TOUGHNESS_BONUS = 0.15;
    /** Armour value bonus per 1.0 knockback resistance (netherite's 0.1 → +20%). */
    static final double KNOCKBACK_RESISTANCE_BONUS = 2;
    /** Multiplier for items that don't burn. */
    static final double FIRE_IMMUNE_BONUS = 1.1;

    // ---- enchantments ----
    /** Share of carriage mobs that are undead, for Smite. */
    static final double UNDEAD_SHARE = 0.5;
    /** Share of carriage mobs that are arthropods, for Bane of Arthropods. */
    static final double ARTHROPOD_SHARE = 0.15;
    /** Mending is worth this many times the item's durability worth. */
    static final double MENDING_MULTIPLIER = 10;
    /** Value Curse of Binding takes off the total. */
    static final double BINDING_PENALTY = 0.25;
    /** Value Curse of Vanishing takes off the total. */
    static final double VANISHING_PENALTY = 0.15;
    /** Trade Everything's value of a plain enchanted book (UNCOMMON rarity, 8 sixteenths). */
    static final double ENCHANTED_BOOK_EMERALDS = 0.5;
    /** Levels past this are priced as this — command-given Sharpness 255 must not overflow. */
    static final int MAX_PRICED_LEVEL = 50;

    /** Set-price enchantments are multiplied by this for the item's material (diamond = 1). */
    static final Map<String, Double> MATERIAL_MULTIPLIER = Map.of(
        "wooden", 0.15, "leather", 0.15,
        "stone", 0.3, "chainmail", 0.3, "golden", 0.3,
        "iron", 0.5, "diamond", 1.0, "netherite", 1.5);
    /** Multiplier for a material the table doesn't know (modded gear, turtle helmet). */
    static final double DEFAULT_MATERIAL_MULTIPLIER = 0.5;

    private static final Set<Slot> MELEE = EnumSet.of(Slot.SWORD, Slot.AXE);
    private static final Set<Slot> SWORD = EnumSet.of(Slot.SWORD);
    private static final Set<Slot> DIGGERS = EnumSet.of(Slot.PICKAXE, Slot.AXE, Slot.SHOVEL, Slot.HOE);
    private static final Set<Slot> ARMOR = EnumSet.of(Slot.HELMET, Slot.CHESTPLATE, Slot.LEGGINGS, Slot.BOOTS);
    private static final Set<Slot> EVERY = EnumSet.allOf(Slot.class);

    /** How one enchantment adds value. Exactly one of the effect fields is used. */
    record Enchant(Set<Slot> on, Function<Integer, Double> damage, Function<Integer, Double> miningSpeed,
                   Function<Integer, Double> armor, double[] flat, boolean unbreaking, boolean mending, double curse) {

        static Enchant damage(Set<Slot> on, Function<Integer, Double> f) {
            return new Enchant(on, f, null, null, null, false, false, 0);
        }

        static Enchant mining(Set<Slot> on, Function<Integer, Double> f) {
            return new Enchant(on, null, f, null, null, false, false, 0);
        }

        static Enchant armor(Set<Slot> on, Function<Integer, Double> f) {
            return new Enchant(on, null, null, f, null, false, false, 0);
        }

        static Enchant flat(Set<Slot> on, double... byLevel) {
            return new Enchant(on, null, null, null, byLevel, false, false, 0);
        }

        static Enchant curse(Set<Slot> on, double penalty) {
            return new Enchant(on, null, null, null, null, false, false, penalty);
        }

        /** Set price on a diamond item at {@code level}; past the table, each level adds the last step. */
        double flatAt(int level) {
            int n = flat.length;
            if (level <= n) return flat[level - 1];
            double step = n > 1 ? flat[n - 1] - flat[n - 2] : flat[0];
            return flat[n - 1] + step * (level - n);
        }
    }

    /** Vanilla enchantments by id path. Anything else (modded) adds nothing. */
    static final Map<String, Enchant> ENCHANTS = Map.ofEntries(
        Map.entry("sharpness", Enchant.damage(MELEE, l -> 0.5 * l + 0.5)),
        Map.entry("smite", Enchant.damage(MELEE, l -> 2.5 * l * UNDEAD_SHARE)),
        Map.entry("bane_of_arthropods", Enchant.damage(MELEE, l -> 2.5 * l * ARTHROPOD_SHARE)),
        Map.entry("fire_aspect", Enchant.flat(SWORD, 4.9, 8.8)),
        Map.entry("sweeping_edge", Enchant.flat(SWORD, 2.9, 4.9, 6.8)),
        Map.entry("knockback", Enchant.flat(SWORD, 3, 5)),
        Map.entry("looting", Enchant.flat(SWORD, 2.9, 4.4, 5.8)),
        Map.entry("efficiency", Enchant.mining(DIGGERS, l -> (double) l * l + 1)),
        Map.entry("fortune", Enchant.flat(DIGGERS, 4.1, 8.2, 12.3)),
        Map.entry("silk_touch", Enchant.flat(DIGGERS, 48)),
        Map.entry("protection", Enchant.armor(ARMOR, l -> (double) l)),
        Map.entry("fire_protection", Enchant.armor(ARMOR, l -> 0.5 * l)),
        Map.entry("blast_protection", Enchant.armor(ARMOR, l -> 0.5 * l)),
        Map.entry("projectile_protection", Enchant.armor(ARMOR, l -> 0.5 * l)),
        Map.entry("thorns", Enchant.armor(ARMOR, l -> 0.3 * l)),
        Map.entry("respiration", Enchant.flat(EnumSet.of(Slot.HELMET), 2, 4, 6)),
        Map.entry("aqua_affinity", Enchant.flat(EnumSet.of(Slot.HELMET), 4)),
        Map.entry("swift_sneak", Enchant.flat(EnumSet.of(Slot.LEGGINGS), 4, 8, 12)),
        Map.entry("feather_falling", Enchant.armor(EnumSet.of(Slot.BOOTS), l -> 0.45 * l)),
        Map.entry("depth_strider", Enchant.flat(EnumSet.of(Slot.BOOTS), 2, 4, 6)),
        Map.entry("frost_walker", Enchant.flat(EnumSet.of(Slot.BOOTS), 4, 6)),
        Map.entry("soul_speed", Enchant.flat(EnumSet.of(Slot.BOOTS), 3, 5, 8)),
        Map.entry("unbreaking", new Enchant(EVERY, null, null, null, null, true, false, 0)),
        Map.entry("mending", new Enchant(EVERY, null, null, null, null, false, true, 0)),
        Map.entry("binding_curse", Enchant.curse(ARMOR, BINDING_PENALTY)),
        Map.entry("vanishing_curse", Enchant.curse(EVERY, VANISHING_PENALTY)));

    private GearValueFormula() {}

    /** The item's value in emeralds with its enchantments (keyed by vanilla id path, e.g. {@code sharpness}). */
    public static double emeralds(Stats item, Reference ref, Map<String, Integer> enchants) {
        Stats boosted = item;
        double flat = 0;
        double keep = 1;
        boolean mending = false;
        for (Map.Entry<String, Integer> e : enchants.entrySet()) {
            Enchant ench = ENCHANTS.get(e.getKey());
            int level = Math.min(e.getValue(), MAX_PRICED_LEVEL);
            if (ench == null || level <= 0) continue;
            if (ench.curse() > 0) {
                keep *= 1 - ench.curse();
                continue;
            }
            if (!ench.on().contains(item.slot())) continue;
            if (ench.damage() != null) boosted = boosted.withDamage(boosted.damage() + ench.damage().apply(level));
            if (ench.miningSpeed() != null) boosted = boosted.withMiningSpeed(boosted.miningSpeed() + ench.miningSpeed().apply(level));
            if (ench.armor() != null) boosted = boosted.withArmor(boosted.armor() + ench.armor().apply(level));
            if (ench.unbreaking()) {
                double m = unbreakingMultiplier(level, item.slot().category == Category.ARMOR);
                boosted = boosted.withDurability(boosted.durability() * m, boosted.maxDurability() * m);
            }
            if (ench.flat() != null) flat += ench.flatAt(level) * materialMultiplier(item.material());
            if (ench.mending()) mending = true;
        }
        // Mending scales with every stat the item ends up with, stat enchantments included.
        double mendingValue = mending ? MENDING_MULTIPLIER * durabilityWorth(boosted, ref) : 0;
        return (statEmeralds(boosted, ref) + flat + mendingValue) * keep;
    }

    /**
     * An enchanted book: the plain book plus, for each enchantment, the most it adds to any of the
     * netherite items it fits ({@code netherite} gives that slot's netherite stats and reference).
     */
    public static double bookEmeralds(Map<String, Integer> enchants, Function<Slot, Map.Entry<Stats, Reference>> netherite) {
        double total = ENCHANTED_BOOK_EMERALDS;
        double keep = 1;
        for (Map.Entry<String, Integer> e : enchants.entrySet()) {
            Enchant ench = ENCHANTS.get(e.getKey());
            if (ench == null || e.getValue() <= 0) continue;
            if (ench.curse() > 0) {
                keep *= 1 - ench.curse();
                continue;
            }
            double best = 0;
            for (Slot slot : ench.on()) {
                Map.Entry<Stats, Reference> item = netherite.apply(slot);
                if (item == null) continue;
                double plain = emeralds(item.getKey(), item.getValue(), Map.of());
                double with = emeralds(item.getKey(), item.getValue(), Map.of(e.getKey(), e.getValue()));
                best = Math.max(best, with - plain);
            }
            total += best;
        }
        return total * keep;
    }

    /** The item's stats alone, no enchantments. */
    public static double statEmeralds(Stats item, Reference ref) {
        double core = switch (item.slot().category) {
            case WEAPON, TOOL -> {
                double ip = weaponPoints(ref.iron());
                double dp = weaponPoints(ref.diamond());
                double curve = item.slot().category == Category.WEAPON ? WEAPON_CURVE : TOOL_CURVE;
                yield bend(weaponPoints(item) / ip, dp / ip, curve);
            }
            case ARMOR -> {
                double ip = ref.iron().armor();
                double dp = ref.diamond().armor();
                yield bend(item.armor() / ip, dp / ip, ARMOR_CURVE)
                    * (1 + TOUGHNESS_BONUS * item.toughness())
                    * (1 + KNOCKBACK_RESISTANCE_BONUS * item.knockbackResistance());
            }
        };
        double durability = Math.sqrt(Math.max(0, item.durability()) / ref.iron().maxDurability());
        return ref.ironEmeralds() * core * durability * (item.fireImmune() ? FIRE_IMMUNE_BONUS : 1);
    }

    /** What the item's durability is worth: its price at full durability minus at 1 durability left. */
    static double durabilityWorth(Stats item, Reference ref) {
        double full = statEmeralds(item.withDurability(item.maxDurability(), item.maxDurability()), ref);
        double broken = statEmeralds(item.withDurability(1, item.maxDurability()), ref);
        return Math.max(0, full - broken);
    }

    static double weaponPoints(Stats s) {
        double dps = s.damage() * s.attackSpeed();
        if (s.slot().category != Category.TOOL) return dps;
        return dps + MINING_SPEED_POINTS * s.miningSpeed() + MINING_TIER_POINTS * s.miningTier();
    }

    /** Power curve up to the diamond ratio {@code rd}, its tangent beyond. */
    static double bend(double r, double rd, double power) {
        if (r <= rd) return Math.pow(Math.max(r, 0), power);
        return Math.pow(rd, power) + power * Math.pow(rd, power - 1) * (r - rd);
    }

    /** Vanilla Unbreaking: tools skip 1 in (level+1) hits; armour only 60% + 40%/(level+1) of them. */
    static double unbreakingMultiplier(int level, boolean armor) {
        return armor ? 1 / (0.6 + 0.4 / (level + 1)) : level + 1;
    }

    static double materialMultiplier(String material) {
        return MATERIAL_MULTIPLIER.getOrDefault(material, DEFAULT_MATERIAL_MULTIPLIER);
    }
}
