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

    /**
     * Runs a slash command (no leading /) and closes the menu. The optional
     * {@code highlighted} flag draws a persistent accent tint behind the row
     * — used to mark "this is the option you're currently in" in selection
     * lists (e.g. the active carriage variant in the templates drilldown).
     */
    record Run(String label, String command, boolean highlighted) implements CommandMenuEntry {
        public Run(String label, String command) { this(label, command, false); }
    }

    /**
     * Like {@link Run}, but keeps the menu open after dispatch. Use for
     * incremental adjustments (e.g. nudge buttons) where the player wants
     * to click again while watching the result update live. The menu
     * rebuilds entries each tick, so labels relying on server-pushed state
     * refresh automatically.
     */
    record Stay(String label, String command) implements CommandMenuEntry {}

    /**
     * Like {@link Stay}, but invokes a Java {@link Runnable} directly
     * instead of dispatching a slash command. Use for menu actions that
     * mutate purely client-side state (e.g. cosmetic display preferences
     * stored in client config) where round-tripping through a slash
     * command would be unnecessary plumbing. Leaves the menu open so the
     * next-tick rebuild reflects the new value in row labels.
     */
    record ClientAction(String label, Runnable action) implements CommandMenuEntry {}

    /**
     * Navigates into a nested {@link MenuScreen}. The optional
     * {@code highlighted} flag draws a persistent accent tint behind the row
     * — used to mark "the player is already inside this category" in the
     * category selector.
     */
    record DrillIn(String label, MenuScreen target, boolean highlighted) implements CommandMenuEntry {
        public DrillIn(String label, MenuScreen target) { this(label, target, false); }
    }

    /** Pops one level of the breadcrumb, or closes the menu at the top level. */
    record Back(String label) implements CommandMenuEntry {}

    /**
     * Free-text argument entry. Activating puts the menu in typing mode:
     * keystrokes append to a buffer, Enter submits
     * {@code commandPrefix + " " + buffer} (with {@code commandSuffix}
     * appended when non-empty, e.g. the {@code [source]} arg of
     * {@code editor new <name> <source>}), ESC cancels.
     *
     * <p>{@code initialBuffer} pre-populates the typing field so the user
     * can edit an existing value (e.g. Rename starts with the current name
     * already typed). Empty string means start blank.</p>
     */
    record TypeArg(String label, String argName, String commandPrefix, String commandSuffix, String initialBuffer) implements CommandMenuEntry {
        public TypeArg(String label, String argName, String commandPrefix) {
            this(label, argName, commandPrefix, "", "");
        }
        public TypeArg(String label, String argName, String commandPrefix, String commandSuffix) {
            this(label, argName, commandPrefix, commandSuffix, "");
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

    /**
     * Three buttons side-by-side in one row. {@link #leftFraction} is the
     * boundary between left and middle (panel-relative, 0..1);
     * {@link #middleEnd} is the boundary between middle and right. The
     * raycast produces {@code subIdx} 0 / 1 / 2 for left / middle / right.
     */
    record Triple(CommandMenuEntry leftEntry, CommandMenuEntry middleEntry, CommandMenuEntry rightEntry,
                  double leftFraction, double middleEnd) implements CommandMenuEntry {
        @Override public String label() { return middleEntry.label(); }
    }
}
