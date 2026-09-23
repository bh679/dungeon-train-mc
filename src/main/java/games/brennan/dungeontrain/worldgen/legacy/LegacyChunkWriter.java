package games.brennan.dungeontrain.worldgen.legacy;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaBlocks;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaChunk;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaTerrain;
import games.brennan.dungeontrain.worldgen.legacy.farlands.FarLandsShift;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;

/**
 * Writes an old generator's column into a fresh {@link ChunkAccess} during the NOISE step, in place of
 * vanilla's {@code fillFromNoise}. The old world's {@code y = 0} lands at {@link #Y_OFFSET} so Beta's
 * sea (top water at y 63) lines up with the modern sea (top water at y 62) and fade-seam oceans meet flush.
 * Everything below the old column down to the world floor is stone — the old world's bedrock layer stays
 * where it was, and DT's own floor still goes in at the bottom.
 */
public final class LegacyChunkWriter {

    /** World Y of the old generator's {@code y = 0}. */
    public static final int Y_OFFSET = -1;

    private static final BlockState[] STATES = new BlockState[256];

    static {
        STATES[BetaBlocks.STONE] = Blocks.STONE.defaultBlockState();
        STATES[BetaBlocks.GRASS] = Blocks.GRASS_BLOCK.defaultBlockState();
        STATES[BetaBlocks.DIRT] = Blocks.DIRT.defaultBlockState();
        STATES[BetaBlocks.BEDROCK] = Blocks.BEDROCK.defaultBlockState();
        STATES[BetaBlocks.WATER] = Blocks.WATER.defaultBlockState();
        STATES[BetaBlocks.LAVA] = Blocks.LAVA.defaultBlockState();
        STATES[BetaBlocks.SAND] = Blocks.SAND.defaultBlockState();
        STATES[BetaBlocks.GRAVEL] = Blocks.GRAVEL.defaultBlockState();
        STATES[BetaBlocks.SANDSTONE] = Blocks.SANDSTONE.defaultBlockState();
        STATES[BetaBlocks.ICE] = Blocks.ICE.defaultBlockState();
    }

    private LegacyChunkWriter() {}

    /** Generate {@code kind}'s terrain for {@code chunk} and write it, filling stone down to {@code floorY}. */
    public static void fill(LegacyBandKind kind, long seed, ChunkAccess chunk, int floorY) {
        switch (kind) {
            case BETA -> write(chunk, LegacyBands.beta(seed).generate(chunk.getPos().x, chunk.getPos().z), floorY);
            case FAR_LANDS -> {
                // The Far Lands are Beta's own terrain, read ~12.55M blocks out (see FarLandsShift).
                FarLandsShift shift = FarLandsShift.of(WorldGenCycle.fromConfig(), chunk.getPos().x, chunk.getPos().z);
                write(chunk, LegacyBands.beta(seed).generate(chunk.getPos().x + shift.dxChunks(),
                        chunk.getPos().z + shift.dzChunks()), floorY);
            }
        }
    }

    static void write(ChunkAccess chunk, BetaChunk column, int floorY) {
        int minY = Math.max(chunk.getMinBuildHeight(), floorY);
        int maxY = chunk.getMaxBuildHeight() - 1;
        BlockState stone = STATES[BetaBlocks.STONE];
        byte[] blocks = column.blocks();
        for (int y = minY; y <= maxY; y++) {
            int oldY = y - Y_OFFSET;
            if (oldY >= BetaTerrain.HEIGHT) break;
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
            int ly = y & 15;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockState state = oldY < 0 ? stone : STATES[blocks[BetaTerrain.index(x, oldY, z)] & 0xFF];
                    if (state != null) section.setBlockState(x, ly, z, state, false);
                }
            }
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
    }
}
