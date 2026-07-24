package games.brennan.dungeontrain.difficulty;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.HashMap;
import java.util.Map;

/**
 * Progression-scaled ceiling on the enchant levels DT writes onto naturally-spawned loot
 * (chest/brushable via {@code ContainerContentsRoller}, mob equipment via {@link DifficultyApplier},
 * villager trades via {@link TradeGearScaler}).
 *
 * <p>All three roll via vanilla {@code EnchantmentHelper.enchantItem(...)}, whose output level is
 * bounded only by each enchantment's {@code getMaxLevel()}. A third-party "enchant cap-remover" mod
 * raises exactly that ceiling, silently inflating DT's loot — and worst of all early, since chest
 * loot feeds a flat power up to 30 from carriage 1. This clamps each rolled enchant to a DT-owned
 * cap that grows with the difficulty tier, so loot power tracks how deep you've travelled instead of
 * whatever enchant mod is installed.</p>
 *
 * <p><b>cap = {@link #vanillaMax}(enchant) + tier / {@value #TIERS_PER_BONUS_LEVEL}.</b> The base is
 * read from {@link #VANILLA_MAX}, a hardcoded table — <b>NOT</b> the live {@code getMaxLevel()},
 * which is the very method a cap-remover mod overrides (reading it would make this clamp a no-op
 * against the mods it defends against). The clamp only ever <em>lowers</em> a level, and vanilla
 * never rolls above its own max, so with no such mod installed this is inert — normal play is
 * unchanged. Modded / unknown enchants (absent from the table) use {@link #FALLBACK_MAX}.</p>
 */
public final class EnchantLevelCap {

    /** Difficulty tiers per +1 to the enchant ceiling. tier 0–9 → +0, 10–19 → +1, … */
    static final int TIERS_PER_BONUS_LEVEL = 10;

    /** Base cap for an enchant not in {@link #VANILLA_MAX} (modded or future vanilla). */
    static final int FALLBACK_MAX = 5;

    /** Vanilla 1.21.1 enchant → its real max level, by registry id. Keyed by id (never by the
     *  mod-overridable {@code getMaxLevel()}) so a cap-remover mod can't defeat the lookup. */
    static final Map<ResourceLocation, Integer> VANILLA_MAX = buildVanillaTable();

    private EnchantLevelCap() {}

    private static Map<ResourceLocation, Integer> buildVanillaTable() {
        Map<ResourceLocation, Integer> m = new HashMap<>();
        // Armor
        put(m, "protection", 4);        put(m, "fire_protection", 4);
        put(m, "feather_falling", 4);   put(m, "blast_protection", 4);
        put(m, "projectile_protection", 4); put(m, "respiration", 3);
        put(m, "aqua_affinity", 1);     put(m, "thorns", 3);
        put(m, "depth_strider", 3);     put(m, "frost_walker", 2);
        put(m, "binding_curse", 1);     put(m, "soul_speed", 3);
        put(m, "swift_sneak", 3);
        // Sword
        put(m, "sharpness", 5);         put(m, "smite", 5);
        put(m, "bane_of_arthropods", 5); put(m, "knockback", 2);
        put(m, "fire_aspect", 2);       put(m, "looting", 3);
        put(m, "sweeping_edge", 3);
        // Tools
        put(m, "efficiency", 5);        put(m, "silk_touch", 1);
        put(m, "unbreaking", 3);        put(m, "fortune", 3);
        // Bow
        put(m, "power", 5);             put(m, "punch", 2);
        put(m, "flame", 1);             put(m, "infinity", 1);
        // Fishing
        put(m, "luck_of_the_sea", 3);   put(m, "lure", 3);
        // Trident
        put(m, "loyalty", 3);           put(m, "impaling", 5);
        put(m, "riptide", 3);           put(m, "channeling", 1);
        // Crossbow
        put(m, "multishot", 1);         put(m, "quick_charge", 3);
        put(m, "piercing", 4);
        // Mace (1.21)
        put(m, "density", 5);           put(m, "breach", 4);
        put(m, "wind_burst", 3);
        // Universal
        put(m, "mending", 1);           put(m, "vanishing_curse", 1);
        return Map.copyOf(m);
    }

    private static void put(Map<ResourceLocation, Integer> m, String path, int max) {
        m.put(ResourceLocation.withDefaultNamespace(path), max);
    }

    /**
     * The cap for one enchant at a difficulty tier: {@code vanillaMax + tier/10}. Pure; the base is
     * the hardcoded table value (or {@link #FALLBACK_MAX} for an unknown id). Negative tiers add 0.
     */
    static int capFor(ResourceLocation id, int tier) {
        int base = VANILLA_MAX.getOrDefault(id, FALLBACK_MAX);
        return base + Math.max(0, tier) / TIERS_PER_BONUS_LEVEL;
    }

    /**
     * Clamp every enchant on {@code stack} (both the worn {@code ENCHANTMENTS} and stored-book
     * {@code STORED_ENCHANTMENTS} components) to {@link #capFor} at {@code tier}. In place; returns
     * the same stack for call-site chaining. No-op when nothing exceeds its cap.
     */
    public static ItemStack clampToProgression(ItemStack stack, int tier) {
        if (stack == null || stack.isEmpty()) return stack;
        clampComponent(stack, DataComponents.ENCHANTMENTS, tier);
        clampComponent(stack, DataComponents.STORED_ENCHANTMENTS, tier);
        return stack;
    }

    private static void clampComponent(ItemStack stack,
                                       DataComponentType<ItemEnchantments> type, int tier) {
        ItemEnchantments current = stack.get(type);
        if (current == null || current.isEmpty()) return;
        ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(current);
        boolean changed = false;
        for (var entry : current.entrySet()) {
            Holder<Enchantment> ench = entry.getKey();
            int level = entry.getIntValue();
            int cap = capFor(idOf(ench), tier);
            if (level > cap) {
                mutable.set(ench, cap);
                changed = true;
            }
        }
        if (changed) stack.set(type, mutable.toImmutable());
    }

    /** The registry id of an enchant holder, or a never-matching placeholder for an unkeyed one. */
    private static ResourceLocation idOf(Holder<Enchantment> holder) {
        return holder.unwrapKey().map(ResourceKey::location)
            .orElse(ResourceLocation.withDefaultNamespace("__unkeyed__"));
    }
}
