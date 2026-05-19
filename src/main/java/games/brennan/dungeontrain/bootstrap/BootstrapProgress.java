package games.brennan.dungeontrain.bootstrap;

/**
 * Shared progress state for the world-load bootstrap phase. Written from
 * the server thread by
 * {@link games.brennan.dungeontrain.train.TrainCarriageAppender#eagerFillForBootstrap},
 * read from the client thread by
 * {@code mixin.client.LevelLoadingScreenProgressMixin} to render a
 * progress line on the loading screen while the server thread is blocked
 * synchronously spawning carriages.
 *
 * <p>Single-player only by intent: on a dedicated server there is no
 * client to render to, so the holder simply stays cleared on the client
 * side — server-side progress logs cover the dedicated case.</p>
 *
 * <p>All fields are {@code volatile}: writes from
 * {@code TrainCarriageAppender} happen on the server thread; reads from
 * the mixin happen on the client/render thread. We accept torn reads for
 * the {@code current}/{@code total} pair — a one-frame mismatch in the
 * visible "X / Y" text is invisible to a player.</p>
 */
public final class BootstrapProgress {

    private static volatile int current = 0;
    private static volatile int total = 0;
    private static volatile String phase = "";

    private BootstrapProgress() {}

    /**
     * Show indeterminate-phase text on the loading screen — just a label,
     * no X/Y counter. Use for short steps where computing a count would
     * be more trouble than it's worth (e.g. anchoring world spawn).
     */
    public static void setPhase(String phaseName) {
        phase = phaseName;
        total = 0;
        current = 0;
    }

    /**
     * Begin a counted progress span. {@code totalCount} is the number of
     * units the operation will increment through (e.g. carriages to
     * place). {@code currentStart} is the starting count, useful when
     * some units are already accounted for at the call site (e.g. the
     * seed group is already placed before the eager-fill loop starts).
     */
    public static void start(String phaseName, int totalCount, int currentStart) {
        phase = phaseName;
        total = totalCount;
        current = currentStart;
    }

    /** Increment progress by {@code amount}. Counted phase only. */
    public static void advance(int amount) {
        current += amount;
    }

    /** Mark the operation complete and hide the indicator. */
    public static void clear() {
        total = 0;
        current = 0;
        phase = "";
    }

    /** Indicator is visible whenever {@code phase} is non-empty. */
    public static boolean isActive() {
        return !phase.isEmpty();
    }

    /** {@code true} when the phase has a determinate {@code total} count. */
    public static boolean hasCount() {
        return total > 0;
    }

    public static int current() { return current; }
    public static int total() { return total; }
    public static String phase() { return phase; }
}
