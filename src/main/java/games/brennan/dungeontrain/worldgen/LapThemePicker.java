package games.brennan.dungeontrain.worldgen;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Chooses the {@link LapTheme} of one theme lap from how far the players have got in each theme on
 * earlier runs. Pure (no Minecraft types) so the rules are unit-tested directly.
 *
 * <ul>
 *   <li><b>Lap 1 of the first cycle</b> is always {@link LapTheme#VANILLA}.</li>
 *   <li><b>Lap 2</b> ({@link Kind#LAP2}) is {@link LapTheme#BOP} or {@link LapTheme#BETTER}: whichever
 *       the players have got least far in; a tie (both 100% included) is 50:50.</li>
 *   <li><b>Lap 1 of later cycles</b> ({@link Kind#LAP1}) is random among the three themes that are not
 *       yet 100% complete — or among all three once every one is.</li>
 *   <li>Neither ever repeats the theme of the lap just before it.</li>
 * </ul>
 */
public final class LapThemePicker {

    /** Which rule a theme group follows: {@code :t1} slots are {@link #LAP1}, {@code :t2} slots {@link #LAP2}. */
    public enum Kind { LAP1, LAP2 }

    /** Progress at or above this counts as 100% complete (float noise in the saved fraction). */
    static final double COMPLETE = 0.999;

    private LapThemePicker() {}

    /**
     * The theme for theme lap {@code n}.
     *
     * @param n         global theme-lap index ({@code run × groupsPerRun + group}); {@code 0} is the first lap
     * @param kind      the group's rule
     * @param previous  theme of lap {@code n − 1}, or {@code null} for the first
     * @param progress  best fraction [0, 1] reached per theme (missing = 0)
     * @param random    seeded per world and lap, so the choice is reproducible
     */
    public static LapTheme pick(long n, Kind kind, LapTheme previous, Map<LapTheme, Double> progress, Random random) {
        if (kind == Kind.LAP1 && n == 0L) return LapTheme.VANILLA;
        return kind == Kind.LAP2 ? pickLap2(previous, progress, random) : pickLap1(previous, progress, random);
    }

    private static LapTheme pickLap2(LapTheme previous, Map<LapTheme, Double> progress, Random random) {
        List<LapTheme> candidates = without(EnumSet.of(LapTheme.BOP, LapTheme.BETTER), previous);
        if (candidates.size() == 1) return candidates.get(0);
        double bop = of(progress, LapTheme.BOP);
        double better = of(progress, LapTheme.BETTER);
        boolean bothDone = bop >= COMPLETE && better >= COMPLETE;
        if (bothDone || bop == better) return random.nextBoolean() ? LapTheme.BOP : LapTheme.BETTER;
        return bop < better ? LapTheme.BOP : LapTheme.BETTER;
    }

    private static LapTheme pickLap1(LapTheme previous, Map<LapTheme, Double> progress, Random random) {
        List<LapTheme> candidates = without(EnumSet.allOf(LapTheme.class), previous);
        List<LapTheme> open = new ArrayList<>();
        for (LapTheme t : candidates) {
            if (of(progress, t) < COMPLETE) open.add(t);
        }
        List<LapTheme> pool = open.isEmpty() ? candidates : open;
        return pool.get(random.nextInt(pool.size()));
    }

    private static List<LapTheme> without(EnumSet<LapTheme> set, LapTheme previous) {
        if (previous != null && set.size() > 1) set.remove(previous);
        return new ArrayList<>(set);   // EnumSet iterates in declaration order: stable for a seeded pick
    }

    private static double of(Map<LapTheme, Double> progress, LapTheme t) {
        if (progress == null) return 0.0;
        Double v = progress.get(t);
        return v == null || v.isNaN() ? 0.0 : Math.max(0.0, Math.min(1.0, v));
    }

    /** The per-lap seed: mixes the world's generation seed with {@code n} (splitmix64 finaliser). */
    public static long seedFor(long worldSeed, long n) {
        long z = worldSeed + 0x9E37_79B9_7F4A_7C15L * (n + 1L) + 0x4C61_7054_6865_6D65L;
        z = (z ^ (z >>> 30)) * 0xBF58_476D_1CE4_E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D0_49BB_1331_11EBL;
        return z ^ (z >>> 31);
    }
}
