package games.brennan.dungeontrain.editor;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.InstrumentTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Instrument;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * "One of a family" loot placeholders: {@code dungeontrain:random_pottery_sherd} and
 * {@code dungeontrain:random_goat_horn}, substituted at chest spawn time by
 * {@link ContainerContentsRoller#rollItemStack} for a real vanilla item drawn from a tag.
 *
 * <p>Tags rather than hand lists, so a mod that adds sherds or horn instruments joins the pick with
 * no change here. Tag members are sorted by id before the pick, so a chest rolls the same item
 * whatever order the tag happened to load in.</p>
 *
 * <p>Also gives a plain {@code minecraft:goat_horn} entry an instrument: a loot entry cannot store
 * one, and vanilla's {@code InstrumentItem#use} fails on a horn without one, so every horn loot
 * ever placed was silent until this fill.</p>
 */
public final class RandomVariantLoot {

    private RandomVariantLoot() {}

    /**
     * A random pottery sherd from {@code minecraft:decorated_pot_sherds}, {@code count} of them
     * (capped at the stack size). {@link ItemStack#EMPTY} when the tag is empty, so the slot is
     * skipped rather than filled with a placeholder.
     */
    static ItemStack bakeSherd(long seed, int count, HolderLookup.Provider registries) {
        List<Holder<Item>> pool = sortedTag(registries, Registries.ITEM, ItemTags.DECORATED_POT_SHERDS);
        if (pool.isEmpty()) return ItemStack.EMPTY;
        Item sherd = pool.get(pickIndex(seed, pool.size())).value();
        int maxStack = new ItemStack(sherd).getMaxStackSize();
        return new ItemStack(sherd, Math.max(1, Math.min(maxStack, count)));
    }

    /** A single goat horn playing a random instrument from {@code minecraft:goat_horns}. */
    static ItemStack bakeGoatHorn(long seed, HolderLookup.Provider registries) {
        ItemStack horn = new ItemStack(Items.GOAT_HORN);
        return fillMissingInstrument(horn, seed, registries) ? horn : ItemStack.EMPTY;
    }

    /**
     * Give a goat horn with no instrument a random one. Mutates {@code stack} in place, like the
     * roller's stew and tipped-arrow fills. Returns true when the stack ends up playable (it already
     * was, or one was set); false for a non-horn or an empty instrument tag.
     */
    static boolean fillMissingInstrument(ItemStack stack, long seed, HolderLookup.Provider registries) {
        if (!stack.is(Items.GOAT_HORN)) return false;
        if (stack.has(DataComponents.INSTRUMENT)) return true;
        List<Holder<Instrument>> pool = sortedTag(registries, Registries.INSTRUMENT, InstrumentTags.GOAT_HORNS);
        if (pool.isEmpty()) return false;
        stack.set(DataComponents.INSTRUMENT, pool.get(pickIndex(seed, pool.size())));
        return true;
    }

    /** Uniform index in {@code [0, size)} for {@code seed}. Package-private for tests. */
    static int pickIndex(long seed, int size) {
        return RandomSource.create(seed).nextInt(size);
    }

    /** {@code tag}'s members sorted by registry id; empty when the registry or tag is missing. */
    static <T> List<Holder<T>> sortedTag(HolderLookup.Provider registries,
                                         ResourceKey<? extends Registry<? extends T>> registry,
                                         TagKey<T> tag) {
        Optional<List<Holder<T>>> members = registries.lookup(registry)
            .flatMap(lookup -> lookup.get(tag))
            .map(set -> set.stream()
                .filter(h -> h.unwrapKey().isPresent())
                .sorted(Comparator.comparing(RandomVariantLoot::idOf))
                .toList());
        return members.orElse(List.of());
    }

    private static <T> String idOf(Holder<T> holder) {
        return holder.unwrapKey().map(ResourceKey::location).map(ResourceLocation::toString).orElse("");
    }
}
