package games.brennan.dungeontrain.editor;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Spears roll wherever a sword can: every {@code minecraft:<tier>_sword} entry in a container pool is
 * joined, at roll time, by a {@code minecraft:<tier>_spear} twin with the same weight, counts and
 * durability / enchantment rolls.
 *
 * <p>Spears come from VanillaBackport (1.2+), which registers them under {@code minecraft:}. A tier
 * whose spear is not registered gets no twin, so the pools roll exactly as authored without it.</p>
 *
 * <p>Applied by {@link ContainerContentsRoller} to the pool it is about to roll, never to the stored
 * pool: the editor still shows and saves only what was authored, and rooms authored later get their
 * spears the same way. Idempotent — a sword already followed by its twin gets no second one, so a
 * pool that reaches the roller twice (a vault's saved pool, rolled again on unlock) is unchanged.</p>
 */
public final class SpearTwins {

    /** Vanilla's sword tiers; each has a spear of the same name. */
    private static final Set<String> TIERS = Set.of(
            "wooden", "stone", "copper", "iron", "golden", "diamond", "netherite");
    private static final String NAMESPACE = "minecraft";
    private static final String SWORD = "_sword";
    private static final String SPEAR = "_spear";

    private SpearTwins() {}

    /** {@code pool} with a spear twin after each sword entry whose spear is registered. */
    public static ContainerContentsPool expand(@Nullable ContainerContentsPool pool) {
        return expand(pool, BuiltInRegistries.ITEM::containsKey);
    }

    static ContainerContentsPool expand(@Nullable ContainerContentsPool pool, Predicate<ResourceLocation> registered) {
        if (pool == null || pool.isEmpty()) return pool;
        List<ContainerContentsEntry> src = pool.entries();
        List<ContainerContentsEntry> out = null;
        for (int i = 0; i < src.size(); i++) {
            ContainerContentsEntry entry = src.get(i);
            if (out != null) out.add(entry);
            ResourceLocation spear = spearFor(entry.itemId());
            if (spear == null || !registered.test(spear)) continue;
            if (i + 1 < src.size() && src.get(i + 1).itemId().equals(spear)) continue;
            if (out == null) out = new ArrayList<>(src.subList(0, i + 1));
            out.add(twin(entry, spear));
        }
        return out == null ? pool : new ContainerContentsPool(out, pool.fillMin(), pool.fillMax(), pool.vaultKey());
    }

    /** The spear matching a vanilla-tier sword id, or {@code null} for anything else. */
    @Nullable
    static ResourceLocation spearFor(ResourceLocation itemId) {
        if (!NAMESPACE.equals(itemId.getNamespace())) return null;
        String path = itemId.getPath();
        if (!path.endsWith(SWORD)) return null;
        String tier = path.substring(0, path.length() - SWORD.length());
        return TIERS.contains(tier) ? ResourceLocation.fromNamespaceAndPath(NAMESPACE, tier + SPEAR) : null;
    }

    private static ContainerContentsEntry twin(ContainerContentsEntry sword, ResourceLocation spear) {
        return new ContainerContentsEntry(spear, sword.count(), sword.minCount(), sword.weight(),
                sword.randomDurability(), sword.durabilityChance(),
                sword.randomEnchantment(), sword.enchantmentChance(),
                sword.slotOverride(), sword.potionId(), sword.scaleWithDistance(), sword.potionForm(),
                sword.book(), sword.enchants(), sword.includeLuck(), sword.contents());
    }
}
