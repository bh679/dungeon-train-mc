package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Surface palettes that make a stamped mountain <em>look like</em> a chosen vanilla
 * mountainous biome (the Nether transition's opening stages). One palette is picked
 * deterministically per run from the world generation seed, so a given run always reads
 * as the same mountain type — first at a normal height, then stretched taller.
 *
 * <p>This is a visual replica (the F3 biome / natural mobs stay vanilla); the blocks are
 * stamped directly. {@code surfaceBlock} is keyed on depth below the column top so snow
 * caps, nylium tops, grass etc. land where they would on the real biome's peaks.</p>
 */
public enum MountainPalette {

    /** Bare stone with a thin snow cap — the classic jagged peak. */
    JAGGED_PEAKS {
        @Override public BlockState surfaceBlock(int depth, boolean aboveSnowLine, double noise) {
            if (aboveSnowLine && depth < 6) return SNOW_BLOCK;
            return STONE;
        }
    },
    /** Snow + packed-ice flecks over stone. */
    FROZEN_PEAKS {
        @Override public BlockState surfaceBlock(int depth, boolean aboveSnowLine, double noise) {
            if (aboveSnowLine && depth < 8) return noise < 0.18 ? PACKED_ICE : SNOW_BLOCK;
            return STONE;
        }
    },
    /** Deep snow blanket over stone. */
    SNOWY_SLOPES {
        @Override public BlockState surfaceBlock(int depth, boolean aboveSnowLine, double noise) {
            if (aboveSnowLine && depth < 10) return SNOW_BLOCK;
            return STONE;
        }
    },
    /** Warm peaks — stone with calcite seams and gravel patches, no snow. */
    STONY_PEAKS {
        @Override public BlockState surfaceBlock(int depth, boolean aboveSnowLine, double noise) {
            if (depth < 3) {
                if (noise < 0.30) return CALCITE;
                if (noise > 0.82) return GRAVEL;
            }
            return STONE;
        }
    },
    /** Green alpine meadow — grass over dirt over stone. */
    MEADOW {
        @Override public BlockState surfaceBlock(int depth, boolean aboveSnowLine, double noise) {
            if (depth == 0) return GRASS_BLOCK;
            if (depth < 4) return DIRT;
            return STONE;
        }
    },
    /** Snowy spruce-grove floor — snow over podzol/dirt over stone. */
    GROVE {
        @Override public BlockState surfaceBlock(int depth, boolean aboveSnowLine, double noise) {
            if (depth == 0) return SNOW_BLOCK;
            if (depth < 4) return noise < 0.5 ? PODZOL : DIRT;
            return STONE;
        }
    },
    /** Windswept hills — patchy grass and exposed stone/gravel. */
    WINDSWEPT_HILLS {
        @Override public BlockState surfaceBlock(int depth, boolean aboveSnowLine, double noise) {
            if (depth == 0) return noise < 0.65 ? GRASS_BLOCK : STONE;
            if (depth < 3) return noise < 0.5 ? DIRT : GRAVEL;
            return STONE;
        }
    };

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState SNOW_BLOCK = Blocks.SNOW_BLOCK.defaultBlockState();
    private static final BlockState PACKED_ICE = Blocks.PACKED_ICE.defaultBlockState();
    private static final BlockState CALCITE = Blocks.CALCITE.defaultBlockState();
    private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState GRASS_BLOCK = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState PODZOL = Blocks.PODZOL.defaultBlockState();

    /**
     * Block for a column cell, given its {@code depth} below the column top (0 = surface),
     * whether the peak rises above the snow line, and a deterministic noise value in
     * {@code [0,1)} for accents.
     */
    public abstract BlockState surfaceBlock(int depth, boolean aboveSnowLine, double noise);

    /** Bulk rock under the surface dressing — what the netherrack crossfade dithers against. */
    public BlockState rock() {
        return STONE;
    }

    /** Deterministically pick one palette for a run from its world generation seed. */
    public static MountainPalette fromSeed(long seed) {
        long h = seed * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 32);
        MountainPalette[] all = values();
        return all[Math.floorMod((int) h, all.length)];
    }
}
