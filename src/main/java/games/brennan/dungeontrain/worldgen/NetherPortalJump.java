package games.brennan.dungeontrain.worldgen;

import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.IntFunction;

/**
 * Where a Nether portal on the overworld ride lets the player out — pure world-X arithmetic over a
 * {@link WorldGenCycle} and a band-label function, so it is unit-testable; the portal hook
 * ({@code event/NetherPortalBandJump}) supplies {@code BandLabel.bandAt} as the labeller.
 *
 * <ul>
 *   <li><b>Outside</b> a Nether band → the next Nether band ahead (the first pass whose netherrack entry
 *       lies past the player — standing in a band's mountain approach jumps into <em>that</em> band).</li>
 *   <li><b>Inside</b> Nether pass {@code p} ({@link NetherBand#isInNetherBand(WorldGenCycle, int)}) → pass
 *       {@code p − 1}; inside the first band, which has no previous one, → the next band ahead.</li>
 * </ul>
 *
 * <p>The landing column keeps the player's <b>proportion</b>: a <em>segment</em> is the run of columns
 * sharing one F3+4 band label (an overworld stretch, a Nether band, an End band…); the player's fraction
 * through the segment they left is applied to the destination Nether band's segment, so bands of
 * different lengths still line up ({@link #mapFraction}).</p>
 */
public final class NetherPortalJump {

    /** Coarse scan stride for segment edges and netherrack entries. */
    private static final int SCAN_STEP = 16;

    /** Passes to look ahead before giving up. */
    private static final int MAX_PASSES_AHEAD = 64;

    /** Layout runs double in length; cap the shift so the scan bound stays an int. */
    private static final int MAX_RUN_SHIFT = 12;

    private NetherPortalJump() {}

    /** A run of columns {@code [start, end)} that share one band label. */
    public record Segment(long start, long end) {
        public long length() {
            return end - start;
        }

        public boolean contains(long x) {
            return x >= start && x < end;
        }
    }

    /**
     * Target world-X for a portal used at {@code fromX}, or empty when there is no Nether band to go to
     * (none in the cycle, or the next one lies beyond int world-X). {@code label} names the band at a
     * column; equal labels form one segment.
     */
    public static OptionalLong targetX(WorldGenCycle cycle, IntFunction<String> label, int fromX) {
        if (cycle.period() <= 0L) return OptionalLong.empty();
        OptionalInt pass = destinationPass(cycle, fromX);
        if (pass.isEmpty()) return OptionalLong.empty();
        OptionalLong entry = netherrackEntry(cycle, pass.getAsInt());
        if (entry.isEmpty()) return OptionalLong.empty();

        long bound = scanBound(cycle, fromX);
        // The ride begins at x=0 (or the anchor, if that is earlier): the overworld behind it is not part
        // of any stretch, so a segment never reaches back past it.
        long rideStart = Math.min(0L, cycle.startX());
        Segment from = segmentAround(label, fromX, Math.max(rideStart, (long) fromX - bound), (long) fromX + bound);
        Segment to = segmentAround(label, (int) entry.getAsLong(), entry.getAsLong(), entry.getAsLong() + bound);
        long target = mapFraction(from, fromX, to);
        return target > Integer.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(target);
    }

    /**
     * The Nether pass a portal at {@code fromX} leads to: inside pass {@code p} → {@code p − 1} (or the
     * next band ahead when {@code p} is the first); outside → the first pass whose netherrack entry is
     * ahead of {@code fromX}. Empty when there is none within reach.
     */
    static OptionalInt destinationPass(WorldGenCycle cycle, int fromX) {
        long pass = cycle.netherPassIndex(fromX);
        if (NetherBand.isInNetherBand(cycle, fromX) && pass > 0L) return OptionalInt.of((int) (pass - 1L));
        // Before the anchor the pass index is not meaningful (the layout extends backwards), so start at 0.
        int first = fromX < cycle.startX() ? 0 : (int) Math.max(0L, pass);
        for (int p = first; p < first + MAX_PASSES_AHEAD; p++) {
            long[] range = cycle.netherPassRange(p);
            if (range == null || range[0] > Integer.MAX_VALUE) return OptionalInt.empty();
            OptionalLong entry = netherrackEntry(cycle, p);
            if (entry.isPresent() && entry.getAsLong() > fromX) return OptionalInt.of(p);
        }
        return OptionalInt.empty();
    }

