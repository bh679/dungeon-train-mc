package games.brennan.dungeontrain.worldgen.legacy;

import games.brennan.dungeontrain.worldgen.legacy.beta.BetaBlocks;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaChunk;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaTerrain;
import games.brennan.dungeontrain.worldgen.legacy.classic.ClassicBlocks;
import games.brennan.dungeontrain.worldgen.legacy.classic.ClassicLevel;
import games.brennan.dungeontrain.worldgen.legacy.classic.ClassicLevels;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;

/**
 * Writes an old generator's column into a fresh {@link ChunkAccess} during the NOISE step, in place of
 * vanilla's {@code fillFromNoise}. The old world's {@code y = 0} lands at a per-generator offset so its sea
 * lines up with the modern sea (top water at y 62) and fade-seam oceans meet flush — {@link #Y_OFFSET} for
 * Beta (top water at y 63), {@link ClassicLevels#Y_OFFSET} for Classic (top water at level y 31).
 * Everything below the old column down to the world floor is stone — the old world's bedrock layer stays
 * where it was, and DT's own floor still goes in at the bottom.
 */
public final class LegacyChunkWriter {

    /** World Y of Beta's {@code y = 0}. */
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
        STATES[ClassicBlocks.GOLD_ORE] = Blocks.GOLD_ORE.defaultBlockState();
        STATES[ClassicBlocks.IRON_ORE] = Blocks.IRON_ORE.defaultBlockState();
        STATES[ClassicBlocks.COAL_ORE] = Blocks.COAL_ORE.defaultBlockState();
        STATES[ClassicBlocks.LOG] = Blocks.OAK_LOG.defaultBlockState();
        // Natural (decaying) leaves that start attached, like BetaTrees'.
        STATES[ClassicBlocks.LEAVES] = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
        STATES[ClassicBlocks.DANDELION] = Blocks.DANDELION.defaultBlockState();
        STATES[ClassicBlocks.ROSE] = Blocks.POPPY.defaultBlockState();
        STATES[ClassicBlocks.BROWN_MUSHROOM] = Blocks.BROWN_MUSHROOM.defaultBlockState();
        STATES[ClassicBlocks.RED_MUSHROOM] = Blocks.RED_MUSHROOM.defaultBlockState();
    }

    private LegacyChunkWriter() {}

    /** Generate {@code kind}'s terrain for {@code chunk} and write it, filling stone down to {@code floorY}. */
    public static void fill(LegacyBandKind kind, long seed, ChunkAccess chunk, int floorY) {
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        switch (kind) {
            case BETA -> {
                BetaChunk column = LegacyBands.beta(seed).generate(chunkX, chunkZ);
                write(chunk, column.blocks(), BetaTerrain.HEIGHT, Y_OFFSET, floorY);
            }
            case CLASSIC -> write(chunk, LegacyBands.classic(seed).chunkColumn(chunkX, chunkZ),
                    ClassicLevel.HEIGHT, ClassicLevels.Y_OFFSET, floorY);
        }
    }

    /**
     * Write an old column ({@code blocks[(x·16 + z)·height + y]} — Beta's own {@code x << 11 | z << 7 | y}
     * when {@code height == 128}) with its {@code y = 0} at world {@code yOffset}; stone below it.
     */
    static void write(ChunkAccess chunk, byte[] blocks, int height, int yOffset, int floorY) {
        int minY = Math.max(chunk.getMinBuildHeight(), floorY);
        int maxY = chunk.getMaxBuildHeight() - 1;
        BlockState stone = STATES[BetaBlocks.STONE];
        for (int y = minY; y <= maxY; y++) {
            int oldY = y - yOffset;
            if (oldY >= height) break;
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
            int ly = y & 15;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockState state = oldY < 0 ? stone : STATES[blocks[(x * 16 + z) * height + oldY] & 0xFF];
                    if (state != null) section.setBlockState(x, ly, z, state, false);
                }
            }
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
    }
}
