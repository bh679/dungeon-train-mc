package games.brennan.dungeontrain.worldgen;

import java.util.HashMap;
import java.util.Map;

/** Fixed {@link LapThemePlan}s for tests that need a known look on every lap. */
public final class TestLapPlans {

    private TestLapPlans() {}

    /**
     * Lap 1 of every run BoP, Lap 2 of every run WWOO + Better — so each run carries every modded look
     * (BoP overworld / Nether / End and WWOO / BetterNether / BetterEnd). Test-only: a real world's first
     * lap is always vanilla.
     */
    public static LapThemePlan bopThenBetter() {
        Map<Long, LapTheme> decided = new HashMap<>();
        for (long n = 0; n <= LapThemePlan.MAX_LAPS; n++) decided.put(n, (n & 1L) == 0L ? LapTheme.BOP : LapTheme.BETTER);
        return new LapThemePlan(0L, decided, Map::of, (n, t, p) -> {});
    }
}
