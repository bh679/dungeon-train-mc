package games.brennan.dungeontrain.command;

import games.brennan.dungeontrain.worldgen.TrainPhase;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.server.level.ServerLevel;

import java.util.OptionalInt;

/**
 * Finds the world-X where the next occurrence of a {@link TrainPhase} band begins, for
 * {@code /dtp <band>}. Walks +X with {@link TrainPhase#phaseAt} (the single band classifier the
 * template gate also uses) so it stays correct whatever the band layout config is, then
 * binary-searches the exact entry column.
 */
final class BandLocator {

    /** Coarse scan step — far shorter than any band, so a band can't be stepped over. */
    private static final int STEP = 16;

    private BandLocator() {}

    /**
     * Entry X of the next {@code target} band strictly ahead of {@code fromX} — if {@code fromX} is
     * already inside one, that band is skipped. Empty when the cycle is empty, the band is disabled,
     * or the world has no train (every column then reads {@link TrainPhase#OVERWORLD}).
     */
    static OptionalInt nextBandStartX(ServerLevel overworld, TrainPhase target, int fromX) {
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        long period = cycle.period();
        if (period <= 0L) return OptionalInt.empty();

        long leadIn = Math.max(0L, cycle.startX() - (long) fromX);
        long limit = Math.min(Integer.MAX_VALUE - (long) STEP, (long) fromX + leadIn + 2L * period);

        long x = fromX;
        while (x < limit && TrainPhase.phaseAt(overworld, (int) x) == target) x += STEP;

        long outside = x;
        while (x < limit) {
            x += STEP;
            if (TrainPhase.phaseAt(overworld, (int) x) == target) {
                return OptionalInt.of(refineEntry(overworld, target, (int) outside, (int) x));
            }
            outside = x;
        }
        return OptionalInt.empty();
    }

    /** Binary search in {@code (outside, inside]} for the first column classified as {@code target}. */
    private static int refineEntry(ServerLevel overworld, TrainPhase target, int outside, int inside) {
        int lo = outside;
        int hi = inside;
        while (hi - lo > 1) {
            int mid = lo + (hi - lo) / 2;
            if (TrainPhase.phaseAt(overworld, mid) == target) hi = mid;
            else lo = mid;
        }
        return hi;
    }
}
