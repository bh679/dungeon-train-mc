package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The two block predicates the Nether-band foliage strip asks of every block, with the answers cached
 * <b>per {@link Block}</b>. Tag membership ({@link BlockState#is(net.minecraft.tags.TagKey)}) resolves through
 * the block's registry holder, so it is a property of the block, not the state — one map hit replaces the six
 * tag lookups the strip used to do per non-air block ({@code BlockStateBase.is} was the top frame of the
 * 5-second server-thread stall in the 0.983.0 lag report).
 *
 * <p>Thread-safe: read from the worldgen worker (SPAWN precompute) and the main thread (Load fallback/apply).
 * {@link #reset()} drops the cache when tags reload ({@code TagsUpdatedEvent}, wired in
 * {@code NetherTransitionEvents}) so a datapack that changes {@code #minecraft:leaves} etc. is honoured.</p>
 */
public final class StrippableFoliage {

    private static final ConcurrentHashMap<Block, Boolean> STRIPPABLE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Block, Boolean> NETHER_FLORA = new ConcurrentHashMap<>();

    private StrippableFoliage() {}

    /**
     * Surface foliage that the mountain may bury — leaves, logs, vines, saplings, flowers. Deliberately
     * excludes fluids and other replaceables (catching fluids would cascade neighbour updates), mirroring
     * {@code CorridorCleanupEvents.isFoliage}.
     */
    public static boolean isStrippable(BlockState state) {
        return STRIPPABLE.computeIfAbsent(state.getBlock(), b -> computeStrippable(state));
    }

    /**
     * Flora the real-Nether core's own decoration grows, which the strip must leave standing. Huge crimson /
     * warped fungi have {@code #minecraft:logs} stems, and BetterNether / BoP Nether trees have modded logs
     * and leaves; stripping them as "overworld foliage" left their caps, shroomlights, weeping vines, wall
     * moss and wall mushrooms floating in mid-air. Overworld trees are vanilla ({@code minecraft:}) wood, so
     * spilled overworld canopies are still stripped in the core.
     */
    public static boolean isNetherFlora(BlockState state) {
        return NETHER_FLORA.computeIfAbsent(state.getBlock(), b -> computeNetherFlora(state));
    }

    /** Drop both caches (tags reloaded). */
    public static void reset() {
        STRIPPABLE.clear();
        NETHER_FLORA.clear();
    }

    private static boolean computeStrippable(BlockState state) {
        return state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS)
                || state.is(Blocks.VINE)
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.SMALL_FLOWERS)
                || state.is(BlockTags.TALL_FLOWERS);
    }

    private static boolean computeNetherFlora(BlockState state) {
        if (state.is(BlockTags.CRIMSON_STEMS) || state.is(BlockTags.WARPED_STEMS)
                || state.is(BlockTags.WART_BLOCKS)) {
            return true;
        }
        return !BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft");
    }
}
