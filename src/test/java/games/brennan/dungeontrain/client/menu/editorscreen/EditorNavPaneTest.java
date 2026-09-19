package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.MenuTestLanguage;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.client.menu.editorscreen.InventoryEditorLayout.Rect;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Nav tab's arithmetic and its two decisions — which tile, and whether Go here may be pressed. */
@ExtendWith(MenuTestLanguage.class)
final class EditorNavPaneTest {

    private static EditorRosterIndex stamped(String categoryId) {
        return new EditorRosterIndex(List.of(), categoryId, EditorRosterPacket.TrainSize.UNKNOWN);
    }

    @Test
    @DisplayName("one 16:9 tile per mode, stacked, inside the column at every size that matters")
    void tilesFit() {
        for (int[] size : new int[][] {{427, 240}, {640, 360}, {1920, 1080}}) {
            InventoryEditorLayout layout = InventoryEditorLayout.of(size[0], size[1], false);
            Rect area = EditorNavPane.rect(layout);
            List<Rect> tiles = EditorNavPane.tiles(area);
            assertEquals(BuilderMode.values().length, tiles.size(), "one tile per mode, stacked");
            Rect detail = EditorNavPane.detail(layout);
            assertTrue(detail.x() >= area.right(), "detail column sits right of the tiles");
            assertTrue(detail.w() > area.w(), "the preview column is the wide one");
            for (Rect t : tiles) {
                assertTrue(t.x() >= area.x() && t.right() <= area.right(), size[0] + "x" + size[1] + ": " + t + " leaves " + area);
                assertTrue(t.y() >= area.y() && t.bottom() <= area.bottom(), size[0] + "x" + size[1] + ": " + t + " leaves " + area);
                assertEquals(t.w() * 9 / 16, t.h(), "not 16:9: " + t);
            }
            assertEquals(tiles.get(0).x(), tiles.get(1).x(), "a vertical list: every tile in the one column");
            assertTrue(tiles.get(0).bottom() <= tiles.get(1).y());
        }
    }

    @Test
    @DisplayName("the picked tile defaults to where the player is, then the first tile, until one is clicked")
    void selectionDefaults() {
        EditorScreenState.setNavMode(null);
        assertEquals(BuilderMode.TRAIN_DIMENSIONS, EditorNavPane.selected(stamped("portals")));
        assertEquals(BuilderMode.WHOLE_CARRIAGES, EditorNavPane.selected(EditorRosterIndex.EMPTY));
        EditorScreenState.setNavMode(BuilderMode.TRACKS_TUNNELS);
        assertEquals(BuilderMode.TRACKS_TUNNELS, EditorNavPane.selected(stamped("portals")));
        EditorScreenState.setNavMode(null);
    }

    @Test
    @DisplayName("Go here is off for the area already stamped and on for every other")
    void canGo() {
        EditorRosterIndex here = stamped("carriages");
        assertFalse(EditorNavPane.canGo(BuilderMode.TRAIN_OUTSIDE, here));
        assertTrue(EditorNavPane.canGo(BuilderMode.INSIDE_CARRIAGE, here));
        assertTrue(EditorNavPane.canGo(BuilderMode.TRAIN_DIMENSIONS, here));
        // Between worlds nothing is stamped: everywhere is somewhere else.
        assertNull(EditorNavPane.hereMode(EditorRosterIndex.EMPTY));
        assertTrue(EditorNavPane.canGo(BuilderMode.TRAIN_OUTSIDE, EditorRosterIndex.EMPTY));
    }

    @Test
    @DisplayName("Go here runs the in-world category switch by id, not by label")
    void switchCommand() {
        assertEquals("dungeontrain editor portals", EditorNavPane.switchCommand(BuilderMode.TRAIN_DIMENSIONS));
        assertEquals("dungeontrain editor carriages", EditorNavPane.switchCommand(BuilderMode.TRAIN_OUTSIDE));
    }
}
