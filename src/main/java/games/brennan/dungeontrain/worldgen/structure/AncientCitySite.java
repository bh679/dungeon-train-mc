package games.brennan.dungeontrain.worldgen.structure;

import games.brennan.dungeontrain.worldgen.NetherBandBiomes;
import games.brennan.dungeontrain.worldgen.NetherMountainTerrain;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.feature.CavernNoise;

import java.util.ArrayList;
import java.util.List;

/**
 * Where each Nether pass's <b>ancient city</b> stands: one per pass, in a deep-dark cave region of the
 * fall-side mountains. Pure layout math (no Minecraft types) so it is unit-testable and cheap to memoise.
 *
 * <p>Candidates are the {@code 2^}{@link NetherBandBiomes#CAVE_REGION_SHIFT}-block cave regions at region-Z
 * {@code 0} (the track runs at z ≈ 0–8, so a city of radius ~116 straddles the line) that lie wholly past
 * the real-Nether core ({@link WorldGenCycle#netherPastCore}) and under a mountain tall enough to hold the
 * city below the {@link CavernNoise} roof. One is picked by a seeded hash of {@code (seed, pass)};
 * <b>pass 0 has a 50 % chance of a city, every later pass always has one.</b> The chosen region is forced to
 * {@code deep_dark} by {@code NetherBandBiomeSet#caveBiomeFor}, so the city's biome is guaranteed.</p>
 */
public final class AncientCitySite {

    /** No city for this pass. */
    public static final long NONE = Long.MIN_VALUE;
    /** Region-Z the cities sit in (the track's region). */
    public static final int REGION_Z = 0;
    /** The city anchor ({@code minecraft:city_anchor}) sits this far above the track bed. */
    public static final int ANCHOR_ABOVE_BED = 20;
    /** Rock the city needs above its anchor (vanilla's city rises ~30 over the anchor). */
    public static final int HEADROOM_ABOVE_ANCHOR = 30;
    private static final long SALT = 0x6A09E667F3BCC909L;

    private AncientCitySite() {}

    /** Region-X ({@code worldX >> CAVE_REGION_SHIFT}) of pass {@code pass}'s city, or {@link #NONE}. */
    public static long cellX(WorldGenCycle cycle, long seed, long pass, int seaLevel, int worldCeiling,
                             int netherTop, int baseRelief, int bedY) {
        if (cycle == null || pass < 0) return NONE;
        long[] range = cycle.netherPassRange((int) pass);
        if (range == null) return NONE;
        int shift = NetherBandBiomes.CAVE_REGION_SHIFT;
        int size = 1 << shift;
        int centreZ = (REGION_Z << shift) + size / 2;
        int needTop = bedY + ANCHOR_ABOVE_BED + HEADROOM_ABOVE_ANCHOR + CavernNoise.ROOF_BELOW_TOP;
        List<Long> candidates = new ArrayList<>();
        for (long cx = range[0] >> shift; (cx << shift) + size - 1 < range[1]; cx++) {
            int x0 = (int) (cx << shift);
            if (!cycle.netherPastCore(x0) || !cycle.netherPastCore(x0 + size - 1)) continue;
            double top = NetherMountainTerrain.targetTop(cycle, seed, x0 + size / 2, centreZ,
                    seaLevel, worldCeiling, netherTop, baseRelief);
            if (top >= needTop) candidates.add(cx);
        }
        if (candidates.isEmpty()) return NONE;
        long h = mix(seed ^ SALT, pass);
        if (pass == 0 && (h & 1L) == 0L) return NONE;              // pass 0: a coin flip
        return candidates.get((int) Math.floorMod(h >>> 8, (long) candidates.size()));
    }

    /** World-X of the centre of region {@code cellX} (the city's anchor column). */
    public static int centreX(long cellX) {
        int shift = NetherBandBiomes.CAVE_REGION_SHIFT;
        return (int) ((cellX << shift) + (1 << (shift - 1)));
    }

    /** World-Z of the city's anchor column. */
    public static int centreZ() {
        int shift = NetherBandBiomes.CAVE_REGION_SHIFT;
        return (REGION_Z << shift) + (1 << (shift - 1));
    }

    private static long mix(long seed, long pass) {
        long h = seed * 0x9E3779B97F4A7C15L;
        h ^= pass * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
