package games.brennan.dungeontrain.worldgen;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Where the world's floor actually is, as distinct from how low blocks may be placed.
 *
 * <p><b>Two different numbers.</b> A Dungeon Train overworld's {@code dimension_type} runs deeper
 * than its {@code worldgen/noise_settings}: terrain — and with it the bedrock layer
 * {@link games.brennan.dungeontrain.event.BedrockFloorEvents} stamps — starts at the noise floor,
 * and everything below that is an empty basement the portal system stamps its twin structures into.
 * {@link LevelHeightAccessor#getMinBuildHeight()} answers the second question ("how low can a block
 * go"); almost every other caller in this mod means the first ("where does the world start"), and
 * this class is that first answer.</p>
 *
 * <p><b>Same formula vanilla uses.</b> {@code WorldGenerationContext} resolves {@code above_bottom}
 * vertical anchors — the anchor vanilla's own {@code bedrock_floor} surface rule is written against
 * — as {@code max(level.getMinBuildHeight(), generator.getMinY())}. Deriving the floor the same way
 * means DT's bedrock and vanilla's surface rules can never disagree about where the bottom is, and
 * it needs no constant kept in sync with the dimension-type JSON.</p>
 *
 * <p><b>Correct with no basement, too.</b> In a world whose generator starts at the build floor —
 * Compatible Terrain mode, which runs vanilla's {@code minecraft:overworld} dimension type, or any
 * other mod's dimension — the two numbers coincide and every caller behaves exactly as it did before
 * the basement existed. Nothing branches on which world it is in.</p>
 */
public final class WorldFloor {

    private WorldFloor() {}

    /** Y of the world's bedrock layer — the lowest row terrain generation fills. */
    public static int bedrockY(ServerLevel level) {
        return bedrockY(level, level.getChunkSource().getGenerator());
    }

    /** Worldgen-side variant, for features holding a {@link WorldGenLevel}. */
    public static int bedrockY(WorldGenLevel level) {
        return bedrockY(level.getLevel());
    }

    /**
     * Explicit-generator variant, for callers handed the two halves separately — a structure's
     * {@code GenerationContext}, say, which carries a height accessor and a generator but no level.
     */
    public static int bedrockY(LevelHeightAccessor height, ChunkGenerator generator) {
        return bedrockY(height.getMinBuildHeight(), generator.getMinY());
    }

    /** The maths on its own, so it unit-tests without a NeoForge bootstrap. */
    public static int bedrockY(int minBuildHeight, int generatorMinY) {
        return Math.max(minBuildHeight, generatorMinY);
    }

    /**
     * How much empty world sits under the bedrock — 0 in a world whose generator reaches the build
     * floor. The portal system spends this space; nothing else should.
     */
    public static int basementDepth(ServerLevel level) {
        return bedrockY(level) - level.getMinBuildHeight();
    }
}
