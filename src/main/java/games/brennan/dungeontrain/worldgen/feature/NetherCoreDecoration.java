package games.brennan.dungeontrain.worldgen.feature;

/**
 * "Inside Dungeon Train's own Nether-core feature pass" — the real-Nether decoration
 * {@link NetherTransitionFeature#decorateCoreChunkWithNetherFeatures} runs over a core chunk. Same shape
 * as {@link OverworldDecorationGuard#enter}/{@link OverworldDecorationGuard#exit}: a per-thread depth, so a
 * nested pass can't clear its parent's flag. Read by {@code WorldGenRegionFloorMixin} to apply
 * {@link StandingBlockKeep} only here, leaving BetterNether's own Nether and DT's other stamping untouched.
 */
public final class NetherCoreDecoration {

    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private NetherCoreDecoration() {}

    /** The core pass starts on this thread. Always pair with {@link #end} in a {@code finally}. */
    public static void begin() {
        DEPTH.get()[0]++;
    }

    public static void end() {
        int[] depth = DEPTH.get();
        if (depth[0] > 0) depth[0]--;
    }

    /** True while this thread is inside the core pass. */
    public static boolean isActive() {
        return DEPTH.get()[0] > 0;
    }
}
