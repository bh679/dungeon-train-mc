package games.brennan.dungeontrain.worldgen;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * One world's theme-lap decisions: which {@link LapTheme} each theme lap wears. A lap is decided the
 * first time anything asks for it ({@link #resolve}) — in practice when the chunks ahead of the train
 * first reach it — and never changes after that: chunks are generated ahead of the players, so a
 * decision recomputed later could paint half a lap one way and half another.
 *
 * <p>Laps are decided in order: asking for lap {@code n} decides every earlier lap first, so the
 * "never repeat the previous lap" rule always sees the theme that was really chosen. Thread-safe —
 * worldgen threads resolve concurrently. Pure (no Minecraft types); the world's {@code LapThemeData}
 * persists {@link #decisions} and supplies the progress snapshot.</p>
 */
public final class LapThemePlan {

    /** Called once per new decision (persist + log). */
    public interface Listener {
        void decided(long n, LapTheme theme, Map<LapTheme, Double> progress);
    }

    /** Hard cap on laps: runs double in length, so lap 64 is far past any reachable X. */
    static final long MAX_LAPS = 128L;

    private final long worldSeed;
    private final TreeMap<Long, LapTheme> decided;
    private final Supplier<Map<LapTheme, Double>> progress;
    private final Listener listener;

    public LapThemePlan(long worldSeed, Map<Long, LapTheme> decided,
                        Supplier<Map<LapTheme, Double>> progress, Listener listener) {
        this.worldSeed = worldSeed;
        this.decided = new TreeMap<>(decided);
        this.progress = progress;
        this.listener = listener;
    }

    /**
     * The theme of lap {@code n}, deciding it (and every earlier lap) now if it has not been.
     * {@code kinds} are the layout's theme groups per run: lap {@code n} is group {@code n % kinds.size()}.
     */
    public synchronized LapTheme resolve(long n, List<LapThemePicker.Kind> kinds) {
        if (n < 0L || kinds.isEmpty()) return LapTheme.VANILLA;
        long lap = Math.min(n, MAX_LAPS);
        LapTheme known = decided.get(lap);
        if (known != null) return known;
        Map<LapTheme, Double> snapshot = null;
        LapTheme previous = null;
        for (long i = 0; i <= lap; i++) {
            LapTheme t = decided.get(i);
            if (t == null) {
                if (snapshot == null) snapshot = Map.copyOf(progress.get());
                LapThemePicker.Kind kind = kinds.get((int) (i % kinds.size()));
                t = LapThemePicker.pick(i, kind, previous, snapshot, new Random(LapThemePicker.seedFor(worldSeed, i)));
                decided.put(i, t);
                listener.decided(i, t, snapshot);
            }
            previous = t;
        }
        return previous;
    }

    /** The theme of lap {@code n} if already decided, else {@code null}. Never decides. */
    public synchronized LapTheme peek(long n) {
        return decided.get(Math.min(n, MAX_LAPS));
    }

    /** Every decision so far, lap → theme, in lap order (a copy). */
    public synchronized Map<Long, LapTheme> decisions() {
        return new TreeMap<>(decided);
    }
}
