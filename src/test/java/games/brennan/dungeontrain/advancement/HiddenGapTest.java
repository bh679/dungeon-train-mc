package games.brennan.dungeontrain.advancement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
        assertArrayEquals(new int[] {47, 51, 55}, split.dots());
        assertEquals(2, split.segments().size());
        assertArrayEquals(new int[] {30, 44}, split.segments().get(0));
        assertArrayEquals(new int[] {58, 83}, split.segments().get(1));
    }

    @Test
    @DisplayName("Too short a line is drawn whole, without dots")
    void shortLineUnchanged() {
        HiddenGap.Split split = HiddenGap.split(43, 30, 43);
        assertFalse(split.hasDots());
        assertEquals(1, split.segments().size());
        assertArrayEquals(new int[] {43, 30}, split.segments().get(0));
    }

    @Test
    @DisplayName("A revealed parent re-links the child that skipped it, with the new count")
    void revealedParentRelinks() {
        // d was sent hanging from a (b and c hidden); now b is revealed.
        Map<String, String> sent = Map.of("d", "a");
        List<HiddenGap.Relink<String>> relinks = HiddenGap.changedLinks(List.of("d"), n -> n, PARENT::get,
            Set.of("root", "a", "b", "d")::contains, sent);
        assertEquals(List.of(new HiddenGap.Relink<>("d", "b", 1)), relinks);
    }

    @Test
    @DisplayName("Nothing changes while the same ancestor is still the nearest visible one")
    void unchangedLinkNotRelinked() {
        List<HiddenGap.Relink<String>> relinks = HiddenGap.changedLinks(List.of("d"), n -> n, PARENT::get,
            Set.of("root", "a", "d")::contains, Map.of("d", "a"));
        assertTrue(relinks.isEmpty());
    }

    @Test
    @DisplayName("Advancements never sent with a parent are not checked")
    void unsentNotChecked() {
        List<HiddenGap.Relink<String>> relinks = HiddenGap.changedLinks(List.of("d"), n -> n, PARENT::get,
            Set.of("root", "a", "b", "d")::contains, Map.of());
        assertTrue(relinks.isEmpty());
    }

    /** A mutable fake tree for {@link HiddenGap#moveUnder}. */
    private static final class FakeTree implements HiddenGap.Links<String> {
        final Map<String, String> parent = new HashMap<>(PARENT);
        final Map<String, List<String>> children = new HashMap<>();

        FakeTree() {
            CHILDREN.forEach((k, v) -> children.put(k, new ArrayList<>(v)));
        }

        @Override public String parentOf(String node) { return parent.get(node); }
        @Override public void setParent(String node, String p) { parent.put(node, p); }
        @Override public void removeChild(String p, String child) { children.get(p).remove(child); }
        @Override public void addChild(String p, String child) { children.get(p).add(child); }
    }

    @Test
    @DisplayName("Moving a child under a revealed parent takes it off the old one")
    void moveUnderRelinks() {
        FakeTree t = new FakeTree();
        // Client view: d sits under a; b was just revealed under a.
        t.parent.put("d", "a");
        t.children.get("a").add("d");
        t.children.get("b").remove("c");
        t.children.get("c").remove("d");
        assertTrue(HiddenGap.moveUnder("d", "b", t));
        assertEquals("b", t.parent.get("d"));
        assertEquals(List.of("b"), t.children.get("a"));
        assertEquals(List.of("d"), t.children.get("b"));
    }

    @Test
    @DisplayName("A move that would loop the tree, or change nothing, is refused")
    void moveUnderRefusesLoopsAndNoOps() {
        FakeTree t = new FakeTree();
        assertFalse(HiddenGap.moveUnder("a", "c", t));
        assertFalse(HiddenGap.moveUnder("a", "a", t));
        assertFalse(HiddenGap.moveUnder("d", "c", t));
        assertEquals("root", t.parent.get("a"));
    }
}
