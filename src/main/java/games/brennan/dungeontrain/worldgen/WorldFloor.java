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

    /**
     * Whether a write at {@code y} would land in the basement rather than the world.
     *
     * <p>The rule world generation is held to: vanilla's own floor test is
     * {@code y < getMinBuildHeight()}, which in a DT world lets the whole 80-block basement through.
     * Structures anchored at an <i>absolute</i> low Y — {@code trial_chambers} at −40..−20,
     * {@code ancient_city} at −27 — resolve straight into it. Below the terrain floor is the portal
     * system's space, so generation stops here instead.</p>
     */
    public static boolean isBelowFloor(int y, int bedrockY) {
        return y < bedrockY;
    }

    /**
     * Whether a structure's whole bounding box sits under the floor — no part of it reaches terrain,
     * so registering the start would only leave a {@code /locate} target over empty basement. A
     * structure that straddles the floor keeps its start and is clipped write-by-write instead.
     */
    public static boolean entirelyBelowFloor(int boxMaxY, int bedrockY) {
        return boxMaxY < bedrockY;
    }
}
