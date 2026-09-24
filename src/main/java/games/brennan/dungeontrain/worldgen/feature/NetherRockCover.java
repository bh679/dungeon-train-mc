package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.IntPredicate;

/**
 * Which overworld rock the Nether band covers in netherrack, and where — so that no grey stone
 * shows from inside the Nether.
 *
 * <p>The band's mountain body is real overworld terrain (stone, deepslate, dirt, ores). The core
 * restamps only its sampled Y band and the crossfade used to recolour only the surface skin, so the
 * cliff the core carves against the crossfade — and the rock above/below the core band — stayed
 * stone. {@link NetherTransitionFeature} uses this to repaint that rock.</p>
 */
final class NetherRockCover {

    /**
     * Crossfade columns within this many blocks (edge-waved X) of the real-Nether core are the
     * core-facing wall: every piece of overworld rock in them becomes netherrack, not just a dithered share.
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
     * True when a crossfade column at edge-waved {@code wx} faces the core: the core begins within
     * {@link #WALL_DEPTH} blocks on either side. Pure — {@code isCore} is the cycle's core test.
     */
    static boolean isCoreWall(IntPredicate isCore, int wx) {
        return isCore.test(wx + WALL_DEPTH) || isCore.test(wx - WALL_DEPTH);
    }
}
