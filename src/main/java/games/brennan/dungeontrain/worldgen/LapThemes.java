package games.brennan.dungeontrain.worldgen;

import java.util.List;

/**
 * The running world's {@link LapThemePlan}, where {@link WorldGenCycle} resolves {@code :t1} /
 * {@code :t2} slots. Published when the overworld loads ({@code NetherBandContextEvents}) and cleared
 * when the server stops — the same lifetime as {@code NetherBandContext}.
 *
 * <p>With no plan (unit tests, a client, the moment before the overworld loads) a lap falls back to
 * the look the order had before themes existed: Lap 1 vanilla, Lap 2 WWOO + BetterNether + BetterEnd.</p>
 */
public final class LapThemes {

    private static volatile LapThemePlan current;

    private LapThemes() {}

    public static void publish(LapThemePlan plan) {
        current = plan;
    }

    public static void clear() {
        current = null;
    }

    /** The published plan, or {@code null}. */
    public static LapThemePlan current() {
        return current;
    }

    /** The theme of lap {@code n} — deciding it now if needed; the fallback when no plan is published. */
    public static LapTheme resolve(long n, List<LapThemePicker.Kind> kinds) {
        LapThemePlan plan = current;
        if (plan != null) return plan.resolve(n, kinds);
        return fallback(n, kinds);
    }

    /** The theme of lap {@code n} if decided (or the fallback with no plan), else {@code null}. Never decides. */
    public static LapTheme peek(long n, List<LapThemePicker.Kind> kinds) {
        LapThemePlan plan = current;
        if (plan != null) return plan.peek(n);
        return fallback(n, kinds);
    }

    static LapTheme fallback(long n, List<LapThemePicker.Kind> kinds) {
        if (n < 0L || kinds.isEmpty()) return LapTheme.VANILLA;
        return kinds.get((int) (n % kinds.size())) == LapThemePicker.Kind.LAP2 ? LapTheme.BETTER : LapTheme.VANILLA;
    }
}
