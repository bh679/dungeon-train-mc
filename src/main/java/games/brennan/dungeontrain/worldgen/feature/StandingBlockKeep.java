package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Inside Dungeon Train's Nether-core feature pass, decoration never replaces a standing block.
 *
 * <p>BetterNether's crimson glowing woods are prebuilt trees ({@code betternether:trees/crimson_glow_tree_*})
 * stamped twelve to a chunk, each hanging weeping-vine chains from its shroomlight, stem and hyphae. Two
 * writers then land {@code betternether:wall_moss} on those chains: a second tree stamped over the first
 * (its moss over the first tree's anchors) and the biome's wall-moss patches ({@code wover:place_block},
 * which only asks that the cell is not solid, so a vine segment beside a trunk qualifies). Wall moss has no
 * sturdy underside, so on the chunk's first tick every chain beneath it fails
 * {@code GrowingPlantBlock#canSurvive} and pops into items — about two per generated chunk, the ~2,300
 * dropped items behind the 0.1286.0 lag report.</p>
 *
 * <p>This is the one rule, applied at {@code WorldGenRegion#setBlock} while
 * {@link NetherCoreDecoration#isActive()}: a block that is not a full cube (moss, vines, mushrooms, roots) is
 * dropped where a full block (the anchor) or a vine segment (the chain under one) already stands. Air,
 * grass and other decoration are written over as before, and a full block still replaces anything, so
 * trunks cross and terrain features carve. Reads only the world,
 * so chunk output stays seed-stable.</p>
 */
public final class StandingBlockKeep {

    private StandingBlockKeep() {}

    /**
     * True when the world block stays and the incoming write is dropped: the incoming block is short of a
     * full cube (moss, vines, mushrooms, roots — BetterNether's wall moss keeps a thin collision slab, so
     * "no collision" would miss it) and the world block is one a vine chain can hang from or is part of — a
     * full cube, or a vine segment. Decoration over decoration (a tree's vine chain through another tree's
     * side moss) is still written, or the chain itself would stop at the moss and hang from it.
     * Pure apart from the shape lookup.
     */
    public static boolean keeps(BlockState existing, BlockState incoming, BlockGetter level, BlockPos pos) {
        if (incoming.isAir() || incoming.isCollisionShapeFullBlock(level, pos)) return false;
        if (existing.isAir()) return false;
        return existing.isCollisionShapeFullBlock(level, pos) || existing.getBlock() instanceof GrowingPlantBlock;
    }
}
