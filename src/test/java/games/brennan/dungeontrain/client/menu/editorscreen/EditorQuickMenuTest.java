package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.editorscreen.InventoryEditorLayout.Rect;
import games.brennan.dungeontrain.editor.EditorMenusMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EditorQuickMenuTest {

    private static final Rect STRIP = new Rect(6, 4, 628, 16);
    private static final EditorTabBar.Tab ARROW =
        new EditorTabBar.Tab(EditorTabBar.Kind.QUICK, null, "", 595, EditorTabBar.QUICK_W);

    @Test
    @DisplayName("the dropdown hangs under the arrow, left edge flush: one row of icons, Mirror / Menus / Frame left to right")
    void layout() {
        Rect b = EditorQuickMenu.bounds(ARROW, STRIP);
        assertEquals(ARROW.x(), b.x());
        assertTrue(b.y() >= STRIP.bottom(), "below the tab strip");
        List<EditorQuickMenu.Row> rows = EditorQuickMenu.layout(ARROW, STRIP);
        assertEquals(List.of(EditorQuickMenu.Item.MIRROR, EditorQuickMenu.Item.MENUS, EditorQuickMenu.Item.FRAME),
            rows.stream().map(EditorQuickMenu.Row::item).toList());
        for (EditorQuickMenu.Row r : rows) {
            assertTrue(r.x() >= b.x() && r.x() + r.w() <= b.right(), "cell inside the panel");
            assertTrue(r.y() >= b.y() && r.y() + EditorQuickMenu.ROW_H <= b.bottom(), "cell inside the panel");
            assertEquals(rows.get(0).y(), r.y(), "one horizontal row");
        }
        for (int i = 1; i < rows.size(); i++) {
            assertEquals(rows.get(i - 1).x() + rows.get(i - 1).w(), rows.get(i).x(), "cells side by side");
        }
    }

    @Test
    @DisplayName("hit-testing finds the row under the point and nothing outside")
    void hit() {
        List<EditorQuickMenu.Row> rows = EditorQuickMenu.layout(ARROW, STRIP);
        EditorQuickMenu.Row menus = rows.get(1);
        assertEquals(EditorQuickMenu.Item.MENUS, EditorQuickMenu.hit(rows, menus.x() + 2, menus.y() + 2));
        assertNull(EditorQuickMenu.hit(rows, menus.x() + 2, menus.y() + EditorQuickMenu.ROW_H + 2));
        assertNull(EditorQuickMenu.hit(rows, menus.x() + 2, STRIP.y() + 2));
    }

    @Test
    @DisplayName("Menus: a visible mode goes Off, and Off goes back to the mode it came from")
    void menusRemembersVisibleMode() {
        assertEquals("dungeontrain editor editormenus off", EditorQuickMenu.menusFlipCommand(EditorMenusMode.ON));
        assertEquals("dungeontrain editor editormenus on", EditorQuickMenu.menusFlipCommand(EditorMenusMode.OFF));
        assertEquals("dungeontrain editor editormenus off", EditorQuickMenu.menusFlipCommand(EditorMenusMode.AUTO));
        assertEquals("dungeontrain editor editormenus auto", EditorQuickMenu.menusFlipCommand(EditorMenusMode.OFF));
    }
}
