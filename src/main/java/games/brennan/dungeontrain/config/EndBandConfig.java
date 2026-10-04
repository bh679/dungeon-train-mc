package games.brennan.dungeontrain.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * COMMON config for the <b>sampled End-band passes</b> — the BetterEnd and Biomes O' Plenty End chunks
 * {@code EndBandSampler} copies onto track level. Kept out of {@link DungeonTrainCommonConfig} (already
 * very long) like {@link SpheresProgressionConfig}; its keys are defined into the same COMMON spec from
 * {@code DungeonTrainCommonConfig.build}, right after the spheres keys.
 */
public final class EndBandConfig {

    /** Off: every sampled chunk is decorated alone, as the band has always been generated. */
    public static final boolean DEFAULT_FEATURE_SPILL = false;

    /** On: the BetterEnd band's vanilla End patches are remapped to BetterEnd biomes (#1785). */
    public static final boolean DEFAULT_BETTER_END_ONLY = true;

    /** When a sampled band chunk gets its terrain. */
    public enum Terrain {
        /** During the chunk's own generation, on vanilla's worldgen workers: a chunk never exists without its islands. */
        WORLDGEN,
        /** After the chunk exists, from DT's background sampler pool: the chunk is seen bare until the sample lands. */
        BACKGROUND
    }

    /**
     * Worldgen: the fix for the squares of void a player used to see in the islands until each chunk's
     * sample landed. The blocks written are identical either way; only when they land differs.
     */
    public static final Terrain DEFAULT_TERRAIN = Terrain.WORLDGEN;

    private static ModConfigSpec.BooleanValue featureSpill;
    private static ModConfigSpec.BooleanValue betterEndOnly;
    private static ModConfigSpec.EnumValue<Terrain> terrain;

    private EndBandConfig() {}

    /** Define the keys into the COMMON spec under construction. Called once, from the spec's static build. */
    static void define(ModConfigSpec.Builder b) {
        terrain = b
                .comment("When a sampled End-band chunk (the BetterEnd and BoP End passes) gets its terrain.",
                        "WORLDGEN: during the chunk's own generation, so it never exists without its islands and",
                        "nothing bare is ever sent to a client. Costs the sample on vanilla's worldgen threads.",
                        "BACKGROUND: after the chunk exists, from Dungeon Train's own sampler threads",
                        "(worldgenSamplerThreads) — the previous behaviour, where chunks could be seen as squares",
                        "of void until their sample landed. Default WORLDGEN.")
                .defineEnum("endBandTerrain", DEFAULT_TERRAIN);
        featureSpill = b
                .comment("Carry End-band features across chunk borders. A sampled End chunk (the BetterEnd and BoP",
                        "End passes) is decorated on its own, so trees, pillars, crystals and cave carves that reach",
                        "past its edge stop dead at the chunk line. With this on, each chunk is decorated beside its",
                        "real neighbours and whatever spills over is written into them, the way vanilla does. Only",
                        "chunks generated after the change are affected. Default false (the existing look).")
                .define("endBandFeatureSpill", DEFAULT_FEATURE_SPILL);
        betterEndOnly = b
                .comment("Keep the BetterEnd End band all BetterEnd. BetterEnd's End map keeps vanilla's End biomes",
                        "alongside its own, and vanilla's end_barrens and small_end_islands place nothing at all, so",
                        "the band copied them as flat bare end stone beside lush BetterEnd islands. With this on,",
                        "those vanilla patches are remapped to BetterEnd biomes in the band's samples and labels only;",
                        "the real End dimension is unchanged. Only chunks generated after the change are affected,",
                        "so an existing world keeps its vanilla patches next to remapped new chunks. Default true.")
                .define("endBandBetterEndOnly", DEFAULT_BETTER_END_ONLY);
    }

    /** When sampled End-band chunks get their terrain; hardcoded default pre-load. */
    public static Terrain terrain() {
        return DungeonTrainCommonConfig.isLoaded() && terrain != null ? terrain.get() : DEFAULT_TERRAIN;
    }

    /** True when a sampled band chunk's terrain is written during its own generation ({@link Terrain#WORLDGEN}). */
    public static boolean terrainInWorldgen() {
        return terrain() == Terrain.WORLDGEN;
    }

    /** Whether the BetterEnd band's vanilla End patches are remapped to BetterEnd biomes; hardcoded default pre-load. */
    public static boolean betterEndOnly() {
        return DungeonTrainCommonConfig.isLoaded() && betterEndOnly != null ? betterEndOnly.get() : DEFAULT_BETTER_END_ONLY;
    }

    /** Whether sampled End-band chunks exchange feature spill with their neighbours; hardcoded default pre-load. */
    public static boolean featureSpill() {
        return DungeonTrainCommonConfig.isLoaded() && featureSpill != null ? featureSpill.get() : DEFAULT_FEATURE_SPILL;
    }
}
