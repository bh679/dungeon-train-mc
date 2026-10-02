package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FlatbedPadSelection#draw}: one flatbed per group, by weight, the built-in when alone. */
final class FlatbedPadSelectionTest {

    @Test
    @DisplayName("with only the built-in flatbed every group's pads are the built-in")
    void builtinAlone() {
        for (long g = 0; g < 200; g++) {
            assertEquals("flatbed", FlatbedPadSelection.draw(3L, g, List.of("flatbed"), id -> 1, "flatbed"));
            assertEquals("flatbed", FlatbedPadSelection.draw(3L, g, List.of(), id -> 1, "flatbed"));
        }
    }

    @Test
    @DisplayName("a group draws the same flatbed every time; variants share groups by weight")
    void weightedAndStable() {
        Map<String, Integer> w = Map.of("flatbed", 3, "planked", 1);
        List<String> ids = List.of("flatbed", "planked");
        int planked = 0;
        for (long g = 0; g < 4000; g++) {
            String a = FlatbedPadSelection.draw(9L, g, ids, w::get, "flatbed");
            assertEquals(a, FlatbedPadSelection.draw(9L, g, ids, w::get, "flatbed"),
                "both of a group's pads ask for the same group — they must get the same flatbed");
            if (a.equals("planked")) planked++;
        }
        double share = planked / 4000.0;
        assertTrue(share > 0.20 && share < 0.30, "expected ~25%, got " + share);
    }

    @Test
    @DisplayName("all weights zero falls back to the built-in")
    void zeroWeights() {
        assertEquals("flatbed", FlatbedPadSelection.draw(1L, 7, List.of("planked"), id -> 0, "flatbed"));
    }
}
