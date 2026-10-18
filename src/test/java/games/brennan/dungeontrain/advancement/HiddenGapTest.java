package games.brennan.dungeontrain.advancement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiddenGapTest {

    /** root → a → b → c → d, and root → e. */
    private static final Map<String, String> PARENT = Map.of(
        "a", "root", "b", "a", "c", "b", "d", "c", "e", "root");

    private static final Map<String, List<String>> CHILDREN = Map.of(
        "root", List.of("a", "e"), "a", List.of("b"), "b", List.of("c"), "c", List.of("d"),
        "d", List.of(), "e", List.of());

    private static HiddenGap.Walk<String> walk(String node, Set<String> visible) {
        return HiddenGap.walk(node, PARENT::get, visible::contains);
    }

    @Test
    @DisplayName("A visible parent is a direct link: nothing skipped")
    void directLink() {
        HiddenGap.Walk<String> w = walk("d", Set.of("root", "c", "d"));
        assertEquals("c", w.ancestor());
        assertEquals(0, w.skipped());
    }

    @Test
    @DisplayName("Counts every hidden advancement up to the nearest visible one")
    void countsHidden() {
        HiddenGap.Walk<String> w = walk("d", Set.of("root", "a", "d"));
        assertEquals("a", w.ancestor());
        assertEquals(2, w.skipped());
        assertTrue(HiddenGap.isMarked(w.skipped()));
    }

    @Test
    @DisplayName("One hidden advancement is not marked")
    void oneHiddenNotMarked() {
        HiddenGap.Walk<String> w = walk("d", Set.of("root", "b", "d"));
        assertEquals(1, w.skipped());
        assertFalse(HiddenGap.isMarked(w.skipped()));
    }

    @Test
    @DisplayName("No visible ancestor: no parent and nothing counted")
    void noVisibleAncestor() {
        HiddenGap.Walk<String> w = walk("d", Set.of("d"));
        assertNull(w.ancestor());
        assertEquals(0, w.skipped());
    }

    @Test
    @DisplayName("A marked node and its whole subtree move one column right; siblings stay")
    void shiftsMarkedSubtree() {
        Map<String, Float> x = new HashMap<>(Map.of("root", 0F, "a", 1F, "b", 2F, "c", 3F, "d", 4F, "e", 1F));
        HiddenGap.shiftColumns("root", CHILDREN::get, n -> n.equals("b") || n.equals("d"), x::get, x::put);
        assertEquals(0F, x.get("root"));
        assertEquals(1F, x.get("a"));
        assertEquals(3F, x.get("b"));
        assertEquals(4F, x.get("c"));
        assertEquals(6F, x.get("d"));
        assertEquals(1F, x.get("e"));
    }

    @Test
    @DisplayName("The line is cut around the middle of the open stretch, with three dots in the cut")
    void splitsLine() {
        // Child centre at 83, elbow at 30: open stretch 30..73, middle 51.
        HiddenGap.Split split = HiddenGap.split(83, 30, 83);
        assertArrayEquals(new int[] {48, 51, 54}, split.dots());
        assertEquals(2, split.segments().size());
        assertArrayEquals(new int[] {30, 46}, split.segments().get(0));
        assertArrayEquals(new int[] {56, 83}, split.segments().get(1));
    }

    @Test
    @DisplayName("Too short a line is drawn whole, without dots")
    void shortLineUnchanged() {
        HiddenGap.Split split = HiddenGap.split(43, 30, 43);
        assertFalse(split.hasDots());
        assertEquals(1, split.segments().size());
        assertArrayEquals(new int[] {43, 30}, split.segments().get(0));
    }
}
