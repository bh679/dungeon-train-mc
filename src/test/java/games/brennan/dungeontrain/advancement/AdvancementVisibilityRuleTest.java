package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.advancement.AdvancementVisibilityRule.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A chain root → a → b → c, plus d under root. */
class AdvancementVisibilityRuleTest {

    private static final Map<String, String> PARENT = Map.of("a", "root", "b", "a", "c", "b", "d", "root");

    private static boolean visible(String node, Set<String> done, Map<String, Mode> modes, boolean rootDefault) {
        return AdvancementVisibilityRule.isVisible(node, PARENT::get, done::contains, modes::get, n -> rootDefault);
    }

    @Test
    @DisplayName("default: hidden until the direct parent is earned")
    void parentDefault() {
        Set<String> done = Set.of("root");
        assertTrue(visible("a", done, Map.of(), true));
        assertFalse(visible("b", done, Map.of(), true));
        assertTrue(visible("b", Set.of("root", "a"), Map.of(), true));
    }

    @Test
    @DisplayName("always: shows while its parent is visible, so a whole branch follows its head")
    void alwaysFollowsParentVisibility() {
        Map<String, Mode> modes = new HashMap<>(Map.of("b", Mode.ALWAYS, "c", Mode.ALWAYS));
        Set<String> done = Set.of("root");
        assertTrue(visible("a", done, modes, true), "a: frontier");
        assertTrue(visible("b", done, modes, true), "b: a is visible");
        assertTrue(visible("c", done, modes, true), "c: b is visible");
        assertFalse(visible("c", Set.of(), modes, false), "nothing shows under an unearned hidden root");
    }

    @Test
    @DisplayName("earned: hidden until earned, even on the frontier; earned always shows")
    void earnedOnly() {
        Map<String, Mode> modes = Map.of("a", Mode.EARNED);
        assertFalse(visible("a", Set.of("root"), modes, true));
        assertTrue(visible("a", Set.of("root", "a"), modes, true));
    }

    @Test
    @DisplayName("a tab head keeps vanilla's answer unless a mode is set")
    void rootModes() {
        assertTrue(visible("root", Set.of(), Map.of(), true));
        assertFalse(visible("root", Set.of(), Map.of(), false));
        assertTrue(visible("root", Set.of(), Map.of("root", Mode.ALWAYS), false));
        assertFalse(visible("root", Set.of(), Map.of("root", Mode.EARNED), true));
    }

    @Test
    @DisplayName("modes parse from the editor's strings; anything else means none")
    void parse() {
        assertEquals(Mode.ALWAYS, Mode.parse("always"));
        assertEquals(Mode.EARNED, Mode.parse("earned"));
        assertEquals(Mode.PARENT, Mode.parse("parent"));
        assertEquals(null, Mode.parse("sometimes"));
        assertEquals(null, Mode.parse(null));
    }
}
