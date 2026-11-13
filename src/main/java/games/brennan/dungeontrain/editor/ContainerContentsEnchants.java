package games.brennan.dungeontrain.editor;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The enchantment side of a book {@link ContainerContentsEntry}: reading the stored enchantments off
 * an enchanted book an author adds from their hand, and enchanting the book the roller spawns with an
 * entry's {@link BookEnchants}.
 */
public final class ContainerContentsEnchants {

    private ContainerContentsEnchants() {}

    /** True for the two items a {@link BookEnchants} applies to. */
    public static boolean isBook(ItemStack stack) {
        return stack.is(Items.BOOK) || stack.is(Items.ENCHANTED_BOOK);
    }

    private static final ResourceLocation BOOK_ID = ResourceLocation.withDefaultNamespace("book");
    private static final ResourceLocation ENCHANTED_BOOK_ID = ResourceLocation.withDefaultNamespace("enchanted_book");

    /** True for the two item ids a {@link BookEnchants} applies to: the menu offers the range only on these. */
    public static boolean isBookId(ResourceLocation itemId) {
        return BOOK_ID.equals(itemId) || ENCHANTED_BOOK_ID.equals(itemId);
    }

    /**
     * {@code entry} with its random-enchantment range set to {@code min}..{@code max}, its exact
     * enchantments kept. On, the range starts at 1 and replaces the ordinary random-enchant roll,
     * so the entry's own toggle goes off; max 0 turns the range off. Not a book: unchanged.
     */
    public static ContainerContentsEntry withRandomRange(ContainerContentsEntry entry, int min, int max) {
        if (!isBookId(entry.itemId())) return entry;
        int hi = Math.max(0, Math.min(BookEnchants.MAX_RANDOM, max));
        int lo = hi == 0 ? 0 : Math.max(1, Math.min(hi, min));
        BookEnchants current = entry.enchants() == null ? BookEnchants.exact(Map.of()) : entry.enchants();
        ContainerContentsEntry next = entry.withEnchants(current.withRandom(lo, hi));
        return hi > 0 ? next.withRandomEnchantment(false) : next;
    }

