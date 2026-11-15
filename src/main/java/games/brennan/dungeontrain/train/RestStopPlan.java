package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.worldgen.TrainPhase;

import java.util.function.IntPredicate;

/**
 * Where the train's guaranteed <b>rest stops</b> sit: carriages whose contents are forced to a bed
 * room, so a natural nap bed ({@code nap/NapEligibility}) is never more than {@link #MAX_GAP}
 * carriages away. Today's randomly rolled bed rooms stay on top of these and only shorten the gap.
 *
 * <p>Rest stop {@code k} opens a window at {@code k * SPACING + jitter(seed, k)}, jitter in
 * {@code [0, JITTER)}, so stops are about {@link #SPACING} apart and never evenly. The stop is the
 * first room-sized shell in that window ({@link #WINDOW} carriages): a half or full shell takes no
 * room contents, and a flatbed none at all. With {@code SPACING + JITTER + WINDOW <= MAX_GAP} two
 * consecutive stops are always closer than {@link #MAX_GAP}.</p>
 *
 * <p>Pure in {@code (seed, carriageIndex)}: walking back over a stretch re-places the same stop, and
 * negative indices (the train's back end) follow the same rule.</p>
 */
public final class RestStopPlan {

    /** Carriages between rest-stop windows. */
    public static final int SPACING = 200;
    /** Seeded nudge added to each window's start, {@code [0, JITTER)}. */
    public static final int JITTER = 40;
    /** Carriages a window spans, looking for a room-sized shell. */
    public static final int WINDOW = 8;
    /** Brennan's rule: at least one natural bed every this many carriages. */
    public static final int MAX_GAP = 250;

    /** The bed room a rest stop gets. Ungated, so it fits every phase a bed can sleep in. */
    public static final String REST_STOP_CONTENTS = "sleeping";

    /** Decorrelates the jitter from every other {@code (seed, index)} roll. */
    private static final long SALT = 0x5EB7_57A9_D00DL;

    private RestStopPlan() {}

    /** First carriage index of rest-stop window {@code k}. */
    public static int windowStart(long seed, int k) {
        return Math.multiplyExact(k, SPACING) + jitter(seed, k);
    }

    /** Index of the window that may hold {@code carriageIndex}, or {@code null} if none does. */
    public static Integer windowOf(long seed, int carriageIndex) {
        int k = Math.floorDiv(carriageIndex, SPACING);
        // A window starting late in stretch k-1 can't reach stretch k (JITTER + WINDOW < SPACING),
        // so only window k needs checking.
        int start = windowStart(seed, k);
        return carriageIndex >= start && carriageIndex < start + WINDOW ? k : null;
    }

    /**
     * Whether {@code carriageIndex} is a rest stop: it sits in a window, is itself room-sized, and no
     * earlier carriage of the same window is. {@code roomSized} answers for any index.
     */
    public static boolean isRestStop(long seed, int carriageIndex, IntPredicate roomSized) {
        Integer k = windowOf(seed, carriageIndex);
        if (k == null || !roomSized.test(carriageIndex)) return false;
        for (int i = windowStart(seed, k); i < carriageIndex; i++) {
            if (roomSized.test(i)) return false;
        }
        return true;
    }

    /**
     * Phases where a bed sleeps rather than exploding. The Nether and End cores (and the Deeper
     * Dark's Otherside) blow beds up ({@code BedBlockMixin}), so a rest stop there would be a trap.
     */
    public static boolean phaseAllowsBeds(TrainPhase phase) {
        return phase != TrainPhase.NETHER && phase != TrainPhase.END && phase != TrainPhase.DEEPER_DARK;
    }

    private static int jitter(long seed, int k) {
        long h = (seed ^ SALT) + k * 0x9E37_79B9_7F4A_7C15L;
        h = (h ^ (h >>> 30)) * 0xBF58_476D_1CE4_E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D0_49BB_1331_11EBL;
        h ^= h >>> 31;
        return (int) Math.floorMod(h, (long) JITTER);
    }
}