    /**
     * {@code to.start + fraction × to.length}, where {@code fraction} is how far {@code fromX} sits
     * through {@code from}; clamped so the result is always inside {@code to}.
     */
    static long mapFraction(Segment from, long fromX, Segment to) {
        double fraction = from.length() <= 0L ? 0.0 : (double) (fromX - from.start()) / (double) from.length();
        fraction = Math.max(0.0, Math.min(fraction, 1.0));
        long target = to.start() + (long) Math.floor(fraction * (double) to.length());
        return Math.max(to.start(), Math.min(target, to.end() - 1L));
    }

    /**
     * The segment containing {@code x}: the maximal run of columns with {@code label(x)}'s label, searched
     * within {@code [lo, hi]} (both clamped to int world-X). A coarse {@link #SCAN_STEP} walk finds the
     * first differing column on each side, then a binary search pins the edge.
     */
    static Segment segmentAround(IntFunction<String> label, int x, long lo, long hi) {
        lo = Math.max(lo, Integer.MIN_VALUE);
        hi = Math.min(hi, Integer.MAX_VALUE);
        String own = label.apply(x);
        long end = edge(label, own, x, +1, hi) + 1L;
        long start = edge(label, own, x, -1, lo);
        return new Segment(start, end);
    }

    /** The last column in direction {@code dir} (±1) from {@code x} still labelled {@code own}, not past {@code limit}. */
    private static long edge(IntFunction<String> label, String own, int x, int dir, long limit) {
        long inside = x;
        long probe = x;
        while (true) {
            long next = probe + (long) dir * SCAN_STEP;
            if (dir > 0 ? next > limit : next < limit) {
                // Ran out of range: check the limit column itself, then refine.
                if (limit != probe && own.equals(label.apply((int) limit))) return limit;
                return refine(label, own, inside, limit, dir);
            }
            if (!own.equals(label.apply((int) next))) return refine(label, own, inside, next, dir);
            inside = next;
            probe = next;
        }
    }

    /** Binary search between {@code inside} (labelled {@code own}) and {@code outside} (not) for the last {@code own} column. */
    private static long refine(IntFunction<String> label, String own, long inside, long outside, int dir) {
        while (Math.abs(outside - inside) > 1L) {
            long mid = (inside + outside) / 2L;
            if (own.equals(label.apply((int) mid))) inside = mid; else outside = mid;
        }
        return inside;
    }

    /** How far a segment scan may walk — twice the current run's length (runs double every lap). */
    private static long scanBound(WorldGenCycle cycle, int fromX) {
        int run = cycle.hasLayout() ? (int) Math.min(MAX_RUN_SHIFT, Math.max(0L, cycle.cycleIndex(fromX))) : 0;
        return Math.min((long) Integer.MAX_VALUE, (cycle.period() << run) * 2L);
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
            if (cycle.netherRamp((int) x) > 0.0) return OptionalLong.of(refineEntry(cycle, outside, x));
            outside = x;
        }
        return OptionalLong.empty();
    }

    /** Binary search in {@code (outside, inside]} for the first netherrack column. */
    private static long refineEntry(WorldGenCycle cycle, long outside, long inside) {
        while (inside - outside > 1L) {
            long mid = (outside + inside) >>> 1;
            if (cycle.netherRamp((int) mid) > 0.0) inside = mid; else outside = mid;
        }
        return inside;
    }
}
