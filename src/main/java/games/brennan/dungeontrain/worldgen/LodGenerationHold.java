package games.brennan.dungeontrain.worldgen;

/**
 * Shared state of the <b>pause hold</b> on Distant Horizons' LOD generation, and its pure decision.
 *
 * <p>A paused singleplayer game does not stop DH: its {@code DH-World Gen Thread[N]} workers keep
 * requesting chunks, the paused integrated server keeps servicing them from
 * {@code waitUntilNextTick}, and every one runs DT's Nether-core decoration. A player log showed two
 * hours on the ESC menu spent generating ~20 LOD chunks/s, then a game that ran at 200 ms/tick on
 * resume. {@code client.DistantHorizonsPauseHold} switches DH's distant generation off through DH's
 * API once the game has been paused for {@link #GRACE_MILLIS}, and back on when it resumes.</p>
 *
 * <p>This class names no DH or client types so the common {@code DebugCommand} can read the state
 * on any side — on a dedicated server it simply stays {@link Status#UNAVAILABLE}.</p>
 */
public final class LodGenerationHold {

    /** How long the game must stay paused before generation is held — a quick ESC never churns DH's queue. */
    public static final long GRACE_MILLIS = 10_000L;

    /** What the hold is doing right now, for the debug readout. */
    public enum Status {
        /** DH absent, not initialised, or its setting can't be overridden — DH generates as before. */
        UNAVAILABLE,
        /** Hook bound, game running (or paused for less than the grace). */
        IDLE,
        /** DH's distant generation is switched off by DT until the game resumes. */
        HOLDING
    }

    private static volatile Status status = Status.UNAVAILABLE;
    private static volatile long holdingSinceMillis;

    private LodGenerationHold() {}

    /**
     * Whether generation should be held, given when the current pause began ({@code -1} when the
     * game is not paused) and the current time.
     */
    public static boolean shouldHold(long pausedSinceMillis, long nowMillis) {
        return pausedSinceMillis >= 0 && nowMillis - pausedSinceMillis >= GRACE_MILLIS;
    }

    public static Status status() {
        return status;
    }

    public static void markIdle() {
        status = Status.IDLE;
    }

    public static void markUnavailable() {
        status = Status.UNAVAILABLE;
    }

    public static void markHolding(long nowMillis) {
        holdingSinceMillis = nowMillis;
        status = Status.HOLDING;
    }

    /** Seconds the current hold has lasted, or 0 when not holding. */
    public static long holdingSeconds(long nowMillis) {
        return status == Status.HOLDING ? Math.max(0L, (nowMillis - holdingSinceMillis) / 1000L) : 0L;
    }

    /** One-line readout for {@code /dungeontrain debug lod-lite status}. */
    public static String describe(long nowMillis) {
        return switch (status) {
            case HOLDING -> "HOLDING for " + holdingSeconds(nowMillis) + "s (game paused)";
            case IDLE -> "idle (holds after " + GRACE_MILLIS / 1000L + "s paused)";
            case UNAVAILABLE -> "unavailable (Distant Horizons absent or not overridable)";
        };
    }
}
