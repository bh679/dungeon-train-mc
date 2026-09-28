package games.brennan.dungeontrain.config;

import games.brennan.dungeontrain.worldgen.SpheresSegments;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * COMMON config for the spheres band's <b>progression</b> — where along the band spheres start being
 * cut from other dimensions and where structures run denser. Kept
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
    public static final int DEFAULT_NETHER_MIX_START_BLOCKS = 1750;
    public static final int DEFAULT_END_MIX_START_BLOCKS = 2500;
    public static final int DEFAULT_STRUCTURE_BOOST_START_BLOCKS = 3250;
    public static final int DEFAULT_STRUCTURE_BOOST_END_BLOCKS = 6250;

    /**
     * The offsets v5 shipped (Nether mix / End mix / boost start / boost end) before the band shrank to
     * 6550. The v5 -> v6 migration moves only values still at these — see {@link #migrateV5Offsets}.
     */
    public static final int V5_NETHER_MIX_START_BLOCKS = 5000;
    public static final int V5_END_MIX_START_BLOCKS = 6000;
    public static final int V5_STRUCTURE_BOOST_START_BLOCKS = 9000;
    public static final int V5_STRUCTURE_BOOST_END_BLOCKS = 12000;
    public static final double DEFAULT_STRUCTURE_CHANCE = 0.08;
    public static final double DEFAULT_STRUCTURE_BOOST_MULTIPLIER = 5.0;
    public static final double DEFAULT_STRUCTURE_BOOST_PEAK_MULTIPLIER = 20.0;
    public static final int MIN_WEIGHT = 0;
    public static final int MAX_WEIGHT = 1000;
    public static final int DEFAULT_WEIGHT = 1;
    public static final int MIN_END_SKY_EXIT_FADE_BLOCKS = 0;
    public static final int MAX_END_SKY_EXIT_FADE_BLOCKS = 2000;
    public static final int DEFAULT_END_SKY_EXIT_FADE_BLOCKS = 550;
    public static final int MIN_EXIT_BLOCKS = 0;
    public static final int MAX_EXIT_BLOCKS = 100_000;
    public static final int DEFAULT_EXIT_TAPER_BLOCKS = 550;
    public static final int DEFAULT_EXIT_VOID_BLOCKS = 100;
    public static final int DEFAULT_APPLY_PER_TICK = 4;
    public static final int DEFAULT_SAMPLER_THREADS = 2;

    private static ModConfigSpec.IntValue netherMixStart;
    private static ModConfigSpec.IntValue endMixStart;
    private static ModConfigSpec.IntValue structureBoostStart;
    private static ModConfigSpec.IntValue structureBoostEnd;
    private static ModConfigSpec.IntValue endSkyExitFade;
    private static ModConfigSpec.IntValue exitTaper;
    private static ModConfigSpec.IntValue exitVoid;
    private static ModConfigSpec.DoubleValue structureChance;
    private static ModConfigSpec.DoubleValue structureBoostMultiplier;
    private static ModConfigSpec.DoubleValue structureBoostPeakMultiplier;
    private static ModConfigSpec.IntValue overworldWeight;
    private static ModConfigSpec.IntValue netherWeight;
    private static ModConfigSpec.IntValue endWeight;
    private static ModConfigSpec.IntValue applyPerTick;
    private static ModConfigSpec.IntValue samplerThreads;

    private SpheresProgressionConfig() {}

    /** Define the keys into the COMMON spec under construction. Called once, from the spec's static build. */
    static void define(ModConfigSpec.Builder b) {
        endSkyExitFade = b
                .comment("Crossfade span (blocks) from the End sky back to the overworld sky over the last blocks of",
                        "the spheres band. Clamped to a quarter of the band. 0 = hard switch. Client-side visual only.",
                        "It ends where the spheres run out (before spheresExitVoidBlocks). Default 550.")
                .defineInRange("spheresEndSkyExitFadeBlocks", DEFAULT_END_SKY_EXIT_FADE_BLOCKS,
                        MIN_END_SKY_EXIT_FADE_BLOCKS, MAX_END_SKY_EXIT_FADE_BLOCKS);
        exitTaper = b
                .comment("Blocks over which spheres thin out and shrink to nothing near the end of the spheres band,",
                        "ending where spheresExitVoidBlocks begin. 0 = full spheres to the end. Default 550.")
                .defineInRange("spheresExitTaperBlocks", DEFAULT_EXIT_TAPER_BLOCKS, MIN_EXIT_BLOCKS, MAX_EXIT_BLOCKS);
        exitVoid = b
                .comment("Blocks of empty void (no spheres, overworld sky) closing the spheres band. Default 100.")
                .defineInRange("spheresExitVoidBlocks", DEFAULT_EXIT_VOID_BLOCKS, MIN_EXIT_BLOCKS, MAX_EXIT_BLOCKS);
        netherMixStart = b
                .comment("Blocks into the spheres band where spheres start being cut from the Nether as well as the",
                        "overworld. Default 1750.")
                .defineInRange("spheresNetherMixStartBlocks", DEFAULT_NETHER_MIX_START_BLOCKS,
                        MIN_OFFSET_BLOCKS, MAX_OFFSET_BLOCKS);
        endMixStart = b
                .comment("Blocks into the spheres band where spheres can also be cut from the End. Default 2500.")
                .defineInRange("spheresEndMixStartBlocks", DEFAULT_END_MIX_START_BLOCKS,
                        MIN_OFFSET_BLOCKS, MAX_OFFSET_BLOCKS);
        structureBoostStart = b
                .comment("Blocks into the spheres band where the structure-chance boost starts (ramping from",
                        "spheresStructureBoostMultiplier up to the peak and back). Default 3250.")
                .defineInRange("spheresStructureBoostStartBlocks", DEFAULT_STRUCTURE_BOOST_START_BLOCKS,
                        MIN_OFFSET_BLOCKS, MAX_OFFSET_BLOCKS);
        structureBoostEnd = b
                .comment("Blocks into the spheres band where the structure boost ends. Default 6250.")
                .defineInRange("spheresStructureBoostEndBlocks", DEFAULT_STRUCTURE_BOOST_END_BLOCKS,
                        MIN_OFFSET_BLOCKS, MAX_OFFSET_BLOCKS);
        structureChance = b
                .comment("Chance 0..1 a sphere is built around a structure of its dimension. Default 0.08.")
                .defineInRange("spheresStructureChance", DEFAULT_STRUCTURE_CHANCE, 0.0, 1.0);
        structureBoostMultiplier = b
                .comment("Multiplier on spheresStructureChance at the start and end of the structure-boost stretch.",
                        "It ramps up to spheresStructureBoostPeakMultiplier at the stretch's midpoint and back down.",
                        "Default 5.")
                .defineInRange("spheresStructureBoostMultiplier", DEFAULT_STRUCTURE_BOOST_MULTIPLIER, 0.0, 1000.0);
        structureBoostPeakMultiplier = b
                .comment("Multiplier on spheresStructureChance at the midpoint of the structure-boost stretch — the",
                        "top of the ramp. The chance is capped at 1 (every sphere a structure). Default 20.")
                .defineInRange("spheresStructureBoostPeakMultiplier", DEFAULT_STRUCTURE_BOOST_PEAK_MULTIPLIER,
                        0.0, 1000.0);
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
                loaded ? structureChance.get() : DEFAULT_STRUCTURE_CHANCE,
                loaded ? structureBoostMultiplier.get() : DEFAULT_STRUCTURE_BOOST_MULTIPLIER,
                loaded ? structureBoostPeakMultiplier.get() : DEFAULT_STRUCTURE_BOOST_PEAK_MULTIPLIER,
                loaded ? overworldWeight.get() : DEFAULT_WEIGHT,
                loaded ? netherWeight.get() : DEFAULT_WEIGHT,
                loaded ? endWeight.get() : DEFAULT_WEIGHT);
    }

    /**
     * v5 -> v6: the band shrank to 6550 and its progression moved earlier. Each offset still at v5's
     * shipped default moves to the new one; a chosen offset is left alone. Called from
     * {@link DungeonTrainCommonConfig#runPendingMigrations()}.
     */
    static void migrateV5Offsets(int from) {
        if (netherMixStart == null) return;
        DungeonTrainCommonConfig.migrateBandHold("spheresNetherMixStartBlocks", netherMixStart,
                V5_NETHER_MIX_START_BLOCKS, DEFAULT_NETHER_MIX_START_BLOCKS, from);
        DungeonTrainCommonConfig.migrateBandHold("spheresEndMixStartBlocks", endMixStart,
                V5_END_MIX_START_BLOCKS, DEFAULT_END_MIX_START_BLOCKS, from);
        DungeonTrainCommonConfig.migrateBandHold("spheresStructureBoostStartBlocks", structureBoostStart,
                V5_STRUCTURE_BOOST_START_BLOCKS, DEFAULT_STRUCTURE_BOOST_START_BLOCKS, from);
        DungeonTrainCommonConfig.migrateBandHold("spheresStructureBoostEndBlocks", structureBoostEnd,
                V5_STRUCTURE_BOOST_END_BLOCKS, DEFAULT_STRUCTURE_BOOST_END_BLOCKS, from);
    }

    /** End-sky exit crossfade span in blocks; hardcoded default pre-load. */
    public static int endSkyExitFadeBlocks() {
        return DungeonTrainCommonConfig.isLoaded() && endSkyExitFade != null ? endSkyExitFade.get() : DEFAULT_END_SKY_EXIT_FADE_BLOCKS;
    }

    /** Exit taper span in blocks; hardcoded default pre-load. */
    public static int exitTaperBlocks() {
        return DungeonTrainCommonConfig.isLoaded() && exitTaper != null ? exitTaper.get() : DEFAULT_EXIT_TAPER_BLOCKS;
    }

    /** Closing empty-void span in blocks; hardcoded default pre-load. */
    public static int exitVoidBlocks() {
        return DungeonTrainCommonConfig.isLoaded() && exitVoid != null ? exitVoid.get() : DEFAULT_EXIT_VOID_BLOCKS;
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