    /** The stored enchantments on a held enchanted book, or {@code null} for anything else. */
    public static @Nullable BookEnchants enchantsOf(ItemStack stack) {
        if (stack == null || !stack.is(Items.ENCHANTED_BOOK)) return null;
        ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored == null || stored.isEmpty()) return null;
        Map<ResourceLocation, Integer> exact = new LinkedHashMap<>();
        for (var entry : stored.entrySet()) {
            entry.getKey().unwrapKey().ifPresent(key -> exact.put(key.location(), entry.getIntValue()));
        }
        BookEnchants enchants = BookEnchants.exact(exact);
        return enchants.isEmpty() ? null : enchants;
    }

    /**
     * {@code stack} enchanted as {@code enchants} says: a plain book becomes one enchanted book carrying
     * the exact enchantments plus the random ones rolled from {@code rng}. Returns {@code stack}
     * untouched when there is nothing to apply or it is not a book. Ids that do not resolve in
     * {@code registries} are skipped, so a removed mod's enchantment never crashes a roll.
     */
    public static ItemStack apply(ItemStack stack, @Nullable BookEnchants enchants,
                                  RandomSource rng, HolderLookup.Provider registries) {
        if (enchants == null || enchants.isEmpty() || stack.isEmpty() || !isBook(stack)) return stack;
        Optional<HolderLookup.RegistryLookup<Enchantment>> lookup = registries.lookup(Registries.ENCHANTMENT);
        if (lookup.isEmpty()) return stack;

        ItemEnchantments.Mutable stored = new ItemEnchantments.Mutable(
            stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY));
        enchants.exact().forEach((id, level) -> lookup.get()
            .get(ResourceKey.create(Registries.ENCHANTMENT, id))
            .ifPresent(holder -> stored.set(holder, level)));
        if (enchants.hasRandom()) {
            int n = enchants.randomMin() + rng.nextInt(enchants.randomMax() - enchants.randomMin() + 1);
            for (Holder<Enchantment> holder : rollRandom(lookup.get(), enchants, stored.keySet(), n, rng)) {
                int top = holder.value().getMaxLevel();
                int lo = enchants.levelLow(top);
                int hi = enchants.levelHigh(top);
                stored.set(holder, lo >= hi ? hi : lo + rng.nextInt(hi - lo + 1));
            }
        }
        if (stored.keySet().isEmpty()) return stack;

        ItemStack book = stack.is(Items.ENCHANTED_BOOK) ? stack.copyWithCount(1)
            : stack.transmuteCopy(Items.ENCHANTED_BOOK, 1);
        book.set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());
        return book;
    }

    /**
     * Every enchantment a book's random roll could pick, and so every row of the menu's list:
     * {@code #minecraft:on_random_loot} (so other mods' enchantments come along) plus the curses, which
     * a builder may switch on. Sorted by id, as tag order follows datapack load order and the same
     * seed must always roll the same book.
     */
    public static List<Holder<Enchantment>> listCandidates(HolderLookup.RegistryLookup<Enchantment> lookup) {
        Map<ResourceLocation, Holder<Enchantment>> byId = new TreeMap<>();
        for (var tag : List.of(EnchantmentTags.ON_RANDOM_LOOT, EnchantmentTags.CURSE)) {
            lookup.get(tag).ifPresent(set -> {
                for (Holder<Enchantment> holder : set) {
                    holder.unwrapKey().ifPresent(key -> byId.putIfAbsent(key.location(), holder));
                }
            });
        }
        return List.copyOf(byId.values());
    }

    /** The rule for a pool saved before the list existed: everything but curses and Mending. */
    public static boolean onByDefault(Holder<Enchantment> holder) {
        return !holder.is(EnchantmentTags.CURSE) && !holder.is(Enchantments.MENDING);
    }

    /** Whether {@code enchants}' random roll may pick {@code holder}; undecided ones never roll. */
    public static boolean allowed(BookEnchants enchants, Holder<Enchantment> holder) {
        if (enchants.list() == null) return onByDefault(holder);
        return holder.unwrapKey()
            .map(key -> Boolean.TRUE.equals(enchants.list().get(key.location())))
            .orElse(false);
    }

    /** {@code candidates} the builder has not decided on yet; none while the pool keeps the old rule. */
    public static List<ResourceLocation> undecided(BookEnchants enchants, List<Holder<Enchantment>> candidates) {
        if (enchants.list() == null) return List.of();
        List<ResourceLocation> out = new ArrayList<>();
        for (Holder<Enchantment> holder : candidates) {
            holder.unwrapKey().map(ResourceKey::location)
                .filter(id -> !enchants.list().containsKey(id))
                .ifPresent(out::add);
        }
        return out;
    }

    /**
     * The list as decisions for every candidate: a saved list is copied as is; the old rule is written
     * out. Every toggle starts from this, so the first edit of an old pool records the whole list.
     */
    public static Map<ResourceLocation, Boolean> decisions(BookEnchants enchants, List<Holder<Enchantment>> candidates) {
        if (enchants.list() != null) return new TreeMap<>(enchants.list());
        Map<ResourceLocation, Boolean> out = new TreeMap<>();
        for (Holder<Enchantment> holder : candidates) {
            holder.unwrapKey().ifPresent(key -> out.put(key.location(), onByDefault(holder)));
        }
        return out;
    }

    /** {@code enchants} with {@code id} switched; an undecided one switches on. */
    public static BookEnchants toggled(BookEnchants enchants, ResourceLocation id, List<Holder<Enchantment>> candidates) {
        Map<ResourceLocation, Boolean> list = decisions(enchants, candidates);
        list.put(id, !Boolean.TRUE.equals(list.get(id)));
        return enchants.withList(list);
    }

    /** {@code enchants} with every candidate on (curses excepted, they stay as they were) or every one off. */
    public static BookEnchants allSet(BookEnchants enchants, boolean on, List<Holder<Enchantment>> candidates) {
        Map<ResourceLocation, Boolean> list = decisions(enchants, candidates);
        for (Holder<Enchantment> holder : candidates) {
            holder.unwrapKey().ifPresent(key -> {
                if (on && holder.is(EnchantmentTags.CURSE)) list.putIfAbsent(key.location(), false);
                else list.put(key.location(), on);
            });
        }
        return enchants.withList(list);
    }

    /** Up to {@code n} different enchantments {@code enchants} allows, for a book already holding {@code held}. */
    static List<Holder<Enchantment>> rollRandom(HolderLookup.RegistryLookup<Enchantment> lookup, BookEnchants enchants,
                                                Iterable<Holder<Enchantment>> held, int n, RandomSource rng) {
        List<Holder<Enchantment>> candidates = new ArrayList<>();
        for (Holder<Enchantment> holder : listCandidates(lookup)) {
            if (allowed(enchants, holder)) candidates.add(holder);
        }
        return pick(candidates, held, n, rng);
    }

    /**
     * Up to {@code n} of {@code candidates}, none already in {@code held}. Each pick must be compatible
     * with everything already on the book, so every enchantment on it can go onto one item; the book
     * gets fewer than {@code n} when the compatible ones run out.
     */
    static List<Holder<Enchantment>> pick(List<Holder<Enchantment>> candidates,
                                          Iterable<Holder<Enchantment>> held, int n, RandomSource rng) {
        if (n <= 0 || candidates.isEmpty()) return List.of();
        List<Holder<Enchantment>> remaining = new ArrayList<>(candidates);
        List<Holder<Enchantment>> chosen = new ArrayList<>();
        held.forEach(chosen::add);
        int heldCount = chosen.size();
        while (chosen.size() - heldCount < n && !remaining.isEmpty()) {
            Holder<Enchantment> next = remaining.remove(rng.nextInt(remaining.size()));
            boolean fits = true;
            for (Holder<Enchantment> other : chosen) {
                if (next.equals(other) || !Enchantment.areCompatible(next, other)) {
                    fits = false;
                    break;
                }
            }
            if (fits) chosen.add(next);
        }
        return List.copyOf(chosen.subList(heldCount, chosen.size()));
    }
}
