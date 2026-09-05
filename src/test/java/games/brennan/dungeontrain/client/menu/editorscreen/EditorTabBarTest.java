package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.editorscreen.InventoryEditorLayout.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EditorTabBarTest {

    private static final List<String> LABELS = List.of("All", "Carriages", "Contents", "Tracks",
        "Dimensions", "Settings");

    private static List<EditorTabBar.Tab> tabs(int width) {
        return EditorTabBar.layout(new Rect(6, 4, width - 12, 16), s -> s.length() * 6, LABELS);
    }

    @Test
    @DisplayName("All is first, the pages follow in order, Settings and Exit are locked to the right")
    void order() {
        List<EditorTabBar.Tab> t = tabs(640);
        assertEquals(EditorScreenPage.ALL, t.get(0).page());
        assertEquals(EditorScreenPage.CARRIAGES, t.get(1).page());
        assertEquals(EditorScreenPage.DIMENSIONS, t.get(4).page());
        assertEquals(EditorScreenPage.SETTINGS, t.get(5).page());
        assertEquals(EditorTabBar.Kind.EXIT, t.get(6).kind());
        EditorTabBar.Tab exit = t.get(6);
        assertEquals(6 + 640 - 12, exit.x() + exit.w());
        assertTrue(t.get(5).x() + t.get(5).w() <= exit.x());
        assertTrue(t.get(4).x() + t.get(4).w() <= t.get(5).x());
    }

    @Test
    @DisplayName("tabs never overlap, even on a narrow strip")
    void noOverlap() {
        for (int width : new int[] {427, 480, 640, 1920}) {
            List<EditorTabBar.Tab> t = tabs(width);
            for (int i = 1; i < t.size(); i++) {
                assertTrue(t.get(i - 1).x() + t.get(i - 1).w() <= t.get(i).x(),
                    width + ": " + t.get(i - 1) + " runs into " + t.get(i));
            }
        }
    }

    @Test
    @DisplayName("hit-testing returns the tab under the point and nothing below the strip")
    void hit() {
        Rect strip = new Rect(6, 4, 640 - 12, 16);
        List<EditorTabBar.Tab> t = tabs(640);
        assertSame(t.get(2), EditorTabBar.hit(t, strip, t.get(2).x() + 1, 10));
        assertNull(EditorTabBar.hit(t, strip, t.get(2).x() + 1, 40));
        assertNull(EditorTabBar.hit(t, strip, t.get(1).x() - 1, 10));
    }
}
