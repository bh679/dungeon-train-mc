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

/** Each axis button runs its own flip, and the Flip row shares the Offset row's slot. */
@ExtendWith(MenuTestLanguage.class)
final class FlipControlsTest {

    @Test
    void eachAxisRunsItsOwnFlip() {
        List<EditorScreenActions.Icon> icons = FlipControls.icons(true);
        List<String> expected = List.of(
            "dungeontrain editor flip x", "dungeontrain editor flip y", "dungeontrain editor flip z");
        assertEquals(expected.size(), icons.size());
        for (int i = 0; i < icons.size(); i++) {
            // Stay, not Run: the author watches the model and may flip a second axis.
            CommandMenuEntry.Stay stay = assertInstanceOf(CommandMenuEntry.Stay.class, icons.get(i).entry());
            assertEquals(expected.get(i), stay.command());
        }
    }

    @Test
    void toggleIsOffWithNothingSelected() {
        assertFalse(FlipControls.toggleIcon(false).enabled());
        assertTrue(FlipControls.toggleIcon(true).enabled());
    }

    @Test
    void offWhenNotStandingInThePlot() {
        for (EditorScreenActions.Icon icon : FlipControls.icons(false)) {
            assertFalse(icon.enabled(), icon.id());
            assertEquals(EditorScreenLang.DISABLED_STAND_HERE, icon.disabledKey());
        }
    }

    @Test
    void everyButtonHasItsOwnSprite() {
        assertTrue(EditorIcons.forAction(FlipControls.TOGGLE) == EditorIcons.FLIP);
        long distinct = FlipControls.icons(true).stream()
            .map(i -> EditorIcons.forAction(i.id())).distinct().count();
        assertEquals(3, distinct);
        assertTrue(FlipControls.icons(true).stream()
            .noneMatch(i -> EditorIcons.forAction(i.id()) == EditorIcons.SAVE), "fell through to the default sprite");
    }

    @Test
    void openingOneRowClosesTheOther() {
        try {
            EditorScreenState.toggleOffsetRow();
            assertTrue(EditorScreenState.offsetRowOpen());
            EditorScreenState.toggleFlipRow();
            assertTrue(EditorScreenState.flipRowOpen());
            assertFalse(EditorScreenState.offsetRowOpen(), "one slot under the toolbar");
            EditorScreenState.toggleFlipRow();
            assertFalse(EditorScreenState.flipRowOpen());
            assertFalse(EditorScreenState.offsetRowOpen());
        } finally {
            if (EditorScreenState.offsetRowOpen()) EditorScreenState.toggleOffsetRow();
            if (EditorScreenState.flipRowOpen()) EditorScreenState.toggleFlipRow();
        }
    }

    @Test
    void flipRowFitsUnderTheEditingRow() {
        for (int[] s : new int[][] {{427, 240}, {640, 360}, {1920, 1080}}) {
            InventoryEditorLayout base = InventoryEditorLayout.of(s[0], s[1]);
            int cell = EditorDetailPane.layoutIcons(12, base.icons().x(), base.icons().w()).cell();
            InventoryEditorLayout l = base.withOffsetRow(cell);
            int[] xs = EditorDetailPane.layoutFlipIcons(3, l.offsetIcons().x(), cell);
            assertTrue(xs[2] + cell <= l.offsetIcons().right(), "three axes fit at " + s[0]);
        }
    }
}
