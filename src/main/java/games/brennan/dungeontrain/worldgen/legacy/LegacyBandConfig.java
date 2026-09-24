package games.brennan.dungeontrain.worldgen.legacy;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.EnumMap;
import java.util.Map;

/**
 * COMMON-config keys for the legacy bands, one group per {@link LegacyBandKind}
 * ({@code legacy<Kind>Enabled / HoldBlocks / FadeBlocks / LeadGapBlocks} in the worldgen section of
 * {@code dungeontrain-common.toml}). Defined from {@link DungeonTrainCommonConfig}'s builder via
 * {@link #define} so they live in the same spec (and the same reload → {@code WorldGenCycle} invalidation),
 * but kept out of that file's already-long field list.
 */
public final class LegacyBandConfig {

    public static final int MIN_BLOCKS = 0;
    public static final int MAX_BLOCKS = 100_000_000;

    /** Shipped defaults per kind: {@code {hold, fade, leadGap}}. */
    public record Defaults(boolean enabled, int hold, int fade, int leadGap) {}

    public static final Defaults LARGE_BIOMES_DEFAULTS = new Defaults(false, 6000, 480, 3000);
    public static final Defaults AMPLIFIED_DEFAULTS = new Defaults(true, 6000, 480, 3000);
    public static final Defaults BETA_DEFAULTS = new Defaults(true, 6000, 480, 3000);
    public static final Defaults SKYLANDS_DEFAULTS = new Defaults(true, 6000, 480, 3000);
    public static final Defaults ALPHA_DEFAULTS = new Defaults(true, 6000, 480, 3000);
    public static final Defaults INFDEV_DEFAULTS = new Defaults(true, 8000, 480, 3000);
    public static final Defaults FLOATING_DEFAULTS = new Defaults(true, 6000, 480, 3000);
    public static final Defaults CLASSIC_DEFAULTS = new Defaults(true, 6000, 480, 3000);
    public static final Defaults VOID_DEFAULTS = new Defaults(true, 1000, 480, 3000);
    public static final Defaults FAR_LANDS_DEFAULTS = new Defaults(true, 4320, 480, 3000);

    /** Shipped share of the Alpha core (counted from its end) that is winter mode. */
    public static final double ALPHA_WINTER_SHARE_DEFAULT = 0.5D;

    private record Values(ModConfigSpec.BooleanValue enabled, ModConfigSpec.IntValue hold,
                          ModConfigSpec.IntValue fade, ModConfigSpec.IntValue leadGap, Defaults defaults) {}

    private static final Map<LegacyBandKind, Values> VALUES = new EnumMap<>(LegacyBandKind.class);
    private static ModConfigSpec.DoubleValue alphaWinterShare;

    private LegacyBandConfig() {}

    private static Defaults defaultsFor(LegacyBandKind kind) {
        return switch (kind) {
            case LARGE_BIOMES -> LARGE_BIOMES_DEFAULTS;
            case AMPLIFIED -> AMPLIFIED_DEFAULTS;
            case BETA -> BETA_DEFAULTS;
            case SKYLANDS -> SKYLANDS_DEFAULTS;
            case ALPHA -> ALPHA_DEFAULTS;
            case INFDEV -> INFDEV_DEFAULTS;
            case FLOATING -> FLOATING_DEFAULTS;
            case CLASSIC -> CLASSIC_DEFAULTS;
            case FAR_LANDS -> FAR_LANDS_DEFAULTS;
            case VOID -> VOID_DEFAULTS;
        };
    }

    private static String label(LegacyBandKind kind) {
        return switch (kind) {
            case LARGE_BIOMES -> "Large Biomes";
            case AMPLIFIED -> "Amplified";
            case BETA -> "Beta 1.7.3";
            case SKYLANDS -> "Skylands";
            case ALPHA -> "Alpha 1.1.2";
            case INFDEV -> "Infdev";
            case FLOATING -> "Indev floating";
            case CLASSIC -> "Classic 0.30";
            case FAR_LANDS -> "Far Lands";
            case VOID -> "Void";
        };
    }

