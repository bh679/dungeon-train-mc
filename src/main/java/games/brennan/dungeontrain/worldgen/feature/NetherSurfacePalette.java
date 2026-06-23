package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per-biome floor material for the Dungeon-Train Nether core. The real {@code SurfaceSystem} can't run on
 * the Y-remapped core (its bedrock-roof / lava-sea rules are absolute-Y and would misfire), so this is a
 * targeted skin: {@code NetherTransitionFeature} recolours the top {@code SURFACE_SKIN_DEPTH} of each
 * exposed netherrack floor to the block this returns, matching what each Nether biome's surface looks like.
 *
 * <ul>
 *   <li>crimson_forest → crimson_nylium cap over netherrack</li>
 *   <li>warped_forest → warped_nylium cap over netherrack</li>
 *   <li>soul_sand_valley → soul_sand / soul_soil noise mix</li>
 *   <li>basalt_deltas → basalt / blackstone noise mix</li>
 *   <li>nether_wastes / anything else → plain netherrack (no skin)</li>
 * </ul>
 */
public final class NetherSurfacePalette {

    private static final BlockState NETHERRACK = Blocks.NETHERRACK.defaultBlockState();
    private static final BlockState CRIMSON_NYLIUM = Blocks.CRIMSON_NYLIUM.defaultBlockState();
    private static final BlockState WARPED_NYLIUM = Blocks.WARPED_NYLIUM.defaultBlockState();
    private static final BlockState SOUL_SAND = Blocks.SOUL_SAND.defaultBlockState();
    private static final BlockState SOUL_SOIL = Blocks.SOUL_SOIL.defaultBlockState();
    private static final BlockState BASALT = Blocks.BASALT.defaultBlockState();
    private static final BlockState BLACKSTONE = Blocks.BLACKSTONE.defaultBlockState();

    private NetherSurfacePalette() {}

    /** Whether this biome paints a non-netherrack floor (so running the skin pass is worth it). */
    public static boolean hasSurface(ResourceKey<Biome> biome) {
        return biome == Biomes.CRIMSON_FOREST
                || biome == Biomes.WARPED_FOREST
                || biome == Biomes.SOUL_SAND_VALLEY
                || biome == Biomes.BASALT_DELTAS;
    }

    /**
     * Surface block at {@code depthBelowTop} (0 = the exposed top block) for a column of {@code biome}.
     * {@code noise} is a coherent value in {@code [0,1)} used to mottle the two-block biomes. Returns
     * {@link Blocks#NETHERRACK} for nether_wastes / unknown biomes and below the nylium cap.
     */
    public static BlockState surfaceBlock(ResourceKey<Biome> biome, int depthBelowTop, double noise) {
        if (biome == Biomes.CRIMSON_FOREST) {
            return depthBelowTop == 0 ? CRIMSON_NYLIUM : NETHERRACK; // nylium is a 1-block cap
        }
        if (biome == Biomes.WARPED_FOREST) {
            return depthBelowTop == 0 ? WARPED_NYLIUM : NETHERRACK;
        }
        if (biome == Biomes.SOUL_SAND_VALLEY) {
            return noise < 0.55 ? SOUL_SAND : SOUL_SOIL;            // soul sand dominant, soul soil patches
        }
        if (biome == Biomes.BASALT_DELTAS) {
            return noise < 0.6 ? BASALT : BLACKSTONE;               // basalt dominant, blackstone patches
        }
        return NETHERRACK;
    }
}
