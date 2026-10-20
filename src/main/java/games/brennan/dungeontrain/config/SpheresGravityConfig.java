package games.brennan.dungeontrain.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * COMMON config for <b>sphere gravity</b> — each floating sphere in the spheres band pulls players, mobs,
 * items and projectiles toward its centre ({@code worldgen.SphereGravity}). Kept out of
 * {@link DungeonTrainCommonConfig} (already very long), defined into the same COMMON spec right after
 * {@link SpheresProgressionConfig}. Read on both sides: the server pulls everything but players, each
 * client pulls its own player.
 */
public final class SpheresGravityConfig {

    public static final boolean DEFAULT_ENABLED = true;
    public static final double DEFAULT_STRENGTH = 0.6;
    public static final double DEFAULT_HOLD = 3.0;
    public static final double MIN_HOLD = 1.0;
    public static final double MAX_HOLD = 8.0;
    public static final double DEFAULT_REACH_RADII = 4.0;
    public static final double MIN_REACH_RADII = 1.0;
    public static final double MAX_REACH_RADII = 8.0;
    public static final double DEFAULT_RIDER_FACTOR = 0.3;

    private static ModConfigSpec.BooleanValue enabled;
    private static ModConfigSpec.DoubleValue strength;
    private static ModConfigSpec.DoubleValue hold;
    private static ModConfigSpec.DoubleValue reachRadii;
    private static ModConfigSpec.DoubleValue riderFactor;

    private SpheresGravityConfig() {}

    /** Define the keys into the COMMON spec under construction. Called once, from the spec's static build. */
    static void define(ModConfigSpec.Builder b) {
        enabled = b
                .comment("Spheres in the spheres band have gravity: each pulls players, mobs, dropped items, XP and",
                        "projectiles toward its centre, on top of normal gravity. The pull stops while you are",
                        "standing or swimming on or in a sphere, and comes back when you jump. Default true.")
                .define("spheresGravityEnabled", DEFAULT_ENABLED);
        strength = b
                .comment("Pull at a sphere's surface as a multiple of normal gravity, for the largest sphere",
                        "(radius 40); smaller spheres pull proportionally less. Inside the sphere the pull stays",
                        "at this surface value, never stronger. 0 = off. Default 0.6.")
                .defineInRange("spheresGravityStrength", DEFAULT_STRENGTH, 0.0, 10.0);
        hold = b
                .comment("How the pull drops off between the surface and the reach: slow at first, fast at the",
                        "end. 1 = a straight ramp down. 3 = still 88% of the surface pull half way out and about",
                        "half at 80%, then falls away quickly. Higher holds on longer and drops harder. Default 3.")
                .defineInRange("spheresGravityHold", DEFAULT_HOLD, MIN_HOLD, MAX_HOLD);
        reachRadii = b
                .comment("How far a sphere's pull reaches, in multiples of its radius from its centre; the pull",
                        "is gone at this edge (see spheresGravityHold for the shape). Default 4.0.")
                .defineInRange("spheresGravityReachRadii", DEFAULT_REACH_RADII, MIN_REACH_RADII, MAX_REACH_RADII);
        riderFactor = b
                .comment("Fraction of the pull felt by anyone out on the train (a roof, a flatbed). Inside a",
                        "carriage, under its roof, nothing is felt. Default 0.3.")
                .defineInRange("spheresGravityRiderFactor", DEFAULT_RIDER_FACTOR, 0.0, 1.0);
    }

    private static boolean loaded() {
        return DungeonTrainCommonConfig.isLoaded() && enabled != null;
    }

    public static boolean enabled() {
        return loaded() ? enabled.get() : DEFAULT_ENABLED;
    }

    public static double strength() {
        return loaded() ? strength.get() : DEFAULT_STRENGTH;
    }

    public static double hold() {
        return loaded() ? hold.get() : DEFAULT_HOLD;
    }

    public static double reachRadii() {
        return loaded() ? reachRadii.get() : DEFAULT_REACH_RADII;
    }

    public static double riderFactor() {
        return loaded() ? riderFactor.get() : DEFAULT_RIDER_FACTOR;
    }
}
