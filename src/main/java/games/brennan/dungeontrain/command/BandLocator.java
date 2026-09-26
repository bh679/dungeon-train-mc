package games.brennan.dungeontrain.command;

import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.server.level.ServerLevel;

import java.util.OptionalInt;
import java.util.function.IntPredicate;

/**
 * Finds the world-X where the next occurrence of a band begins, for {@code /dtp <band>}. Walks +X with
 * the band's own column test (see {@link DtpTarget}) so it stays correct whatever the band layout config
 * is, then binary-searches the exact entry column.
 *
 * <p>Nothing here is a band distance: the search horizon and coarse step both come from the live
 * {@link WorldGenCycle}. Under an ordered layout run {@code k} is {@code 2^k} times as long as run 0 and
 * every band in it stretches by the same factor, so both scale with the run the search starts in.</p>
 */
final class BandLocator {

    /** Run-0 coarse scan step — far shorter than any band, so a band can't be stepped over. Scales ×2^k with the run. */
    private static final int BASE_STEP = 16;

    /**
     * Horizon in run lengths: from just past a band, the rest of this run plus the whole next run (twice as
     * long under a layout) is under three run lengths.
     */
    private static final long HORIZON_RUNS = 3L;

    private BandLocator() {}

    /**
     * Entry X of the next {@code target} band strictly ahead of {@code fromX} — if {@code fromX} is
     * already inside one, that band is skipped. Empty when the cycle is empty, the band is disabled,
     * or the world has no train (every column test then reads false).
     */
    static OptionalInt nextBandStartX(ServerLevel overworld, DtpTarget target, int fromX) {
        return nextBandStartX(WorldGenCycle.fromConfig(), x -> target.test().test(overworld, x), fromX);
    }

    /**
     * Entry X of the {@code target} band's occurrence in lap {@code lap} ({@link WorldGenCycle#cycleIndex}),
     * for {@code /dtp <band> <distance> <lap>}. Empty when that lap has no such band (e.g. BetterNether on
     * an even lap) or the lap starts beyond int world-X.
     */
    static OptionalInt bandStartXInLap(ServerLevel overworld, DtpTarget target, int lap) {
        return bandStartXInLap(WorldGenCycle.fromConfig(), x -> target.test().test(overworld, x), lap);
    }

    /** Pure form of {@link #bandStartXInLap(ServerLevel, DtpTarget, int)} over any column test. */
    static OptionalInt bandStartXInLap(WorldGenCycle cycle, IntPredicate inBand, int lap) {
        long lapStart = cycle.lapStartX(lap);
        if (lapStart < 0L || lapStart > Integer.MAX_VALUE) return OptionalInt.empty();
        // One column back, so a band opening exactly on the lap boundary counts as this lap's.
        OptionalInt entry = nextBandStartX(cycle, inBand, (int) lapStart - 1);
        if (entry.isEmpty() || cycle.cycleIndex(entry.getAsInt()) != lap) return OptionalInt.empty();
        return entry;
    }

    /** Pure form of {@link #nextBandStartX(ServerLevel, DtpTarget, int)} over any column test. */
    static OptionalInt nextBandStartX(WorldGenCycle cycle, IntPredicate inBand, int fromX) {
        long period = cycle.period();
        if (period <= 0L) return OptionalInt.empty();

        int run = cycle.hasLayout() ? (int) Math.min(30L, Math.max(0L, cycle.cycleIndex(fromX))) : 0;
        long runLength = period << run;
        long step = (long) BASE_STEP << run;
        long leadIn = Math.max(0L, cycle.startX() - (long) fromX);
        long limit = Math.min(Integer.MAX_VALUE - step, (long) fromX + leadIn + HORIZON_RUNS * runLength);

        long x = fromX;
        while (x < limit && inBand.test((int) x)) x += step;

        long outside = x;
        while (x < limit) {
            x += step;
            if (inBand.test((int) x)) {
                return OptionalInt.of(refineEntry(inBand, (int) outside, (int) x));
            }
            outside = x;
        }
        return OptionalInt.empty();
    }

    /** Binary search in {@code (outside, inside]} for the first column in the band. */
    private static int refineEntry(IntPredicate inBand, int outside, int inside) {
        int lo = outside;
        int hi = inside;
        while (hi - lo > 1) {
            int mid = lo + (hi - lo) / 2;
            if (inBand.test(mid)) hi = mid;
            else lo = mid;
        }
        return hi;
    }
}
