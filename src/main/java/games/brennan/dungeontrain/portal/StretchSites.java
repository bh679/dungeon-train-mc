package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.worldgen.SecondLapOverworld;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;

import java.util.ArrayList;
import java.util.List;

/**
 * Which overworld X a chunk-dimension sample may be cut from, by the look the room wants.
 *
 * <p>A sample takes whatever look its site's X gives it — the biome mixin and the modded-stretch
 * decoration pass both key on the sample chunk's own X — so choosing the look is choosing the site.
 * The plain overworld room wants ordinary vanilla overworld: no band, no legacy era, no modded
 * stretch. The modded rooms want the inside of their own stretch, which a uniformly scattered site
 * almost never lands in, so they are handed an X inside one instead.</p>
 *
 * <p>Pure (no Minecraft types) so it is unit-testable against a hand-built {@link WorldGenCycle}.</p>
 */
public final class StretchSites {

    /** Blocks of clearance a site keeps from a stretch's edge, for the biome blending across it. */
    static final int MARGIN = 32;

    /** How many laps the modded stretches are looked for in. Later laps double, so this reaches far. */
    static final int LAPS = 16;

    private static final int CHUNK = 16;

    private StretchSites() {}

    /**
     * True when the chunk starting at {@code chunkMinX} — widened by {@link #MARGIN} — is ordinary
     * overworld wearing {@code stretch}. With no cycle there are no bands and every stretch is vanilla.
     */
    public static boolean matches(WorldGenCycle cycle, SecondLapOverworld.Stretch stretch, int chunkMinX) {
        if (cycle == null) return stretch == SecondLapOverworld.Stretch.VANILLA;
        long lo = (long) chunkMinX - MARGIN;
        long hi = (long) chunkMinX + CHUNK - 1 + MARGIN;
        for (long x : new long[] {lo, (lo + hi) / 2, hi}) {
            if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE) return false;
            int ix = (int) x;
            if (!cycle.isOverworldGapAt(ix) || SecondLapOverworld.at(cycle, ix) != stretch) return false;
        }
        return true;
    }

    /**
     * Chunk-X ranges {@code [from, to)} a {@code stretch} room can be cut from, margin already taken
     * off both ends, no further out than {@code maxBlockX}, in X order. Empty when this world has no
     * such stretch within reach (its band is switched off).
     *
     * <p>The cap keeps modded rooms as near the origin as the plain room's scattered sites: the
     * doubling laps put later stretches billions of blocks out, far past the world border, where
     * generation is nothing a player could ever stand on.</p>
     */
    public static List<int[]> chunkRanges(WorldGenCycle cycle, SecondLapOverworld.Stretch stretch, long maxBlockX) {
        List<int[]> out = new ArrayList<>();
        if (cycle == null) return out;
        for (long[] gap : cycle.overworldGapRanges(LAPS)) {
            if (gap[0] >= maxBlockX) break;
            long mid = (gap[0] + gap[1]) / 2;
            if (SecondLapOverworld.at(cycle, (int) Math.min(mid, Integer.MAX_VALUE)) != stretch) continue;
            long from = Math.floorDiv(gap[0] + MARGIN + CHUNK - 1, CHUNK);
            long to = Math.floorDiv(Math.min(gap[1] - MARGIN, maxBlockX), CHUNK);
            if (from < to) out.add(new int[] {(int) from, (int) to});
        }
        return out;
    }

    /**
     * One chunk X inside {@code ranges}: {@code pickRange} chooses the range — each equally, so the
     * short early stretches turn up as often as the long late ones — and {@code pickOffset} the chunk
     * within it. Both are uniform {@code [0,1)}.
     */
    public static int chunkXIn(List<int[]> ranges, double pickRange, double pickOffset) {
        int[] r = ranges.get(Math.min(ranges.size() - 1, (int) (pickRange * ranges.size())));
        long span = (long) r[1] - r[0];
        return (int) (r[0] + Math.min(span - 1, (long) (pickOffset * span)));
    }
}
