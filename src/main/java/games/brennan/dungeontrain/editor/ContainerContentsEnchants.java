package games.brennan.dungeontrain.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The enchantment side of a book {@link ContainerContentsEntry}: reading the stored enchantments off
 * an enchanted book an author adds from their hand, and enchanting the book the roller spawns with an
 * entry's {@link BookEnchants}.
 */
public final class ContainerContentsEnchants {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Entry JSON key for the exact enchantments, id to level. */
    public static final String JSON_EXACT = "enchants";
    /** Entry JSON key for the random range, {@code {"min": 1, "max": 7}}. */
    public static final String JSON_RANDOM = "bookEnchants";
    private static final String KEY_MIN = "min";
    private static final String KEY_MAX = "max";

    private ContainerContentsEnchants() {}

    /** True for the two items a {@link BookEnchants} applies to. */
    public static boolean isBook(ItemStack stack) {
        return stack.is(Items.BOOK) || stack.is(Items.ENCHANTED_BOOK);
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
            for (Holder<Enchantment> holder : rollRandom(lookup.get(), stored.keySet(), n, rng)) {
                stored.set(holder, holder.value().getMaxLevel());
            }
        }
        if (stored.keySet().isEmpty()) return stack;

        ItemStack book = stack.is(Items.ENCHANTED_BOOK) ? stack.copyWithCount(1)
            : stack.transmuteCopy(Items.ENCHANTED_BOOK, 1);
        book.set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());
        return book;
    }

    /**
     * What a book's random enchantments are drawn from: {@code #minecraft:on_random_loot} (so other
     * mods' enchantments come along), never a curse and never Mending, which a loot table offers as
     * its own entry. Sorted by id, as tag order follows datapack load order and the same seed must
     * always roll the same book.
     */
    static List<Holder<Enchantment>> randomCandidates(HolderLookup.RegistryLookup<Enchantment> lookup) {
        Optional<HolderSet.Named<Enchantment>> pool = lookup.get(EnchantmentTags.ON_RANDOM_LOOT);
        if (pool.isEmpty()) return List.of();
        List<Holder<Enchantment>> candidates = new ArrayList<>();
        for (Holder<Enchantment> holder : pool.get()) {
            if (holder.unwrapKey().isEmpty()) continue;
            if (holder.is(EnchantmentTags.CURSE) || holder.is(Enchantments.MENDING)) continue;
            candidates.add(holder);
        }
        candidates.sort(Comparator.comparing(h -> h.unwrapKey().get().location()));
        return candidates;
    }

    /** Up to {@code n} different enchantments from {@link #randomCandidates} for a book already holding {@code held}. */
    static List<Holder<Enchantment>> rollRandom(HolderLookup.RegistryLookup<Enchantment> lookup,
                                                Iterable<Holder<Enchantment>> held, int n, RandomSource rng) {
        return pick(randomCandidates(lookup), held, n, rng);
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

    // ---- JSON (store files) -----------------------------------------------------------------

    /** The book enchantments on entry object {@code entry}, or {@code null} when it has none. */
    public static @Nullable BookEnchants fromJson(JsonObject entry) {
        try {
            Map<ResourceLocation, Integer> exact = new LinkedHashMap<>();
            JsonElement ex = entry.get(JSON_EXACT);
            if (ex != null && ex.isJsonObject()) {
                for (var e : ex.getAsJsonObject().entrySet()) {
                    ResourceLocation id = ResourceLocation.tryParse(e.getKey());
                    if (id != null && e.getValue().isJsonPrimitive()) exact.put(id, e.getValue().getAsInt());
                }
            }
            int min = 0;
            int max = 0;
            JsonElement rnd = entry.get(JSON_RANDOM);
            if (rnd != null && rnd.isJsonObject()) {
                JsonObject o = rnd.getAsJsonObject();
                max = o.has(KEY_MAX) ? o.get(KEY_MAX).getAsInt() : 0;
                min = o.has(KEY_MIN) ? o.get(KEY_MIN).getAsInt() : max;
            }
            BookEnchants enchants = new BookEnchants(exact, min, max);
            return enchants.isEmpty() ? null : enchants;
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Loot book enchantments: malformed JSON, dropping them — {}", e.toString());
            return null;
        }
    }

    /** {@code , "enchants": {...}, "bookEnchants": {...}} for the hand-built store writers. */
    public static void appendJson(StringBuilder sb, @Nullable BookEnchants enchants) {
        if (enchants == null || enchants.isEmpty()) return;
        if (!enchants.exact().isEmpty()) {
            sb.append(", \"").append(JSON_EXACT).append("\": {");
            boolean first = true;
            for (var e : enchants.exact().entrySet()) {
                if (!first) sb.append(", ");
                sb.append('"').append(e.getKey()).append("\": ").append(e.getValue());
                first = false;
            }
            sb.append('}');
        }
        if (enchants.hasRandom()) {
            sb.append(", \"").append(JSON_RANDOM).append("\": { \"").append(KEY_MIN).append("\": ")
                .append(enchants.randomMin()).append(", \"").append(KEY_MAX).append("\": ")
                .append(enchants.randomMax()).append(" }");
        }
    }

    // ---- NBT (clipboard) --------------------------------------------------------------------

    public static CompoundTag toNbt(BookEnchants enchants) {
        CompoundTag tag = new CompoundTag();
        CompoundTag exact = new CompoundTag();
        enchants.exact().forEach((id, level) -> exact.putInt(id.toString(), level));
        tag.put(JSON_EXACT, exact);
        tag.putInt(KEY_MIN, enchants.randomMin());
        tag.putInt(KEY_MAX, enchants.randomMax());
        return tag;
    }

    public static @Nullable BookEnchants fromNbt(@Nullable CompoundTag tag) {
        if (tag == null) return null;
        Map<ResourceLocation, Integer> exact = new LinkedHashMap<>();
        CompoundTag ex = tag.getCompound(JSON_EXACT);
        for (String key : ex.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id != null && ex.contains(key, Tag.TAG_INT)) exact.put(id, ex.getInt(key));
        }
        BookEnchants enchants = new BookEnchants(exact, tag.getInt(KEY_MIN), tag.getInt(KEY_MAX));
        return enchants.isEmpty() ? null : enchants;
    }
}
