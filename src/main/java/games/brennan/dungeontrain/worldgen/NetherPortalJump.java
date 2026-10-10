package games.brennan.dungeontrain.worldgen;

import java.util.OptionalLong;

/**
 * Where a Nether portal on the overworld ride takes the player — pure world-X arithmetic over a
 * {@link WorldGenCycle}, so it is unit-testable and shared by nothing but the portal hook
 * ({@code event/NetherPortalBandJump}).
 *
 * <ul>
 *   <li><b>Outside</b> a Nether band → the next Nether band ahead: the first pass whose netherrack
 *       entry column lies past the player (so standing in a band's mountain approach jumps a short
 *       way forward into <em>that</em> band, not over it).</li>
 *   <li><b>Inside</b> a Nether band ({@link NetherBand#isInNetherBand(WorldGenCycle, int)}: netherrack
 *       present, End band not owning the column) → the previous Nether band. The first band has no
 *       previous one → empty.</li>
 * </ul>
 *
 * <p>Every target is a band's <b>netherrack entry</b> plus {@link #ENTRY_INSET} — the same "just inside,
 * not on the boundary" offset {@code /dtp <band>} uses — so the new train rolls into the Nether the way
 * the ride normally delivers it.</p>
 */
public final class NetherPortalJump {

    /** Blocks past a band's netherrack entry column to land — just inside, not on the boundary. */
    public static final int ENTRY_INSET = 32;

    /** Coarse scan stride when looking for the first netherrack column of a pass. */
    private static final int SCAN_STEP = 16;

    /** Passes to look ahead before giving up (layout runs double, so a few is plenty). */
    private static final int MAX_PASSES_AHEAD = 64;

    private NetherPortalJump() {}

    /**
     * Target world-X for a portal used at {@code fromX}, or empty when there is nowhere to go: no
     * Nether band at all, standing in the first band (no previous), or the next band lies beyond
     * int world-X.
     */
    public static OptionalLong targetX(WorldGenCycle cycle, int fromX) {
        if (cycle.period() <= 0L) return OptionalLong.empty();
        long pass = cycle.netherPassIndex(fromX);
        if (NetherBand.isInNetherBand(cycle, fromX)) {
            if (pass <= 0L) return OptionalLong.empty();
            return withInset(netherrackEntry(cycle, (int) (pass - 1L)));
        }
        int first = (int) Math.max(0L, pass);
        for (int p = first; p < first + MAX_PASSES_AHEAD; p++) {
            long[] range = cycle.netherPassRange(p);
            if (range == null || range[0] > Integer.MAX_VALUE) return OptionalLong.empty();
            OptionalLong entry = netherrackEntry(cycle, p);
            if (entry.isPresent() && entry.getAsLong() > fromX) return withInset(entry);
        }
        return OptionalLong.empty();
    }

    private static OptionalLong withInset(OptionalLong entry) {
        if (entry.isEmpty() || entry.getAsLong() > Integer.MAX_VALUE - ENTRY_INSET) return OptionalLong.empty();
        return OptionalLong.of(entry.getAsLong() + ENTRY_INSET);
    }

    /**
     * First world-X of pass {@code pass} where netherrack appears ({@code netherRamp > 0}), or empty
     * when the pass does not exist, lies beyond int world-X, or never reaches netherrack.
     */
    static OptionalLong netherrackEntry(WorldGenCycle cycle, int pass) {
        long[] range = cycle.netherPassRange(pass);
        if (range == null) return OptionalLong.empty();
        long end = Math.min(range[1], Integer.MAX_VALUE);
        long outside = range[0] - 1L;
        for (long x = range[0]; x < end; x += SCAN_STEP) {
            if (cycle.netherRamp((int) x) > 0.0) return OptionalLong.of(refine(cycle, outside, x));
            outside = x;
        }
        return OptionalLong.empty();
    }

    /** Binary search in {@code (outside, inside]} for the first netherrack column. */
    private static long refine(WorldGenCycle cycle, long outside, long inside) {
        while (inside - outside > 1L) {
            long mid = (outside + inside) >>> 1;
            if (cycle.netherRamp((int) mid) > 0.0) inside = mid; else outside = mid;
        }
        return inside;
    }
}
