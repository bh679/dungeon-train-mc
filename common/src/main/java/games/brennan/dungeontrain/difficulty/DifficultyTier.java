package games.brennan.dungeontrain.difficulty;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * A single rung on the difficulty progression ladder for DT carriage mobs.
 *
 * <p>Tiers are an ordered list in {@code data/dungeontrain/difficulty/tiers.json};
 * the position in that list maps to the carriage-derived tier index — there is no
 * per-tier {@code minCarriage} threshold. Tier 0 is the lowest, the last tier in
 * the list is the cap for anything past it.</p>
 *
 * <p>All weighted picks use simple sum-of-weights selection. Items are resolved
 * lazily via the vanilla item registry at apply time so a bad item id in the JSON
 * is logged-and-skipped rather than crashing on load.</p>
 */
public record DifficultyTier(
        String name,
        ArmorSet armor,
        WeaponSet weapon,
        List<EffectSpec> effects,
        EnchantSpec enchant
) {

    /** Single weighted item entry — {@code item} is a vanilla item id ("minecraft:iron_sword"). */
    public record WeightedItem(ResourceLocation item, int weight) {}

    /**
     * Per-armor-slot weighted item pools.
     *
     * <p>{@code slotChance} is rolled independently per slot (HEAD / CHEST / LEGS /
     * FEET): if it succeeds, that slot is filled with a weighted pick from the
     * matching list. Empty lists short-circuit the slot.</p>
     */
    public record ArmorSet(
            List<WeightedItem> helmet,
            List<WeightedItem> chestplate,
            List<WeightedItem> leggings,
            List<WeightedItem> boots,
            double slotChance
    ) {}

    /**
     * Mainhand weapon roll. {@code chance} is rolled once; on success a weighted
     * pick from {@code mainhand} fills the slot. Empty list short-circuits.
     */
    public record WeaponSet(
            List<WeightedItem> mainhand,
            double chance
    ) {}

    /**
     * One potion effect roll. {@code durationTicks &lt; 0} means infinite
     * ({@link Integer#MAX_VALUE}).
     */
    public record EffectSpec(
            ResourceLocation id,
            int amplifier,
            int durationTicks,
            double chance
    ) {}

    /**
     * Enchantment roll applied to each rolled equipment piece independently.
     * {@code maxLevel} corresponds to the vanilla enchanting-table level parameter
     * (0–30 typical, higher = rarer/stronger).
     */
    public record EnchantSpec(
            int maxLevel,
            double chance,
            boolean treasure
    ) {

        public static final EnchantSpec NONE = new EnchantSpec(0, 0.0, false);
    }
}
