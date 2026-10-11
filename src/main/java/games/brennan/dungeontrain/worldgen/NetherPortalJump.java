package games.brennan.dungeontrain.worldgen;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.IntFunction;

/**
 * Where a Nether portal on the overworld ride lets the player out — pure world-X arithmetic over a
 * {@link WorldGenCycle} and a band-label function, so it is unit-testable; the portal hook
 * ({@code event/NetherPortalBandJump}) supplies {@code BandLabel.bandAt} as the labeller and handles
 * already-linked portal pairs before asking here.
 *
 * <p>Rules (a <em>segment</em> is a run of columns sharing one F3+4 band label; a <em>core</em> is a
 * segment with its transitions cut off):</p>
 * <ul>
 *   <li><b>First overworld</b> (before the first Nether core) → the first Nether's core.</li>
 *   <li><b>Any other non-Nether band</b> → back to the <b>previous</b> Nether's core (the last core that
 *       starts at or behind the player).</li>
 *   <li><b>Inside a Nether band</b> → back to the core of the <b>band just before it</b>, whatever its
 *       type.</li>
 * </ul>
 *
 * <p>The landing column keeps the player's <b>proportion</b>: their fraction through the segment they
 * left is applied to the destination core ({@link #mapFraction}), so bands of different lengths still
 * line up. A Nether core is the run of {@link WorldGenCycle#isNetherCore} columns; any other band's core
 * is its segment trimmed by the Nether approach length at both ends ({@link #coreOf}).</p>
 */
public final class NetherPortalJump {

    /** Coarse scan stride for segment edges and core edges. */
    private static final int SCAN_STEP = 16;

    /** Layout runs double in length; cap the shift so the scan bound stays an int. */
    private static final int MAX_RUN_SHIFT = 12;

    /** A non-Nether core keeps at least this share of its segment (the trim is capped at a third per side). */
    private static final int MIN_CORE_DIVISOR = 3;

    private NetherPortalJump() {}

    /** A run of columns {@code [start, end)}. */
    public record Segment(long start, long end) {
        public long length() {
            return end - start;
        }

        public boolean contains(long x) {
            return x >= start && x < end;
        }
    }

    /**
     * Target world-X for a portal used at {@code fromX}, or empty when there is nowhere to go (no Nether
     * band in the cycle, or the destination lies beyond int world-X). {@code label} names the band at a
     * column; equal labels form one segment.
     */
    public static OptionalLong targetX(WorldGenCycle cycle, IntFunction<String> label, int fromX) {
        if (cycle.period() <= 0L) return OptionalLong.empty();
        long bound = scanBound(cycle, fromX);
        Optional<Segment> dest = destinationCore(cycle, label, fromX, bound);
        if (dest.isEmpty()) return OptionalLong.empty();
        Segment from = segmentAround(label, fromX, Math.max(rideStart(cycle), (long) fromX - bound), (long) fromX + bound);
        long target = mapFraction(from, fromX, dest.get());
        return target > Integer.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(target);
    }

