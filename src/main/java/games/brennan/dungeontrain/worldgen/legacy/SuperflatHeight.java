package games.brennan.dungeontrain.worldgen.legacy;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Where the Superflat band's sheet lies, for structure siting. Villages in the band are placed at vanilla
 * Superflat's rate — the band is plains, and vanilla's {@code minecraft:villages} set runs there unchanged —
 * but siting asks the overworld generator's {@code getBaseHeight}, which answers with modern noise terrain.
 * This answers with the sheet instead, using the same loop math as {@link LegacyChunkWriter#fill}, so a
 * village stands on the grass the chunk was actually given.
 *
 * <p>Published per world from {@code NetherBandContextEvents} alongside {@link LegacyBiomes}; read on the
 * height-query path, so the lookup is a volatile read plus the cached chunk classification.</p>
 */
public final class SuperflatHeight {

    private record Context(Object overworldGenerator, long seed, int firstLoopGrassY, int minBuildY) {}

    private static volatile Context current;

    private SuperflatHeight() {}

    /** Resolve and publish this world's sheet height; clears it for a world without a train. */
    public static void publish(ServerLevel overworld) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) {
            current = null;
            return;
        }
        current = new Context(overworld.getChunkSource().getGenerator(), data.getGenerationSeed(),
                LegacyBands.yOffset(LegacyBandKind.SUPERFLAT, overworld), overworld.getMinBuildHeight());
    }

    public static void clear() {
        current = null;
    }

    /**
     * World Y of the Superflat grass at block column {@code (blockX, blockZ)} of {@code generator}, or
     * {@code null} when that column is not Superflat (or {@code generator} is not the train overworld's).
     */
    public static Integer grassYForColumn(Object generator, int blockX, int blockZ) {
        Context c = current;
        if (c == null || generator != c.overworldGenerator()) return null;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (LegacyBands.kindOfChunk(c.seed(), cycle, blockX >> 4, blockZ >> 4) != LegacyBandKind.SUPERFLAT) {
            return null;
        }
        return LegacyBands.superflatGrassY(c.firstLoopGrassY(), cycle.cycleIndex(blockX), c.minBuildY());
    }

    /** First free Y above the sheet — what every heightmap reads on a bare grass/dirt/bedrock sheet. */
    public static int baseHeight(int grassY) {
        return grassY + 1;
    }

    /**
     * The sheet as a column from {@code minBuildY} upward: {@link LegacyChunkWriter#SUPERFLAT_LAYERS} with the
     * grass at {@code grassY}, air everywhere else (Superflat is void below).
     */
    public static BlockState[] column(int grassY, int minBuildY, int height) {
        BlockState[] states = new BlockState[height];
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int i = 0; i < height; i++) {
            int layer = layerIndexAt(grassY, minBuildY + i);
            states[i] = layer < 0 ? air : LegacyChunkWriter.SUPERFLAT_LAYERS[layer];
        }
        return states;
    }

    /** Index into {@link LegacyChunkWriter#SUPERFLAT_LAYERS} at world {@code y}, or -1 for air. */
    static int layerIndexAt(int grassY, int y) {
        int layer = grassY - y;
        return layer >= 0 && layer < LegacyChunkWriter.SUPERFLAT_LAYERS.length ? layer : -1;
    }

    /** A village ({@code #minecraft:village}) — the one structure family the Superflat band lets place. */
    public static boolean isVillage(RegistryAccess registries, Structure structure) {
        return registries.registryOrThrow(Registries.STRUCTURE).wrapAsHolder(structure).is(StructureTags.VILLAGE);
    }
}
