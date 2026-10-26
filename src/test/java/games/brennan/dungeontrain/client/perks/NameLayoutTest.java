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
    @DisplayName("a name sits right of its dot, left-aligned and centred on it")
    void defaultIsRight() {
        NameLayout.Want a = new NameLayout.Want("a", 100, 100, 5, 40, 6);
        Map<String, int[]> out = NameLayout.place(List.of(a), List.of(), Map.of("a", new double[] {100, 100, 5}));
        assertEquals(108, out.get("a")[0]);
        assertEquals(97, out.get("a")[1]);
    }

    @Test
    @DisplayName("an unnamed star may be covered; a named star's dot may not")
    void onlyNamedStarsBlock() {
        NameLayout.Want a = new NameLayout.Want("a", 100, 100, 5, 40, 6);
        Map<String, double[]> stars = Map.of("a", new double[] {100, 100, 5}, "b", new double[] {125, 100, 5});
        assertEquals(108, NameLayout.place(List.of(a), List.of(), stars).get("a")[0], "b is unnamed: covered");
        NameLayout.Want b = new NameLayout.Want("b", 125, 100, 5, 40, 6);
        Map<String, int[]> both = NameLayout.place(List.of(b, a), List.of(), stars);
        assertFalse(box(both.get("a"), a).hits(box(both.get("b"), b)), "names never overlap");
        assertTrue(both.get("a")[0] != 108 || both.get("a")[1] != 97, "a moved off b's dot");
    }

    @Test
    @DisplayName("a hovered name always shows, clear of the other names")
    void forcedAlwaysPlaced() {
        NameLayout.Want a = new NameLayout.Want("a", 100, 100, 5, 40, 6, true);
        List<NameLayout.Box> titles = new ArrayList<>(List.of(new NameLayout.Box(105, 90, 160, 110)));
        Map<String, int[]> out = NameLayout.place(List.of(a), titles, Map.of("a", new double[] {100, 100, 5}));
        assertTrue(out.containsKey("a"));
        assertFalse(box(out.get("a"), a).hits(titles.get(0)));
        titles.add(new NameLayout.Box(0, 0, 400, 400));
        assertTrue(NameLayout.place(List.of(a), titles, Map.of()).containsKey("a"), "even with nowhere free");
    }
}
