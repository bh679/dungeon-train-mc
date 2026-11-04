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

/** Each offset button runs the set step its own way, and the row fits under the toolbar. */
@ExtendWith(MenuTestLanguage.class)
final class OffsetControlsTest {

    @Test
    void eachDirectionRunsItsOwnUnitStep() {
        List<EditorScreenActions.Icon> icons = OffsetControls.icons(true, 1);
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
    void theDistanceScalesEveryStep() {
        List<EditorScreenActions.Icon> icons = OffsetControls.icons(true, 5);
        assertEquals("dungeontrain editor offset -5 0 0",
            assertInstanceOf(CommandMenuEntry.Stay.class, icons.get(0).entry()).command());
        assertEquals("dungeontrain editor offset 0 0 5",
            assertInstanceOf(CommandMenuEntry.Stay.class, icons.get(5).entry()).command());
    }

    @Test
    void theDistanceBoxTakesOnlyAStepTheButtonsCanRun() {
        int before = EditorScreenState.offsetDistance();
        try {
            EditorScreenState.setOffsetDistance(8);
            assertEquals(8, EditorScreenState.offsetDistance());
            EditorScreenState.setOffsetDistance(0);
            EditorScreenState.setOffsetDistance(-3);
            EditorScreenState.setOffsetDistance(OffsetControls.MAX_DISTANCE + 1);
            assertEquals(8, EditorScreenState.offsetDistance(), "out-of-range steps are ignored");
        } finally {
            EditorScreenState.setOffsetDistance(before);
        }
    }

    @Test
    void typingIntoTheDistanceBoxSetsItWithoutRunningACommand() {
        int[] got = {0};
        InlineEdit edit = new InlineEdit();
        edit.beginLocal(v -> got[0] = v, "1", new InventoryEditorLayout.Rect(0, 0, 16, 16));
        edit.charTyped('1');
        edit.charTyped('2');
        assertEquals(null, edit.submit());
        assertEquals(12, got[0]);
        assertFalse(edit.active());
    }

    @Test
    void undoAndRedoRefreshTheLiveModel() {
        assertTrue(OffsetControls.movesBlocks("undo"));
        assertTrue(OffsetControls.movesBlocks("redo"));
        assertFalse(OffsetControls.movesBlocks("save"));
    }

    @Test
    void toggleIsOffWithNothingSelected() {
        assertFalse(OffsetControls.toggleIcon(false).enabled());
        assertTrue(OffsetControls.toggleIcon(true).enabled());
    }

    @Test
    void offWhenNotStandingInThePlot() {
        for (EditorScreenActions.Icon icon : OffsetControls.icons(false, 1)) {
            assertFalse(icon.enabled(), icon.id());
            assertEquals(EditorScreenLang.DISABLED_STAND_HERE, icon.disabledKey());
        }
    }

    @Test
    void everyButtonHasItsOwnSprite() {
        assertTrue(EditorIcons.forAction(OffsetControls.TOGGLE) == EditorIcons.OFFSET);
        long distinct = OffsetControls.icons(true, 1).stream()
            .map(i -> EditorIcons.forAction(i.id())).distinct().count();
        assertEquals(6, distinct);
        assertTrue(OffsetControls.icons(true, 1).stream()
            .noneMatch(i -> EditorIcons.forAction(i.id()) == EditorIcons.SAVE), "fell through to the default sprite");
    }

    @Test
    void offsetRowSitsUnderTheEditingRowAtItsButtonSize() {
        for (int[] s : new int[][] {{427, 240}, {640, 360}, {1920, 1080}}) {
            InventoryEditorLayout base = InventoryEditorLayout.of(s[0], s[1]);
            assertEquals(0, base.offsetIcons().h(), "no offset row until it is opened");
            int cell = EditorDetailPane.layoutIcons(11, base.icons().x(), base.icons().w()).cell();

            InventoryEditorLayout l = base.withOffsetRow(cell);
            assertEquals(base.icons().y() + cell + 1, l.offsetIcons().y(), "straight under the buttons as drawn");
            assertEquals(cell, l.offsetIcons().h(), "as tall as the toolbar's buttons");
            assertTrue(l.preview().y() >= l.offsetIcons().bottom(), "above the model");
            assertEquals(base.preview().bottom(), l.preview().bottom(), "the model gives the row its room");
            int[] xs = EditorDetailPane.layoutOffsetIcons(6, l.offsetIcons().x(), cell);
            assertEquals(7, xs.length, "six buttons and the distance box");
            assertTrue(xs[6] + EditorDetailPane.offsetDistanceWidth(cell) <= l.offsetIcons().right(),
                "buttons and distance box fit at " + s[0]);
        }
    }
}
