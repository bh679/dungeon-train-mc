package games.brennan.dungeontrain.worldgen.legacy;

import games.brennan.dungeontrain.worldgen.legacy.beta.BetaBlocks;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.legacy.indev.IndevFloatingLevel;
import games.brennan.dungeontrain.worldgen.legacy.indev.IndevLevels;
import games.brennan.dungeontrain.worldgen.legacy.beta.BetaTerrain;
import games.brennan.dungeontrain.worldgen.legacy.classic.ClassicBlocks;
import games.brennan.dungeontrain.worldgen.legacy.classic.ClassicLevel;
import games.brennan.dungeontrain.worldgen.legacy.farlands.FarLandsShift;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;

/**
 * Writes an old generator's column into a fresh {@link ChunkAccess} during the NOISE step, in place of
 * vanilla's {@code fillFromNoise}. The old world's {@code y = 0} lands at the kind's
 * {@linkplain LegacyBands#yOffset Y offset} — for Beta and Alpha, {@link #Y_OFFSET}, so the old sea (top
 * water at y 63) lines up with the modern sea (top water at y 62) and fade-seam oceans meet flush.
 * Below the old column: for a solid-world kind everything down to the world floor is stone (the old
 * bedrock layer stays where it was and DT's own floor still goes in at the bottom); a
 * {@linkplain LegacyBandKind#voidBelow void-below} kind leaves it empty.
 */
public final class LegacyChunkWriter {

    /** World Y of Beta's and Alpha's {@code y = 0}. */
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
        STATES[BetaBlocks.BRICKS] = Blocks.BRICKS.defaultBlockState();
        STATES[BetaBlocks.OBSIDIAN] = Blocks.OBSIDIAN.defaultBlockState();
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

    /**
     * Generate {@code kind}'s terrain for {@code chunk} and write it with the old {@code y = 0} at world
     * {@code yOffset}, filling stone down to {@code floorY} unless the kind is void below.
     */
    public static void fill(LegacyBandKind kind, long seed, ChunkAccess chunk, int floorY, int yOffset) {
        int cx = chunk.getPos().x;
        int cz = chunk.getPos().z;
        byte[] blocks = switch (kind) {
            case BETA -> LegacyBands.beta(seed).generate(cx, cz).blocks();
            case SKYLANDS -> LegacyBands.sky(seed).generate(cx, cz).blocks();
            case ALPHA -> LegacyBands.alpha(seed).generate(cx, cz, LegacyBands.isAlphaWinter(WorldGenCycle.fromConfig(), cx));
            case INFDEV -> LegacyBands.infdev(seed).generate(cx, cz, LegacyBands.infdevVersion(WorldGenCycle.fromConfig(), cx));
            case FLOATING -> null; // not a Beta-layout column: a slice of a whole finite level
            case CLASSIC -> LegacyBands.classic(seed).chunkColumn(cx, cz);
            case FAR_LANDS -> {
                // The Far Lands are Beta's own terrain, read ~12.55M blocks out (see FarLandsShift).
                FarLandsShift shift = FarLandsShift.of(WorldGenCycle.fromConfig(), cx, cz);
                yield LegacyBands.beta(seed).generate(cx + shift.dxChunks(), cz + shift.dzChunks()).blocks();
            }
        };
        if (blocks == null) {
            writeFloating(chunk, LegacyBands.indevFloating(seed).levelForChunk(cx, cz), yOffset);
        } else {
            int height = kind == LegacyBandKind.CLASSIC ? ClassicLevel.HEIGHT : BetaTerrain.HEIGHT;
            write(chunk, blocks, height, floorY, yOffset, !kind.voidBelow());
        }
    }

    /**
     * Copy the chunk's 16×16 column of an Indev floating {@code level} into {@code chunk}, old {@code y = 0}
     * at world {@code yOffset}. Void below, and air is never written, so the level's gaps stay empty.
     */
    static void writeFloating(ChunkAccess chunk, IndevFloatingLevel level, int yOffset) {
        int lx0 = IndevLevels.localX(chunk.getPos().getMinBlockX());
        int lz0 = IndevLevels.localZ(chunk.getPos().getMinBlockZ());
        int minY = Math.max(chunk.getMinBuildHeight(), yOffset);
        int maxY = Math.min(chunk.getMaxBuildHeight() - 1, yOffset + IndevFloatingLevel.HEIGHT - 1);
        for (int y = minY; y <= maxY; y++) {
            int oldY = y - yOffset;
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
            int ly = y & 15;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    byte id = level.block(lx0 + x, oldY, lz0 + z);
                    if (id == BetaBlocks.AIR) continue;
                    BlockState state = STATES[id & 0xFF];
                    if (state != null) section.setBlockState(x, ly, z, state, false);
                }
            }
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
    }

    /**
     * Write an old column ({@code blocks[(x·16 + z)·height + y]}, {@link BetaBlocks} ids) — Beta's own layout
     * ({@link BetaTerrain#index}) at {@code height == 128}, shared by Alpha and Infdev; Classic's 64-high levels
     * use the same layout at their own height.
     */
    static void write(ChunkAccess chunk, byte[] blocks, int height, int floorY, int yOffset, boolean stoneBelow) {
        // Void below: start at the old y = 0 so nothing (not even air) is written under the column.
        int minY = Math.max(Math.max(chunk.getMinBuildHeight(), floorY), stoneBelow ? Integer.MIN_VALUE : yOffset);
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
