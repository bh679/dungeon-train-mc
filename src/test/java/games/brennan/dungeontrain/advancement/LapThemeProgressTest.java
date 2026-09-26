package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.worldgen.LapTheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure parts of {@link LapThemeProgress}: milestone writes and the filled/clamped view. */
final class LapThemeProgressTest {

    @Test
    @DisplayName("progress is written at each 25% step and on reaching 100%, not in between")
    void milestones() {
        assertFalse(LapThemeProgress.crossesMilestone(0.0, 0.1));
        assertTrue(LapThemeProgress.crossesMilestone(0.2, 0.26));
        assertFalse(LapThemeProgress.crossesMilestone(0.26, 0.49));
        assertTrue(LapThemeProgress.crossesMilestone(0.74, 0.76));
        assertTrue(LapThemeProgress.crossesMilestone(0.99, 1.0));
        assertFalse(LapThemeProgress.crossesMilestone(1.0, 1.0));
    }

    @Test
    @DisplayName("every theme is present, missing ones are 0, values are clamped to [0, 1]")
    void filled() {
        Map<LapTheme, Double> f = LapThemeProgress.filled(Map.of(LapTheme.BOP, 1.7, LapTheme.BETTER, -0.2));
        assertEquals(0.0, f.get(LapTheme.VANILLA));
        assertEquals(1.0, f.get(LapTheme.BOP));
        assertEquals(0.0, f.get(LapTheme.BETTER));
        assertEquals(3, f.size());
    }
}
