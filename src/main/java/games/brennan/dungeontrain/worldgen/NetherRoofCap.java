package games.brennan.dungeontrain.worldgen;

/**
 * The Nether band's roof: the rows the core slab leaves out above its sampled top (Nether y121..127), stamped
 * the way the real Nether ends — netherrack, then vanilla's ragged bedrock ceiling.
 *
 * <p>Vanilla's Nether surface rule lays bedrock with a {@code bedrock_roof} vertical gradient from
 * {@code belowTop(5)} to {@code top()}: never at or below Nether y122, always at y127, and in between a chance
 * that rises a fifth per row. The cap uses that gradient with its own seeded per-block draw, so the band's roof
 * has the Nether's look — not the exact blocks of the patch of Nether the core samples.</p>
 */
public final class NetherRoofCap {

    /** The real Nether's top row — always bedrock. */
    public static final int NETHER_TOP_Y = 127;
    /** At and below this Nether y the roof is never bedrock (vanilla's {@code belowTop(5)}). */
    public static final int NETHER_BEDROCK_FADE_Y = NETHER_TOP_Y - 5;
    /** Rows stamped over the slab: Nether y{@code NETHER_Y_SAMPLE_MAX + 1} .. {@link #NETHER_TOP_Y}. */
    public static final int ROWS = NETHER_TOP_Y - NetherCoreGeometry.NETHER_Y_SAMPLE_MAX;

    private static final long BEDROCK_SALT = 0x4E7B6EC0D52A9F13L;

    private NetherRoofCap() {}

    /** Chance a block at this Nether y is bedrock — vanilla's {@code bedrock_roof} gradient. */
    public static double bedrockChance(int netherY) {
        if (netherY >= NETHER_TOP_Y) return 1.0;
        if (netherY <= NETHER_BEDROCK_FADE_Y) return 0.0;
        return (double) (netherY - NETHER_BEDROCK_FADE_Y) / (NETHER_TOP_Y - NETHER_BEDROCK_FADE_Y);
    }

    /** Whether the cap block at world {@code (x, y, z)}, Nether y {@code netherY}, is bedrock. Pure and seed-stable. */
    public static boolean isBedrock(long seed, int x, int y, int z, int netherY) {
        double chance = bedrockChance(netherY);
        if (chance >= 1.0) return true;
        if (chance <= 0.0) return false;
        return hash01(seed ^ BEDROCK_SALT, x, y, z) < chance;
    }

    /** Per-block white noise in {@code [0,1)} — the SplitMix64-style hash {@link NetherFade} uses. */
    private static double hash01(long seed, int x, int y, int z) {
        long h = seed + 0x9E3779B97F4A7C15L;
        h ^= (long) x * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        h ^= (long) y * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (long) z * 0xD6E8FEB86659FD93L;
        h ^= (h >>> 31);
        return (h >>> 11) * 0x1.0p-53;
    }
}
