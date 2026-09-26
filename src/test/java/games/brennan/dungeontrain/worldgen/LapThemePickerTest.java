package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for {@link LapThemePicker} and {@link LapTheme}. */
final class LapThemePickerTest {

    private static Map<LapTheme, Double> progress(double vanilla, double bop, double better) {
        Map<LapTheme, Double> m = new EnumMap<>(LapTheme.class);
        m.put(LapTheme.VANILLA, vanilla);
        m.put(LapTheme.BOP, bop);
        m.put(LapTheme.BETTER, better);
        return m;
    }

    private static Set<LapTheme> outcomes(long n, LapThemePicker.Kind kind, LapTheme previous, Map<LapTheme, Double> p) {
        Set<LapTheme> seen = EnumSet.noneOf(LapTheme.class);
        for (long seed = 0; seed < 200; seed++) {
            seen.add(LapThemePicker.pick(n, kind, previous, p, new Random(LapThemePicker.seedFor(7L, seed))));
        }
        return seen;
    }

    @Test
    @DisplayName("the first cycle's Lap 1 is always vanilla, whatever the progress")
    void firstLapVanilla() {
        assertEquals(Set.of(LapTheme.VANILLA),
                outcomes(0, LapThemePicker.Kind.LAP1, null, progress(1, 1, 1)));
    }

    @Test
    @DisplayName("Lap 2 goes to the theme the player has got least far in")
    void lap2LeastProgress() {
        assertEquals(Set.of(LapTheme.BOP), outcomes(1, LapThemePicker.Kind.LAP2, LapTheme.VANILLA, progress(1, 0.2, 0.9)));
        assertEquals(Set.of(LapTheme.BETTER), outcomes(1, LapThemePicker.Kind.LAP2, LapTheme.VANILLA, progress(1, 1.0, 0.5)));
    }

    @Test
    @DisplayName("Lap 2 is 50:50 on a tie and when both are 100% complete")
    void lap2Tie() {
        assertEquals(EnumSet.of(LapTheme.BOP, LapTheme.BETTER),
                outcomes(1, LapThemePicker.Kind.LAP2, LapTheme.VANILLA, progress(0, 0, 0)));
        assertEquals(EnumSet.of(LapTheme.BOP, LapTheme.BETTER),
                outcomes(1, LapThemePicker.Kind.LAP2, LapTheme.VANILLA, progress(1, 1, 1)));
        int bop = 0;
        for (long seed = 0; seed < 2000; seed++) {
            if (LapThemePicker.pick(1, LapThemePicker.Kind.LAP2, LapTheme.VANILLA, progress(1, 1, 1), new Random(LapThemePicker.seedFor(7L, seed))) == LapTheme.BOP) bop++;
        }
        assertTrue(bop > 850 && bop < 1150, "roughly even split, got " + bop);
    }

    @Test
    @DisplayName("Lap 2 never repeats the Lap 1 just before it, even against the progress rule")
    void lap2NoRepeat() {
        assertEquals(Set.of(LapTheme.BETTER), outcomes(3, LapThemePicker.Kind.LAP2, LapTheme.BOP, progress(0, 0, 1)));
        assertEquals(Set.of(LapTheme.BOP), outcomes(3, LapThemePicker.Kind.LAP2, LapTheme.BETTER, progress(0, 1, 0)));
    }

    @Test
    @DisplayName("later Lap 1s pick randomly among incomplete themes, never the previous one")
    void lap1LaterCycles() {
        assertEquals(EnumSet.of(LapTheme.VANILLA, LapTheme.BOP),
                outcomes(2, LapThemePicker.Kind.LAP1, LapTheme.BETTER, progress(0.5, 0.1, 0)));
        // BoP done: only vanilla is still open (BETTER was the lap before)
        assertEquals(Set.of(LapTheme.VANILLA),
                outcomes(2, LapThemePicker.Kind.LAP1, LapTheme.BETTER, progress(0.5, 1.0, 0)));
        // everything done: random among all but the previous
        assertEquals(EnumSet.of(LapTheme.VANILLA, LapTheme.BETTER),
                outcomes(2, LapThemePicker.Kind.LAP1, LapTheme.BOP, progress(1, 1, 1)));
        // only the previous lap's theme is open: fall back to the others rather than repeat
        assertEquals(EnumSet.of(LapTheme.VANILLA, LapTheme.BOP),
                outcomes(4, LapThemePicker.Kind.LAP1, LapTheme.BETTER, progress(1, 1, 0.3)));
    }

    @Test
    @DisplayName("the per-lap seed is deterministic and differs between laps")
    void seeds() {
        assertEquals(LapThemePicker.seedFor(42, 3), LapThemePicker.seedFor(42, 3));
        assertNotEquals(LapThemePicker.seedFor(42, 3), LapThemePicker.seedFor(42, 4));
        assertNotEquals(LapThemePicker.seedFor(42, 3), LapThemePicker.seedFor(43, 3));
    }

    @Test
    @DisplayName("themes map onto slot styles: BoP everywhere, WWOO + Better, or vanilla")
    void styles() {
        assertEquals(CycleLayout.Style.BOP, LapTheme.BOP.styleFor(CycleLayout.Type.OVERWORLD));
        assertEquals(CycleLayout.Style.BOP, LapTheme.BOP.styleFor(CycleLayout.Type.NETHER));
        assertEquals(CycleLayout.Style.BOP, LapTheme.BOP.styleFor(CycleLayout.Type.END));
        assertEquals(CycleLayout.Style.WWOO, LapTheme.BETTER.styleFor(CycleLayout.Type.OVERWORLD));
        assertEquals(CycleLayout.Style.BETTER, LapTheme.BETTER.styleFor(CycleLayout.Type.NETHER));
        assertEquals(CycleLayout.Style.BETTER, LapTheme.BETTER.styleFor(CycleLayout.Type.END));
        assertEquals(CycleLayout.Style.VANILLA, LapTheme.VANILLA.styleFor(CycleLayout.Type.END));
        assertEquals(CycleLayout.Style.VANILLA, LapTheme.BOP.styleFor(CycleLayout.Type.SPHERES));
        assertEquals(LapTheme.BETTER, LapTheme.byId("better"));
    }
}