    /** Define every kind's keys on {@code b}. Called once, from {@code DungeonTrainCommonConfig}'s spec build. */
    public static void define(ModConfigSpec.Builder b) {
        for (LegacyBandKind kind : LegacyBandKind.values()) {
            Defaults d = defaultsFor(kind);
            String prefix = "legacy" + kind.configStem();
            ModConfigSpec.BooleanValue enabled = b
                    .comment(label(kind) + " legacy band — a stretch of the looping world-gen cycle, after the stacks",
                            "band, where the terrain comes from a port of that version's world generator (terrain,",
                            "surface, caves and decoration). Set false to drop it from the cycle.")
                    .define(prefix + "Enabled", d.enabled());
            ModConfigSpec.IntValue hold = b
                    .comment("Blocks of full-strength " + label(kind) + " terrain. 0 drops the band from the cycle.")
                    .defineInRange(prefix + "HoldBlocks", d.hold(), MIN_BLOCKS, MAX_BLOCKS);
            ModConfigSpec.IntValue fade = b
                    .comment("Entry and exit fade (each side): chunks switch between modern and " + label(kind),
                            "terrain one at a time, leaving old-world chunk walls. 0 = hard edge.")
                    .defineInRange(prefix + "FadeBlocks", d.fade(), MIN_BLOCKS, MAX_BLOCKS);
            ModConfigSpec.IntValue leadGap = b
                    .comment("Plain-overworld gap before the " + label(kind) + " band's entry fade.")
                    .defineInRange(prefix + "LeadGapBlocks", d.leadGap(), MIN_BLOCKS, MAX_BLOCKS);
            VALUES.put(kind, new Values(enabled, hold, fade, leadGap, d));
        }
        alphaWinterShare = b
                .comment("Share of the Alpha 1.1.2 band's core, counted back from its end, generated in Alpha's",
                        "winter mode (frozen sea, snow everywhere). 0 = never winter, 1 = the whole band.")
                .defineInRange("legacyAlphaWinterShare", ALPHA_WINTER_SHARE_DEFAULT, 0.0D, 1.0D);
    }

    /** Live {@code legacyAlphaWinterShare}; the shipped default before the config loads. */
    public static double alphaWinterShare() {
        ModConfigSpec.DoubleValue v = alphaWinterShare;
        return v == null || !DungeonTrainCommonConfig.isLoaded() ? ALPHA_WINTER_SHARE_DEFAULT : v.get();
    }

    /** Whether {@code kind}'s band is enabled; the shipped default before the config loads. */
    public static boolean isEnabled(LegacyBandKind kind) {
        Values v = VALUES.get(kind);
        if (v == null) return defaultsFor(kind).enabled();
        return DungeonTrainCommonConfig.isLoaded() ? v.enabled().get() : v.defaults().enabled();
    }

    /**
     * The live legacy layout in cycle order. A disabled kind gets a zero-hold span so it adds nothing to
     * the period. Fresh array per call — {@code WorldGenCycle} memoises the result.
     */
    public static LegacySpan[] spans() {
        LegacyBandKind[] kinds = LegacyBandKind.values();
        LegacySpan[] out = new LegacySpan[kinds.length];
        boolean loaded = DungeonTrainCommonConfig.isLoaded();
        for (int i = 0; i < kinds.length; i++) {
            LegacyBandKind kind = kinds[i];
            Values v = VALUES.get(kind);
            Defaults d = defaultsFor(kind);
            boolean on = v == null || !loaded ? d.enabled() : v.enabled().get();
            int hold = v == null || !loaded ? d.hold() : v.hold().get();
            int fade = v == null || !loaded ? d.fade() : v.fade().get();
            int lead = v == null || !loaded ? d.leadGap() : v.leadGap().get();
            out[i] = new LegacySpan(kind, lead, fade, on ? hold : 0);
        }
        return out;
    }
}
