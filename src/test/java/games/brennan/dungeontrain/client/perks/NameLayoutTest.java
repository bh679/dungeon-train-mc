package games.brennan.dungeontrain.client.perks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameLayoutTest {

    private static NameLayout.Box box(int[] at, NameLayout.Want w) {
        return new NameLayout.Box(at[0], at[1], at[0] + w.width(), at[1] + w.height());
    }

    @Test
    @DisplayName("two stars side by side: the second name moves off the first, never on top")
    void namesNeverOverlap() {
        NameLayout.Want a = new NameLayout.Want("a", 100, 100, 5, 60, 9);
        NameLayout.Want b = new NameLayout.Want("b", 120, 100, 5, 60, 9);
        Map<String, double[]> stars = Map.of("a", new double[] {100, 100, 5}, "b", new double[] {120, 100, 5});
        Map<String, int[]> out = NameLayout.place(List.of(a, b), List.of(), stars);
        assertEquals(new int[] {70, 108}[0], out.get("a")[0], "the first name sits below its star");
        assertTrue(out.containsKey("b"));
        assertFalse(box(out.get("a"), a).hits(box(out.get("b"), b)));
    }

    @Test
    @DisplayName("a name keeps clear of a class title, and is left out when nothing is free")
    void namesAvoidTitles() {
        NameLayout.Want a = new NameLayout.Want("a", 100, 100, 5, 40, 9);
        List<NameLayout.Box> titles = new ArrayList<>(List.of(new NameLayout.Box(60, 100, 140, 125)));
        Map<String, int[]> out = NameLayout.place(List.of(a), titles, Map.of("a", new double[] {100, 100, 5}));
        assertTrue(out.containsKey("a"));
        assertFalse(box(out.get("a"), a).hits(titles.get(0)), "moved off the title");
        titles.add(new NameLayout.Box(0, 0, 400, 400));
        assertTrue(NameLayout.place(List.of(a), titles, Map.of()).isEmpty(), "no free spot: no name");
    }
}
