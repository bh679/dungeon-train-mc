package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;

import java.util.List;

/**
 * The offset row the toolbar's arrow button opens: one button per direction, each sliding every
 * block of the plot one block that way.
 *
 * <p>Every button runs {@code /dungeontrain editor offset}, so the undo history, the plot guards and
 * the wrap at the edges are the command's — blocks pushed past one side come back in at the other.
 * The command acts on the plot the author stands in, so the buttons are off anywhere else.</p>
 */
public final class OffsetControls {

    /** The toolbar's arrow button: shows and hides the row. */
    public static final String TOGGLE = "offset";

    static final String COMMAND = "dungeontrain editor offset ";

    /** One direction: its icon id, its lang key, and the one-block step it runs. */
    record Direction(String id, String labelKey, int dx, int dy, int dz) {
        String command() {
            return COMMAND + dx + " " + dy + " " + dz;
        }
    }

    /** Left to right in pairs: −X +X, −Y +Y, −Z +Z. */
    static final List<Direction> DIRECTIONS = List.of(
        new Direction("offset_x_neg", EditorScreenLang.OFFSET_X_NEG, -1, 0, 0),
        new Direction("offset_x_pos", EditorScreenLang.OFFSET_X_POS, 1, 0, 0),
        new Direction("offset_y_neg", EditorScreenLang.OFFSET_Y_NEG, 0, -1, 0),
        new Direction("offset_y_pos", EditorScreenLang.OFFSET_Y_POS, 0, 1, 0),
        new Direction("offset_z_neg", EditorScreenLang.OFFSET_Z_NEG, 0, 0, -1),
        new Direction("offset_z_pos", EditorScreenLang.OFFSET_Z_POS, 0, 0, 1));

    private OffsetControls() {}

    /**
     * The toolbar button. Live whenever something is selected, standing in it or not: it only opens
     * the row, and the row says why its buttons are off.
     */
    static EditorScreenActions.Icon toggleIcon(boolean hasSelection) {
        return new EditorScreenActions.Icon(TOGGLE, EditorScreenLang.ICON_OFFSET,
            hasSelection ? new CommandMenuEntry.ClientAction(EditorScreenLang.text(EditorScreenLang.ICON_OFFSET),
                EditorScreenState::toggleOffsetRow) : null,
            EditorScreenLang.DISABLED_STAND_HERE, EditorScreenLang.text(EditorScreenLang.ICON_OFFSET_TIP));
    }

    /**
     * The row's buttons. They stay on the screen after a click — moving a build into place is
     * several presses in a row, watched in the plot behind the menu.
     */
    public static List<EditorScreenActions.Icon> icons(boolean standingHere) {
        return DIRECTIONS.stream().map(d -> new EditorScreenActions.Icon(d.id(), d.labelKey(),
                standingHere ? new CommandMenuEntry.Stay(EditorScreenLang.text(d.labelKey()), d.command()) : null,
                EditorScreenLang.DISABLED_STAND_HERE, EditorScreenLang.text(EditorScreenLang.OFFSET_TIP)))
            .toList();
    }
}
