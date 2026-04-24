package games.brennan.dungeontrain.client.menu;

/**
 * One rendered row in the worldspace command menu. Most rows contain a
 * single full-width action; {@link Split} rows contain two sub-entries
 * side-by-side — the split is nested so each side can independently be a
 * Run, DrillIn, TypeArg, etc.
 *
 * <p>The raycast produces a {@code (rowIdx, subIdx)} pair where
 * {@code subIdx == 0} is the primary/left side and {@code subIdx == 1} is
 * the right side of a Split row. Non-split rows ignore {@code subIdx}.
 */
public sealed interface CommandMenuEntry {

    /** Text shown on the primary / only button of this row. */
    String label();

    /** Runs a slash command (no leading /) and closes the menu. */
    record Run(String label, String command) implements CommandMenuEntry {}

    /** Navigates into a nested {@link MenuScreen}. */
    record DrillIn(String label, MenuScreen target) implements CommandMenuEntry {}

    /** Pops one level of the breadcrumb, or closes the menu at the top level. */
    record Back(String label) implements CommandMenuEntry {}

    /**
     * Free-text argument entry. Activating puts the menu in typing mode:
     * keystrokes append to a buffer, Enter submits
     * {@code commandPrefix + " " + buffer} (with {@code commandSuffix}
     * appended when non-empty, e.g. the {@code [source]} arg of
     * {@code editor new <name> <source>}), ESC cancels.
     */
    record TypeArg(String label, String argName, String commandPrefix, String commandSuffix) implements CommandMenuEntry {
        public TypeArg(String label, String argName, String commandPrefix) {
            this(label, argName, commandPrefix, "");
        }
    }

    /** Transient placeholder while something asynchronous is pending. */
    record Loading(String label) implements CommandMenuEntry {}

    /**
     * On/off state entry. The label is augmented with {@code [ON]} /
     * {@code [OFF]} at render time based on {@link #state}. Clicking runs
     * either {@link #cmdToTurnOn} (when currently off) or
     * {@link #cmdToTurnOff} (when currently on). Does NOT close the menu —
     * the server ack updates the displayed state on the next rebuild.
     */
    record Toggle(String label, boolean state, String cmdToTurnOn, String cmdToTurnOff) implements CommandMenuEntry {}

    /**
     * Two buttons side-by-side in one row. Each side is its own
     * {@link CommandMenuEntry}, so the two halves can independently be Run,
     * DrillIn, TypeArg, etc. The left side occupies
     * {@link #leftFraction} of the row width; the right fills the
     * remainder.
     */
    record Split(CommandMenuEntry leftEntry, CommandMenuEntry rightEntry, double leftFraction) implements CommandMenuEntry {
        @Override public String label() { return leftEntry.label(); }
    }
}
