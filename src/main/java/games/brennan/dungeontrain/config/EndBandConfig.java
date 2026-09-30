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

    private static ModConfigSpec.BooleanValue featureSpill;

    private EndBandConfig() {}

    /** Define the keys into the COMMON spec under construction. Called once, from the spec's static build. */
    static void define(ModConfigSpec.Builder b) {
        featureSpill = b
                .comment("Carry End-band features across chunk borders. A sampled End chunk (the BetterEnd and BoP",
                        "End passes) is decorated on its own, so trees, pillars, crystals and cave carves that reach",
                        "past its edge stop dead at the chunk line. With this on, each chunk is decorated beside its",
                        "real neighbours and whatever spills over is written into them, the way vanilla does. Only",
                        "chunks generated after the change are affected. Default false (the existing look).")
                .define("endBandFeatureSpill", DEFAULT_FEATURE_SPILL);
    }

    /** Whether sampled End-band chunks exchange feature spill with their neighbours; hardcoded default pre-load. */
    public static boolean featureSpill() {
        return DungeonTrainCommonConfig.isLoaded() && featureSpill != null ? featureSpill.get() : DEFAULT_FEATURE_SPILL;
    }
}
