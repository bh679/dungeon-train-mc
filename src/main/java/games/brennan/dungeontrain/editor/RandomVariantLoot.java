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
import net.minecraft.world.level.block.entity.PotDecorations;

import java.util.ArrayList;
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
 *
 * <p>And rolls the occasional loot pot's sherd sides ({@link #decoratePot}); vanilla never generates
 * a pot with sherds on it.</p>
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

    /** One in this many loot pots is decorated with sherds. */
    static final int POT_DECOR_CHANCE = 80;
    /** Once a pot is decorated, one in this many chances of each further sherd side. */
    static final int EXTRA_SIDE_CHANCE = 10;
    /** Percent chance a further side repeats the previous side's sherd rather than picking anew. */
    static final int SAME_SHERD_PERCENT = 50;
    /** {@link #rollPotSides} value for a plain (brick) side. */
    static final int PLAIN_SIDE = -1;

    /**
     * Sherd sides for a loot pot, or {@link PotDecorations#EMPTY} for the plain pot most of them stay.
     * See {@link #rollPotSides} for the odds. Empty too when the sherd tag is.
     */
    static PotDecorations decoratePot(long seed, HolderLookup.Provider registries) {
        // The chance first: the other 79 pots in 80 never touch the sherd tag.
        if (!isDecorated(RandomSource.create(seed))) return PotDecorations.EMPTY;
        List<Holder<Item>> pool = sortedTag(registries, Registries.ITEM, ItemTags.DECORATED_POT_SHERDS);
        if (pool.isEmpty()) return PotDecorations.EMPTY;
        int[] sides = rollPotSides(seed, pool.size());
        if (sides == null) return PotDecorations.EMPTY;
        return new PotDecorations(
            sideItem(pool, sides[0]), sideItem(pool, sides[1]),
            sideItem(pool, sides[2]), sideItem(pool, sides[3]));
    }

    /**
     * Sherd indices for a pot's four sides (back, left, right, front; {@link #PLAIN_SIDE} for brick),
     * or null when the pot stays plain. One pot in {@link #POT_DECOR_CHANCE} gets one random sherd on
     * a random side; each further side then comes with a one in {@link #EXTRA_SIDE_CHANCE} chance,
     * stopping at the first miss, and repeats the previous sherd {@link #SAME_SHERD_PERCENT} percent
     * of the time. Package-private for tests.
     */
    static int[] rollPotSides(long seed, int poolSize) {
        RandomSource random = RandomSource.create(seed);
        if (poolSize <= 0 || !isDecorated(random)) return null;
        int[] sides = {PLAIN_SIDE, PLAIN_SIDE, PLAIN_SIDE, PLAIN_SIDE};
        List<Integer> free = new ArrayList<>(List.of(0, 1, 2, 3));
        int sherd = random.nextInt(poolSize);
        sides[free.remove(random.nextInt(free.size()))] = sherd;
        while (!free.isEmpty() && random.nextInt(EXTRA_SIDE_CHANCE) == 0) {
            if (random.nextInt(100) >= SAME_SHERD_PERCENT) sherd = random.nextInt(poolSize);
            sides[free.remove(random.nextInt(free.size()))] = sherd;
        }
        return sides;
    }

    /** The one-in-{@link #POT_DECOR_CHANCE} draw; always the first draw from a pot's seed. */
    private static boolean isDecorated(RandomSource random) {
        return random.nextInt(POT_DECOR_CHANCE) == 0;
    }

    private static Optional<Item> sideItem(List<Holder<Item>> pool, int index) {
        return index == PLAIN_SIDE ? Optional.empty() : Optional.of(pool.get(index).value());
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
