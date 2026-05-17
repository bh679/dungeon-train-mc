package games.brennan.dungeontrain.naming;

import com.mojang.logging.LogUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Walks a {@link NameChain} graph deterministically against a supplied
 * {@link RandomSource} to produce a generated display name, then stamps it
 * onto the {@link ItemStack} as a vanilla
 * {@code DataComponents.CUSTOM_NAME}.
 *
 * <p>Two original-Unity bugs are deliberately not preserved:
 * <ul>
 *   <li>Weighted pick: cumulative comparison now happens <em>after</em>
 *       adding the current weight (Unity {@code SharedLines.cs:48-53}
 *       compared before adding, returning the previous index).</li>
 *   <li>Uniform pick: index range is {@code [0, size)} (Unity
 *       {@code SharedLines.cs:71} used {@code Count-1} which silently
 *       excluded the last entry — invisible bug, real bias).</li>
 * </ul>
 * </p>
 *
 * <p>Composition only reads the registry; entry filtering by
 * {@code item_types} runs at compose time against the supplied target
 * item-tag ResourceLocation so universal entries can mix with type-locked
 * ones in the same pool.</p>
 *
 * <p>Context-aware virtual refs are reserved under the
 * {@code dungeontrain:context/...} path — they short-circuit pool/chain
 * lookup and read from the {@link ItemStack} instead. Currently:
 * {@link #REF_ITEM_MATERIAL} returns the stack's vanilla material prefix
 * (Iron, Diamond, Netherite, ...). Unknown materials (shield, custom
 * items) return empty so the composing segment gracefully skips.</p>
 */
public final class NameComposer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Cap chain recursion depth so a circular ref can't blow the stack. */
    private static final int MAX_DEPTH = 16;

    /** Naming probability for plain (unenchanted) items that match a selector. */
    private static final float CHANCE_PLAIN = 0.30f;
    /** Naming probability for enchanted items that match a selector. */
    private static final float CHANCE_ENCHANTED = 0.50f;

    /** Virtual ref resolved from the stack's item id rather than a JSON pool. */
    public static final ResourceLocation REF_ITEM_MATERIAL =
        ResourceLocation.fromNamespaceAndPath("dungeontrain", "context/item_material");

    /**
     * Pool consulted for the item-type-synonym placement roll. Entries are
     * tagged with {@code item_types} so a sword can only roll Blade/Sword/
     * etc. — never "Trousers". When the pool yields a synonym the composer
     * picks one of four placements uniformly: none, start, end, "start of".
     */
    private static final ResourceLocation POOL_TYPE_SYNONYMS =
        ResourceLocation.fromNamespaceAndPath("dungeontrain", "type_synonyms");

    private NameComposer() {}

    /**
     * Generate a name for {@code stack} and apply it as
     * {@link DataComponents#CUSTOM_NAME}. No-op when no registered
     * selector covers the stack's item tags or the chosen chain is empty /
     * unresolved. Iterates {@link NameRegistry#findMatching} so adding new
     * item types is purely a data-side change (drop a selector JSON, no
     * Java edits).
     */
    public static void applyName(ItemStack stack, RandomSource rng) {
        Optional<NameSelector> maybeSel = NameRegistry.findMatching(stack);
        if (maybeSel.isEmpty()) return;
        NameSelector sel = maybeSel.get();

        boolean enchanted = stack.isEnchanted();
        if (rng.nextFloat() >= (enchanted ? CHANCE_ENCHANTED : CHANCE_PLAIN)) return;

        NameTier tier = enchanted ? NameTier.ENCHANTED : NameTier.PLAIN;
        ResourceLocation chainId = sel.tiers().get(tier.key());
        if (chainId == null) chainId = sel.tiers().get(NameTier.PLAIN.key());
        if (chainId == null) return;

        String name = compose(chainId, stack, sel.appliesTo(), rng, 0);
        if (name == null || name.isBlank()) return;

        name = applyTypeSynonym(name, stack, sel.appliesTo(), rng);

        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
    }

    /**
     * Roll one of four weighted placements for the item-type synonym:
     * none (65%), prefix with space (4%), suffix with space (30%),
     * prefix with " of " (1%). Returns the original name unchanged when
     * the synonym pool has no entry tagged for this item kind (e.g. a
     * future item type with no synonyms shipped yet) — the segment
     * quietly skips rather than polluting the name.
     */
    private static String applyTypeSynonym(String name, ItemStack stack,
                                           ResourceLocation targetTagId, RandomSource rng) {
        Optional<NamePool> pool = NameRegistry.pool(POOL_TYPE_SYNONYMS);
        if (pool.isEmpty()) return name;
        String synonym = pickPoolEntry(pool.get(), targetTagId, rng);
        if (synonym.isEmpty()) return name;
        int roll = rng.nextInt(100);
        if (roll < 65) return name;                        // 65% none
        if (roll < 69) return synonym + " " + name;        //  4% start
        if (roll < 99) return name + " " + synonym;        // 30% end
        return synonym + " of " + name;                    //  1% start of
    }

    /**
     * Vanilla material prefix mapping. Order matters — longer prefixes
     * (e.g. {@code golden_}) must match before any shorter substring
     * collisions. Returns empty string for items whose path doesn't carry
     * a material prefix (shields, custom mod items, vanilla items without
     * a tier — e.g. carrot, written_book).
     */
    public static String materialOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        ResourceLocation rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (rl == null) return "";
        String path = rl.getPath();
        if (path.startsWith("netherite_")) return "Netherite";
        if (path.startsWith("chainmail_")) return "Chainmail";
        if (path.startsWith("diamond_"))   return "Diamond";
        if (path.startsWith("leather_"))   return "Leather";
        if (path.startsWith("golden_"))    return "Gold";
        if (path.startsWith("turtle_"))    return "Turtle";
        if (path.startsWith("iron_"))      return "Iron";
        if (path.startsWith("stone_"))     return "Stone";
        if (path.startsWith("wooden_"))    return "Wood";
        return "";
    }

    private static String compose(ResourceLocation chainId, ItemStack stack,
                                  ResourceLocation targetTagId, RandomSource rng, int depth) {
        if (depth >= MAX_DEPTH) {
            LOGGER.warn("[DungeonTrain] Naming: max compose depth reached at chain {}", chainId);
            return "";
        }
        Optional<NameChain> maybeChain = NameRegistry.chain(chainId);
        if (maybeChain.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Naming: chain '{}' not registered", chainId);
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (NameSegment seg : maybeChain.get().segments()) {
            if (seg.chance() < 1f && rng.nextFloat() >= seg.chance()) continue;

            NameSegment.WeightedRef picked = pickWeighted(seg.refs(), rng);
            if (picked == null) continue;

            String fragment = resolveRef(picked.ref(), stack, targetTagId, rng, depth + 1);
            if (fragment == null || fragment.isEmpty()) continue;

            if (out.length() > 0) {
                out.append(seg.connection());
                if (seg.newline()) out.append('\n');
            }
            out.append(fragment);
        }
        return out.toString();
    }

    /**
     * Resolve a chain ref to a concrete fragment — context-aware virtual
     * refs (e.g. {@link #REF_ITEM_MATERIAL}) short-circuit, then chain,
     * then pool.
     */
    private static String resolveRef(ResourceLocation refId, ItemStack stack,
                                     ResourceLocation targetTagId, RandomSource rng, int depth) {
        if (refId.equals(REF_ITEM_MATERIAL)) {
            return materialOf(stack);
        }
        Optional<NameChain> chain = NameRegistry.chain(refId);
        if (chain.isPresent()) {
            return compose(refId, stack, targetTagId, rng, depth);
        }
        Optional<NamePool> pool = NameRegistry.pool(refId);
        if (pool.isPresent()) {
            return pickPoolEntry(pool.get(), targetTagId, rng);
        }
        LOGGER.warn("[DungeonTrain] Naming: ref '{}' resolves to neither pool nor chain", refId);
        return "";
    }

    private static String pickPoolEntry(NamePool pool, ResourceLocation targetTagId, RandomSource rng) {
        List<NamePool.PoolEntry> compatible = new ArrayList<>(pool.entries().size());
        for (NamePool.PoolEntry e : pool.entries()) {
            if (e.itemTypes().isEmpty() || e.itemTypes().contains(targetTagId)) {
                compatible.add(e);
            }
        }
        if (compatible.isEmpty()) return "";
        return compatible.get(rng.nextInt(compatible.size())).text();
    }

    /**
     * Cumulative-weight pick over a list of weighted refs. Returns the
     * last ref when float drift carries past the total — never returns
     * null for a non-empty input.
     */
    private static NameSegment.WeightedRef pickWeighted(List<NameSegment.WeightedRef> refs, RandomSource rng) {
        if (refs.isEmpty()) return null;
        float total = 0f;
        for (NameSegment.WeightedRef r : refs) total += r.weight();
        if (total <= 0f) return null;
        float roll = rng.nextFloat() * total;
        float cum = 0f;
        for (NameSegment.WeightedRef r : refs) {
            cum += r.weight();
            if (roll < cum) return r;
        }
        return refs.get(refs.size() - 1);
    }
}
