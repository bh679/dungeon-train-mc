package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.IntPredicate;

/**
 * Which overworld rock the Nether band covers in netherrack — so that no grey stone shows from inside
 * the Nether.
 *
 * <p>The band's mountain body is real overworld terrain (stone, deepslate, dirt, ores). The core carves
 * its caverns right up against the crossfade, so the cliff it leaves — and the rock just past the core's
 * top/bottom rows — would read as stone from inside. {@link NetherTransitionFeature} repaints only that
 * one-block face; the mountain behind it (and its tunnel) stays stone.</p>
 */
final class NetherRockCover {

    /**
     * Crossfade columns within this many blocks (edge-waved X) of the real-Nether core are checked for a
     * face looking into the core — a cheap gate before the per-cell neighbour test. Generous on purpose:
     * neighbouring columns' waved X can differ by a few blocks.
     */
    static final int WALL_DEPTH = 16;

    private NetherRockCover() {}

    /**
     * Overworld rock the band repaints: base stone (stone, deepslate, granite, diorite, andesite, tuff),
     * dirt-family ground, gravel, clay, calcite, dripstone and overworld ores. Never air, fluids,
     * bedrock, Nether blocks or anything built (track, structures).
     */
    static boolean isOverworldRock(BlockState s) {
        if (s.isAir() || !s.getFluidState().isEmpty()) return false;
        if (s.is(BlockTags.BASE_STONE_OVERWORLD)) return true;
        if (s.is(BlockTags.DIRT)) return true;
        if (s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY) || s.is(Blocks.CALCITE) || s.is(Blocks.DRIPSTONE_BLOCK)) {
            return true;
        }
        if (s.is(Blocks.NETHER_GOLD_ORE)) return false;   // in #gold_ores, but already Nether
        return s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COPPER_ORES)
                || s.is(BlockTags.GOLD_ORES) || s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES)
                || s.is(BlockTags.DIAMOND_ORES) || s.is(BlockTags.EMERALD_ORES);
    }

    /**
     * True when a crossfade column at edge-waved {@code wx} is near enough the core to possibly face it:
     * the core begins within {@link #WALL_DEPTH} blocks on either side. Pure — {@code isCore} is the
     * cycle's core test.
     */
    static boolean isCoreWall(IntPredicate isCore, int wx) {
        return isCore.test(wx + WALL_DEPTH) || isCore.test(wx - WALL_DEPTH);
    }
}
