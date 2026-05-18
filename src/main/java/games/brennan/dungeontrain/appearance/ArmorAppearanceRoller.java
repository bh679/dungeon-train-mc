package games.brennan.dungeontrain.appearance;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.armortrim.TrimMaterial;
import net.minecraft.world.item.armortrim.TrimMaterials;
import net.minecraft.world.item.armortrim.TrimPattern;
import net.minecraft.world.item.component.DyedItemColor;

import java.util.List;
import java.util.Optional;

/**
 * Cosmetic decoration for naturally-spawned armor. Sibling to
 * {@link games.brennan.dungeontrain.naming.NameComposer} — invoked from
 * {@link games.brennan.dungeontrain.editor.ContainerContentsRoller#rollItemStack}
 * with deterministically-seeded RNGs.
 *
 * <p>Two independent effects:</p>
 * <ul>
 *   <li><b>Dye</b> — 30 % chance on leather armor pieces. Colour is one of the
 *       16 vanilla {@link DyeColor}s, RGB taken from {@code getTextureDiffuseColor}
 *       (matches what a vanilla dye-on-armor crafting recipe would produce).</li>
 *   <li><b>Trim</b> — 15 % chance on any {@link ArmorItem}. Pattern is picked
 *       uniformly from the registered patterns; material is biased toward
 *       common metals (iron, copper, redstone, quartz) over rare gems.</li>
 * </ul>
 *
 * <p>Crafted gear never passes through {@code rollItemStack}, so the
 * &quot;naturally-spawned only&quot; scoping is structural, not gated by a
 * runtime check.</p>
 */
public final class ArmorAppearanceRoller {

    private static final float DYE_CHANCE  = 0.30f;
    private static final float TRIM_CHANCE = 0.15f;

    private record MaterialWeight(ResourceKey<TrimMaterial> key, int weight) {}

    private static final List<MaterialWeight> WEIGHTED_MATERIALS = List.of(
        new MaterialWeight(TrimMaterials.IRON,      3),
        new MaterialWeight(TrimMaterials.COPPER,    3),
        new MaterialWeight(TrimMaterials.REDSTONE,  3),
        new MaterialWeight(TrimMaterials.QUARTZ,    3),
        new MaterialWeight(TrimMaterials.GOLD,      2),
        new MaterialWeight(TrimMaterials.LAPIS,     2),
        new MaterialWeight(TrimMaterials.AMETHYST,  2),
        new MaterialWeight(TrimMaterials.DIAMOND,   1),
        new MaterialWeight(TrimMaterials.EMERALD,   1),
        new MaterialWeight(TrimMaterials.NETHERITE, 1)
    );

    private static final int TOTAL_MATERIAL_WEIGHT =
        WEIGHTED_MATERIALS.stream().mapToInt(MaterialWeight::weight).sum();

    private ArmorAppearanceRoller() {}

    /**
     * Roll a 30 % chance to dye leather armor with a random vanilla {@link DyeColor}.
     * No-op on non-leather items or on a failed chance roll.
     */
    public static void maybeApplyDye(ItemStack stack, RandomSource chanceRng, RandomSource valueRng) {
        if (!isLeatherArmor(stack)) return;
        if (chanceRng.nextFloat() >= DYE_CHANCE) return;

        DyeColor[] palette = DyeColor.values();
        DyeColor picked = palette[valueRng.nextInt(palette.length)];
        stack.set(DataComponents.DYED_COLOR, new DyedItemColor(picked.getTextureDiffuseColor(), true));
    }

    /**
     * Roll a 15 % chance to apply a random armor trim to any {@link ArmorItem}.
     * Pattern is uniform across registered patterns; material is weighted
     * toward common metals. No-op on non-armor items, on a failed chance roll,
     * or if the trim registries are unavailable (e.g. data pack misconfigured).
     */
    public static void maybeApplyTrim(ItemStack stack,
                                      RandomSource chanceRng,
                                      RandomSource patternRng,
                                      RandomSource materialRng,
                                      HolderLookup.Provider registries) {
        if (!(stack.getItem() instanceof ArmorItem)) return;
        if (chanceRng.nextFloat() >= TRIM_CHANCE) return;

        Optional<HolderLookup.RegistryLookup<TrimPattern>> patternLookup =
            registries.lookup(Registries.TRIM_PATTERN);
        Optional<HolderLookup.RegistryLookup<TrimMaterial>> materialLookup =
            registries.lookup(Registries.TRIM_MATERIAL);
        if (patternLookup.isEmpty() || materialLookup.isEmpty()) return;

        List<Holder.Reference<TrimPattern>> patterns =
            patternLookup.get().listElements().toList();
        if (patterns.isEmpty()) return;
        Holder<TrimPattern> pattern = patterns.get(patternRng.nextInt(patterns.size()));

        Holder<TrimMaterial> material = pickWeightedMaterial(materialLookup.get(), materialRng);
        if (material == null) return;

        stack.set(DataComponents.TRIM, new ArmorTrim(material, pattern));
    }

    private static boolean isLeatherArmor(ItemStack stack) {
        return stack.is(Items.LEATHER_HELMET)
            || stack.is(Items.LEATHER_CHESTPLATE)
            || stack.is(Items.LEATHER_LEGGINGS)
            || stack.is(Items.LEATHER_BOOTS);
    }

    private static Holder<TrimMaterial> pickWeightedMaterial(
        HolderLookup.RegistryLookup<TrimMaterial> lookup,
        RandomSource rng
    ) {
        int roll = rng.nextInt(TOTAL_MATERIAL_WEIGHT);
        int cursor = 0;
        for (MaterialWeight mw : WEIGHTED_MATERIALS) {
            cursor += mw.weight();
            if (roll < cursor) {
                return lookup.get(mw.key()).map(h -> (Holder<TrimMaterial>) h).orElse(null);
            }
        }
        return null;
    }
}
