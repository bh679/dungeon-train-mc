package games.brennan.dungeontrain.worldgen.feature;

/**
 * "Inside Dungeon Train's own Nether-core feature pass" — the real-Nether decoration
 * {@link NetherTransitionFeature#decorateCoreChunkWithNetherFeatures} runs over a core chunk. Same shape
 * as {@link OverworldDecorationGuard#enter}/{@link OverworldDecorationGuard#exit}: a per-thread depth, so a
 * nested pass can't clear its parent's flag. Read by {@code WorldGenRegionFloorMixin} to apply
 * {@link StandingBlockKeep} only here, leaving BetterNether's own Nether and DT's other stamping untouched.
 *
 * <p>The pass also carries the core slab's <b>roof</b>: the top world Y of the stamped Nether band. Its top
 * face is often open sky, and the Nether's features would treat it as one more floor (patches spread onto
 * it, every-layer fungi find it first), so the mixin drops every write above it ({@link #isAboveRoof}).</p>
 */
public final class NetherCoreDecoration {

    /** Deepest nesting tracked for roofs; deeper passes reuse the last slot (never expected in practice). */
    private static final int MAX_DEPTH = 8;

    /** [0] = depth, [1..MAX_DEPTH] = the roof Y of each open pass. */
    private static final ThreadLocal<int[]> STATE = ThreadLocal.withInitial(() -> new int[MAX_DEPTH + 1]);

    private NetherCoreDecoration() {}

    /**
     * The core pass starts on this thread; nothing it writes may land above {@code roofY}. Always pair with
     * {@link #end} in a {@code finally}.
     */
    public static void begin(int roofY) {
        int[] state = STATE.get();
        state[0]++;
        state[Math.min(state[0], MAX_DEPTH)] = roofY;
    }

    public static void end() {
        int[] state = STATE.get();
        if (state[0] > 0) state[0]--;
    }

    /** True while this thread is inside the core pass. */
    public static boolean isActive() {
        return STATE.get()[0] > 0;
    }

    /** True when this thread is inside the core pass and {@code y} is above the innermost pass's roof. */
    public static boolean isAboveRoof(int y) {
        int[] state = STATE.get();
        return state[0] > 0 && y > state[Math.min(state[0], MAX_DEPTH)];
    }
}
