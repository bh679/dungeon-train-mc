package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.MenuTestLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each offset button runs a one-block step its own way, and the row fits under the toolbar. */
@ExtendWith(MenuTestLanguage.class)
final class OffsetControlsTest {

    @Test
    void eachDirectionRunsItsOwnUnitStep() {
        List<EditorScreenActions.Icon> icons = OffsetControls.icons(true);
        List<String> expected = List.of(
            "dungeontrain editor offset -1 0 0", "dungeontrain editor offset 1 0 0",
            "dungeontrain editor offset 0 -1 0", "dungeontrain editor offset 0 1 0",
            "dungeontrain editor offset 0 0 -1", "dungeontrain editor offset 0 0 1");
        assertEquals(expected.size(), icons.size());
        for (int i = 0; i < icons.size(); i++) {
            // Stay, not Run: moving a build into place is several presses with the menu up.
            CommandMenuEntry.Stay stay = assertInstanceOf(CommandMenuEntry.Stay.class, icons.get(i).entry());
            assertEquals(expected.get(i), stay.command());
        }
    }

    @Test
    void toggleIsOffWithNothingSelected() {
        assertFalse(OffsetControls.toggleIcon(false).enabled());
        assertTrue(OffsetControls.toggleIcon(true).enabled());
    }

    @Test
    void offWhenNotStandingInThePlot() {
        for (EditorScreenActions.Icon icon : OffsetControls.icons(false)) {
            assertFalse(icon.enabled(), icon.id());
            assertEquals(EditorScreenLang.DISABLED_STAND_HERE, icon.disabledKey());
        }
    }

    @Test
    void everyButtonHasItsOwnSprite() {
        assertTrue(EditorIcons.forAction(OffsetControls.TOGGLE) == EditorIcons.OFFSET);
        long distinct = OffsetControls.icons(true).stream()
            .map(i -> EditorIcons.forAction(i.id())).distinct().count();
        assertEquals(6, distinct);
        assertTrue(OffsetControls.icons(true).stream()
            .noneMatch(i -> EditorIcons.forAction(i.id()) == EditorIcons.SAVE), "fell through to the default sprite");
    }

    @Test
    void offsetRowSitsUnderTheEditingRowAndAboveTheModel() {
        for (int[] s : new int[][] {{427, 240}, {640, 360}, {1920, 1080}}) {
            InventoryEditorLayout base = InventoryEditorLayout.of(s[0], s[1]);
            assertEquals(0, base.offsetIcons().h(), "no offset row until it is opened");

            InventoryEditorLayout l = base.withOffsetRow();
            assertTrue(l.offsetIcons().y() >= l.icons().bottom(), "below the editing row");
            assertTrue(l.preview().y() >= l.offsetIcons().bottom(), "above the model");
            assertEquals(base.preview().bottom(), l.preview().bottom(), "the model gives the row its room");
            int[] xs = EditorDetailPane.layoutOffsetIcons(6, l.offsetIcons().x());
            assertTrue(xs[5] + EditorDetailPane.ICON_CELL <= l.offsetIcons().right(), "six buttons fit at " + s[0]);
        }
    }
}
