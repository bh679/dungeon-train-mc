package games.brennan.dungeontrain.client.wind;

import games.brennan.dungeontrain.client.TrainDebugState;
import games.brennan.dungeontrain.wind.TrainExposure;
import games.brennan.dungeontrain.wind.WindProfile;
import games.brennan.dungeontrain.wind.WindSample;

/**
 * What the F3+8 wind panel ({@link WindDebugHud}) shows: the last client tick's wind {@link Frame}, plus a
 * 10-second history of the wind now, the wind the visuals are showing, and the push actually applied, for the
 * panel's graph. Written by {@link WindClientEvents} on the client tick, read on the render thread.
 */
public final class WindDebugState {

    /** Ticks of history the graph shows (10 s). */
    public static final int HISTORY = 200;

    /**
     * One client tick of wind.
     *
     * @param d          blocks past the wind start
     * @param stage      the interpolated stage at {@code d}, {@code null} for no profile
     * @param overTrain  a carriage is under the player
     * @param skip       why the player is never pushed right now ({@code null} = they can be)
     * @param applied    push applied this tick (0 when sheltered, skipped or capped)
     * @param wouldBe    push this tick if exposed, with sneak/air/resistance
     * @param velX,velZ  the player's horizontal velocity after the push
     * @param windyHook  Windy: Configurable's hook is steering the streaks
     * @param windyStrength the Living Wind strength handed to Windy (0.08 = first streaks, 0.75 = strong)
     */
    public record Frame(long tick, double d, long startX, WindProfile.Stage stage,
                        WindSample now, WindSample ahead, int leadTicks,
                        boolean overTrain, TrainExposure.Result exposure, String skip,
                        boolean onGround, boolean sneaking, double applied, double wouldBe,
                        double velX, double velZ, boolean windyHook, float windyStrength) {}

    private static volatile boolean visible = false;
    private static volatile Frame frame = null;
    private static volatile String idleReason = "no wind data yet";

    private static final double[] NOW = new double[HISTORY];
    private static final double[] AHEAD = new double[HISTORY];
    private static final double[] APPLIED = new double[HISTORY];
    private static int head = 0;
    private static int filled = 0;

    private WindDebugState() {}

    /** F3+8. A no-op without a debug access grant, like the other DT panels. */
    public static void toggleVisible() {
        if (!TrainDebugState.permitted()) return;
        visible = !visible;
    }

    public static boolean visible() {
        return visible && TrainDebugState.permitted();
    }

    static synchronized void record(Frame f) {
        frame = f;
        NOW[head] = f.now().strength();
        AHEAD[head] = f.ahead().strength();
        APPLIED[head] = f.applied();
        head = (head + 1) % HISTORY;
        filled = Math.min(HISTORY, filled + 1);
    }

    static void clear(String reason) {
        frame = null;
        idleReason = reason;
    }

    public static Frame frame() {
        return frame;
    }

    public static String idleReason() {
        return idleReason;
    }

    /** Oldest-first copies of the three histories: {@code [now, ahead, applied]}. */
    public static synchronized double[][] history() {
        double[][] out = new double[3][filled];
        int start = (head - filled + HISTORY) % HISTORY;
        for (int i = 0; i < filled; i++) {
            int j = (start + i) % HISTORY;
            out[0][i] = NOW[j];
            out[1][i] = AHEAD[j];
            out[2][i] = APPLIED[j];
        }
        return out;
    }

    public static synchronized void reset() {
        frame = null;
        head = 0;
        filled = 0;
    }
}
