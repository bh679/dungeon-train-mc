package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;

import java.util.List;

/**
 * The axis row the toolbar's Flip button opens: one button per axis, each mirroring every block of
 * the plot across it.
 *
 * <p>Every button runs {@code /dungeontrain editor flip}, so the undo history and the plot guards are
 * the command's. Like the offset row, the command acts on the plot the author stands in, so the
 * buttons are off anywhere else. The two rows share one slot under the toolbar.</p>
 */
public final class FlipControls {

    /** The toolbar's Flip button: shows and hides the axis row. */
    public static final String TOGGLE = "flip";

    static final String COMMAND = "dungeontrain editor flip ";

    /** One axis: its icon id, its lang key, and the command's axis word. */
    record Axis(String id, String labelKey, String axis) {
        String command() {
            return COMMAND + axis;
        }
    }

    /** Left to right: X, Y, Z. */
    static final List<Axis> AXES = List.of(
        new Axis("flip_x", EditorScreenLang.FLIP_X, "x"),
        new Axis("flip_y", EditorScreenLang.FLIP_Y, "y"),
        new Axis("flip_z", EditorScreenLang.FLIP_Z, "z"));

    private FlipControls() {}

    /**
     * The toolbar button. Live whenever something is selected, standing in it or not: it only opens
     * the row, and the row says why its buttons are off.
     */
    static EditorScreenActions.Icon toggleIcon(boolean hasSelection) {
        return new EditorScreenActions.Icon(TOGGLE, EditorScreenLang.ICON_FLIP,
            hasSelection ? new CommandMenuEntry.ClientAction(EditorScreenLang.text(EditorScreenLang.ICON_FLIP),
                EditorScreenState::toggleFlipRow) : null,
            EditorScreenLang.DISABLED_STAND_HERE, EditorScreenLang.text(EditorScreenLang.ICON_FLIP_TIP));
    }

    /** The row's buttons. They stay on the screen after a click, so a second axis is one press away. */
    public static List<EditorScreenActions.Icon> icons(boolean standingHere) {
        return AXES.stream().map(a -> new EditorScreenActions.Icon(a.id(), a.labelKey(),
                standingHere ? new CommandMenuEntry.Stay(EditorScreenLang.text(a.labelKey()), a.command()) : null,
                EditorScreenLang.DISABLED_STAND_HERE, EditorScreenLang.text(EditorScreenLang.FLIP_TIP)))
            .toList();
    }
}
