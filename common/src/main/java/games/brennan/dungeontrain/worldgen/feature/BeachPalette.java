package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Surface palette for the Nether transition's leading <b>beach/cliff stage</b> — used
 * only where a band rises out of the ocean, so the coast reads as sandy cliffs instead
 * of a flat stone shelf. Sand on top, sandstone beneath, stone in the cliff body, with
 * occasional gravel for a rocky shoreline. Mirrors {@link MountainPalette}'s
 * depth-keyed {@code surfaceBlock} shape.
 */
public final class BeachPalette {

    private static final BlockState SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState SANDSTONE = Blocks.SANDSTONE.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();

    private BeachPalette() {}

    /** Block for a cell at {@code depth} below the column top, with a deterministic noise accent in {@code [0,1)}. */
    public static BlockState surfaceBlock(int depth, double noise) {
        if (depth < 3) return noise < 0.15 ? GRAVEL : SAND;   // sandy shore with rocky patches
        if (depth < 7) return SANDSTONE;                       // beach sub-layer
        return STONE;                                          // cliff body
    }
}
