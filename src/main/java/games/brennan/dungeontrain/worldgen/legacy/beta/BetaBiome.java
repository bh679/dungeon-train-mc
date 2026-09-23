package games.brennan.dungeontrain.worldgen.legacy.beta;

/**
 * Beta 1.7.3's climate biomes. Each carries its surface blocks and the vanilla overworld biome it is
 * shown as (grass tint, weather, mob spawns) — DT never adds biomes to the overworld source, so every
 * mapping must be one the overworld already generates.
 */
public enum BetaBiome {
    RAINFOREST("jungle", false),
    SWAMPLAND("swamp", false),
    SEASONAL_FOREST("forest", false),
    FOREST("forest", false),
    SAVANNA("savanna", false),
    SHRUBLAND("plains", false),
    TAIGA("snowy_taiga", false),
    DESERT("desert", true),
    PLAINS("plains", false),
    ICE_DESERT("snowy_plains", true),
    TUNDRA("snowy_plains", false);

    private static final BetaBiome[] LOOKUP = new BetaBiome[64 * 64];

    static {
        for (int t = 0; t < 64; t++) {
            for (int r = 0; r < 64; r++) {
                LOOKUP[t + r * 64] = pick((float) t / 63.0F, (float) r / 63.0F);
            }
        }
    }

    private final String vanillaBiome;
    private final boolean sandy;

    BetaBiome(String vanillaBiome, boolean sandy) {
        this.vanillaBiome = vanillaBiome;
        this.sandy = sandy;
    }

    /** Path of the {@code minecraft:} biome this Beta biome is shown as. */
    public String vanillaBiome() {
        return vanillaBiome;
    }

    /** Surface top block (Beta id). */
    public byte topBlock() {
        return sandy ? BetaBlocks.SAND : BetaBlocks.GRASS;
    }

    /** Surface filler block (Beta id). */
    public byte fillerBlock() {
        return sandy ? BetaBlocks.SAND : BetaBlocks.DIRT;
    }

    /** The biome for a climate sample, via Beta's 64×64 lookup table. */
    public static BetaBiome of(double temp, double rain) {
        int t = (int) (temp * 63.0D);
        int r = (int) (rain * 63.0D);
        return LOOKUP[t + r * 64];
    }

    private static BetaBiome pick(float temp, float rain) {
        rain *= temp;
        if (temp < 0.1F) return ICE_DESERT;
        if (rain < 0.2F) {
            if (temp < 0.5F) return TUNDRA;
            return temp < 0.95F ? SAVANNA : DESERT;
        }
        if (rain > 0.5F && temp < 0.7F) return SWAMPLAND;
        if (temp < 0.5F) return TAIGA;
        if (temp < 0.97F) return rain < 0.35F ? SHRUBLAND : FOREST;
        if (rain < 0.45F) return PLAINS;
        return rain < 0.9F ? SEASONAL_FOREST : RAINFOREST;
    }
}