    /** The core a portal at {@code fromX} leads to, per the class rules; empty when there is none. */
    static Optional<Segment> destinationCore(WorldGenCycle cycle, IntFunction<String> label, int fromX, long bound) {
        if (NetherBand.isInNetherBand(cycle, fromX)) return previousBandCore(cycle, label, fromX, bound);

        Optional<Segment> first = netherCore(cycle, 0);
        if (first.isEmpty()) return Optional.empty();
        if (fromX < first.get().start()) return first;                       // first overworld → first Nether

        for (int p = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, cycle.netherPassIndex(fromX))); p >= 0; p--) {
            Optional<Segment> core = netherCore(cycle, p);
            if (core.isPresent() && core.get().start() <= fromX) return core;  // the previous Nether
        }
        return first;
    }

    /** From inside a Nether segment: the core of the segment that ends where this one begins. */
    private static Optional<Segment> previousBandCore(WorldGenCycle cycle, IntFunction<String> label, int fromX, long bound) {
        long rideStart = rideStart(cycle);
        Segment nether = segmentAround(label, fromX, Math.max(rideStart, (long) fromX - bound), (long) fromX + bound);
        long prevEnd = nether.start() - 1L;
        if (prevEnd < rideStart || prevEnd < Integer.MIN_VALUE) return Optional.empty();
        if (NetherBand.isInNetherBand(cycle, (int) prevEnd)) {
            // Two Nether looks back to back (a split slot): the band before is still "the Nether".
            return netherCore(cycle, (int) cycle.netherPassIndex((int) prevEnd));
        }
        Segment prev = segmentAround(label, (int) prevEnd, Math.max(rideStart, prevEnd - bound), prevEnd);
        return Optional.of(coreOf(prev, cycle));
    }

    /**
     * The real-Nether core of pass {@code pass}: the run of {@link WorldGenCycle#isNetherCore} columns
     * inside its slot. Empty when the pass does not exist, lies beyond int world-X, or has no core.
     */
    static Optional<Segment> netherCore(WorldGenCycle cycle, int pass) {
        long[] range = cycle.netherPassRange(pass);
        if (range == null || range[0] > Integer.MAX_VALUE) return Optional.empty();
        long end = Math.min(range[1], Integer.MAX_VALUE);
        long outside = range[0] - 1L;
        for (long x = range[0]; x < end; x += SCAN_STEP) {
            if (cycle.isNetherCore((int) x)) {
                long start = refineEdge(cycle, outside, x);
                long last = edgeFrom(cycle, x, end);
                return Optional.of(new Segment(start, last + 1L));
            }
            outside = x;
        }
        return Optional.empty();
    }

    /** Last core column at or after {@code inside} (a core column), not past {@code limit}. */
    private static long edgeFrom(WorldGenCycle cycle, long inside, long limit) {
        long probe = inside;
        while (true) {
            long next = probe + SCAN_STEP;
            if (next >= limit) return refineLast(cycle, probe, limit);
            if (!cycle.isNetherCore((int) next)) return refineLast(cycle, probe, next);
            probe = next;
        }
    }

    /** Binary search in {@code (outside, inside]} for the first core column. */
    private static long refineEdge(WorldGenCycle cycle, long outside, long inside) {
        while (inside - outside > 1L) {
            long mid = (outside + inside) >>> 1;
            if (cycle.isNetherCore((int) mid)) inside = mid; else outside = mid;
        }
        return inside;
    }

    /** Binary search in {@code [inside, outside)} for the last core column. */
    private static long refineLast(WorldGenCycle cycle, long inside, long outside) {
        while (outside - inside > 1L) {
            long mid = (inside + outside) >>> 1;
            if (cycle.isNetherCore((int) mid)) inside = mid; else outside = mid;
        }
        return inside;
    }

    /**
     * A non-Nether segment minus its transitions: {@link WorldGenCycle#netherApproachLength} (the
     * mountain rise + crossfade that borders every Nether band) cut from each end, capped at a third of
     * the segment so the core is never empty.
     */
    static Segment coreOf(Segment segment, WorldGenCycle cycle) {
        long trim = Math.min(cycle.netherApproachLength(), segment.length() / MIN_CORE_DIVISOR);
        if (trim <= 0L) return segment;
        return new Segment(segment.start() + trim, segment.end() - trim);
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
                if (limit != probe && own.equals(label.apply((int) limit))) return limit;
                return refine(label, own, inside, limit);
            }
            if (!own.equals(label.apply((int) next))) return refine(label, own, inside, next);
            inside = next;
            probe = next;
        }
    }

    /** Binary search between {@code inside} (labelled {@code own}) and {@code outside} (not) for the last {@code own} column. */
    private static long refine(IntFunction<String> label, String own, long inside, long outside) {
        while (Math.abs(outside - inside) > 1L) {
            long mid = (inside + outside) / 2L;
            if (own.equals(label.apply((int) mid))) inside = mid; else outside = mid;
        }
        return inside;
    }

    /** The ride begins at x=0, or the anchor if that is earlier; no segment reaches back past it. */
    private static long rideStart(WorldGenCycle cycle) {
        return Math.min(0L, cycle.startX());
    }

    /** How far a segment scan may walk — twice the current run's length (runs double every lap). */
    private static long scanBound(WorldGenCycle cycle, int fromX) {
        int run = cycle.hasLayout() ? (int) Math.min(MAX_RUN_SHIFT, Math.max(0L, cycle.cycleIndex(fromX))) : 0;
        return Math.min((long) Integer.MAX_VALUE, (cycle.period() << run) * 2L);
    }
}
