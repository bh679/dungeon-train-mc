package games.brennan.dungeontrain.config;

import games.brennan.dungeontrain.worldgen.SpheresSegments;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * COMMON config for the spheres band's <b>progression</b> — where along the band spheres start being
 * cut from other dimensions, where structures run denser, and where the Nether sky takes over. Kept
 * out of {@link DungeonTrainCommonConfig} (already very long); its keys are defined into the same
 * COMMON spec from {@code DungeonTrainCommonConfig.build}, right after the spheres End-sky keys, so they
 * sit beside the rest of the spheres settings in the file.
 *
 * <p>Every offset counts blocks into the band core (from the end of the entry fade), like
 * {@code spheresEndSkyStartBlocks}. Out-of-order offsets are clamped monotonic by
 * {@link SpheresSegments#of}.</p>
 */
public final class SpheresProgressionConfig {

    public static final int MIN_OFFSET_BLOCKS = 0;
    public static final int MAX_OFFSET_BLOCKS = 100_000_000;
    public static final int DEFAULT_NETHER_MIX_START_BLOCKS = 5000;
    public static final int DEFAULT_END_MIX_START_BLOCKS = 6000;
    public static final int DEFAULT_STRUCTURE_BOOST_START_BLOCKS = 9000;
    public static final int DEFAULT_STRUCTURE_BOOST_END_BLOCKS = 12000;
    public static final int DEFAULT_NETHER_SKY_START_BLOCKS = 12000;
    public static final double DEFAULT_STRUCTURE_CHANCE = 0.08;
    public static final double DEFAULT_STRUCTURE_BOOST_MULTIPLIER = 5.0;
    public static final int MIN_WEIGHT = 0;
    public static final int MAX_WEIGHT = 1000;
    public static final int DEFAULT_WEIGHT = 1;
    public static final int DEFAULT_APPLY_PER_TICK = 4;
    public static final int DEFAULT_SAMPLER_THREADS = 2;

    private static ModConfigSpec.IntValue netherMixStart;
    private static ModConfigSpec.IntValue endMixStart;
    private static ModConfigSpec.IntValue structureBoostStart;
    private static ModConfigSpec.IntValue structureBoostEnd;
    private static ModConfigSpec.IntValue netherSkyStart;
    private static ModConfigSpec.DoubleValue structureChance;
    private static ModConfigSpec.DoubleValue structureBoostMultiplier;
    private static ModConfigSpec.IntValue overworldWeight;
    private static ModConfigSpec.IntValue netherWeight;
    private static ModConfigSpec.IntValue endWeight;
    private static ModConfigSpec.IntValue applyPerTick;
    private static ModConfigSpec.IntValue samplerThreads;

    private SpheresProgressionConfig() {}

    /** Define the keys into the COMMON spec under construction. Called once, from the spec's static build. */
    static void define(ModConfigSpec.Builder b) {
        netherMixStart = b
                .comment("Blocks into the spheres band where spheres start being cut from the Nether as well as the",
                        "overworld. Default 5000.")
                .defineInRange("spheresNetherMixStartBlocks", DEFAULT_NETHER_MIX_START_BLOCKS,
                        MIN_OFFSET_BLOCKS, MAX_OFFSET_BLOCKS);
        endMixStart = b
                .comment("Blocks into the spheres band where spheres can also be cut from the End. Default 6000.")
                .defineInRange("spheresEndMixStartBlocks", DEFAULT_END_MIX_START_BLOCKS,
                        MIN_OFFSET_BLOCKS, MAX_OFFSET_BLOCKS);
        structureBoostStart = b
                .comment("Blocks into the spheres band where the structure chance is multiplied by",
                        "spheresStructureBoostMultiplier. Default 9000.")
                .defineInRange("spheresStructureBoostStartBlocks", DEFAULT_STRUCTURE_BOOST_START_BLOCKS,
                        MIN_OFFSET_BLOCKS, MAX_OFFSET_BLOCKS);
        structureBoostEnd = b
                .comment("Blocks into the spheres band where the structure boost ends. Default 12000.")
                .defineInRange("spheresStructureBoostEndBlocks", DEFAULT_STRUCTURE_BOOST_END_BLOCKS,
                        MIN_OFFSET_BLOCKS, MAX_OFFSET_BLOCKS);
        netherSkyStart = b
                .comment("Blocks into the spheres band where the End sky hands over to the Nether sky, fog and",
                        "lighting for the rest of the band. Client-side visual only. Default 12000.")
                .defineInRange("spheresNetherSkyStartBlocks", DEFAULT_NETHER_SKY_START_BLOCKS,
                        MIN_OFFSET_BLOCKS, MAX_OFFSET_BLOCKS);
        structureChance = b
                .comment("Chance 0..1 a sphere is built around a structure of its dimension. Default 0.08.")
                .defineInRange("spheresStructureChance", DEFAULT_STRUCTURE_CHANCE, 0.0, 1.0);
        structureBoostMultiplier = b
                .comment("Multiplier on spheresStructureChance inside the structure-boost stretch. Default 5.")
                .defineInRange("spheresStructureBoostMultiplier", DEFAULT_STRUCTURE_BOOST_MULTIPLIER, 0.0, 1000.0);
        overworldWeight = b
                .comment("Relative weight of overworld spheres once the band mixes dimensions. Default 1.")
                .defineInRange("spheresMixOverworldWeight", DEFAULT_WEIGHT, MIN_WEIGHT, MAX_WEIGHT);
        netherWeight = b
                .comment("Relative weight of Nether spheres once the band mixes dimensions. Default 1.")
                .defineInRange("spheresMixNetherWeight", DEFAULT_WEIGHT, MIN_WEIGHT, MAX_WEIGHT);
        endWeight = b
                .comment("Relative weight of End spheres once the band mixes in the End. Default 1.")
                .defineInRange("spheresMixEndWeight", DEFAULT_WEIGHT, MIN_WEIGHT, MAX_WEIGHT);
        applyPerTick = b
                .comment("Other-dimension sphere chunks filled in per server tick once generated off-thread.",
                        "Default 4.")
                .defineInRange("spheresForeignApplyPerTick", DEFAULT_APPLY_PER_TICK, 1, 64);
        samplerThreads = b
                .comment("Background threads generating other-dimension sphere terrain. Default 2. Takes effect",
                        "on restart.")
                .defineInRange("spheresSamplerThreads", DEFAULT_SAMPLER_THREADS, 1, 8);
    }

    /** The progression layout; hardcoded defaults pre-load. */
    public static SpheresSegments segments() {
        boolean loaded = DungeonTrainCommonConfig.isLoaded() && netherMixStart != null;
        return SpheresSegments.of(
                DungeonTrainCommonConfig.getSpheresEndSkyStartBlocks(),
                loaded ? netherMixStart.get() : DEFAULT_NETHER_MIX_START_BLOCKS,
                loaded ? endMixStart.get() : DEFAULT_END_MIX_START_BLOCKS,
                loaded ? structureBoostStart.get() : DEFAULT_STRUCTURE_BOOST_START_BLOCKS,
                loaded ? structureBoostEnd.get() : DEFAULT_STRUCTURE_BOOST_END_BLOCKS,
                loaded ? netherSkyStart.get() : DEFAULT_NETHER_SKY_START_BLOCKS,
                loaded ? structureChance.get() : DEFAULT_STRUCTURE_CHANCE,
                loaded ? structureBoostMultiplier.get() : DEFAULT_STRUCTURE_BOOST_MULTIPLIER,
                loaded ? overworldWeight.get() : DEFAULT_WEIGHT,
                loaded ? netherWeight.get() : DEFAULT_WEIGHT,
                loaded ? endWeight.get() : DEFAULT_WEIGHT);
    }

    /** Chunks of foreign sphere terrain applied per server tick. */
    public static int applyPerTick() {
        return DungeonTrainCommonConfig.isLoaded() && applyPerTick != null ? applyPerTick.get() : DEFAULT_APPLY_PER_TICK;
    }

    /** Background sampler threads. */
    public static int samplerThreads() {
        return DungeonTrainCommonConfig.isLoaded() && samplerThreads != null ? samplerThreads.get() : DEFAULT_SAMPLER_THREADS;
    }
}
