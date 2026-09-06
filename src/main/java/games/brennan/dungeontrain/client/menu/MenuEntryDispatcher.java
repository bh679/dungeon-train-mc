package games.brennan.dungeontrain.client.menu;

/**
 * Routes a clicked {@link CommandMenuEntry} to what it does — the one dispatch table every
 * menu surface shares.
 *
 * <p>Extracted from {@link CommandMenuState} so the inventory-style editor screen, which hosts
 * its own modal stack of {@link MenuScreen}s, dispatches rows exactly the way the world-space and
 * screen-space panels do. The side effects (running a command, closing, drilling in, opening a
 * typing field) go through a {@link Host}, which is what makes the routing itself testable without
 * a client.</p>
 */
public final class MenuEntryDispatcher {

    /** What a menu surface must be able to do for a row to act. */
    public interface Host {
        /** Run a command and close the surface — a {@link CommandMenuEntry.Run}. */
        void runAndClose(String command);

        /** Run a command and stay open — a {@link CommandMenuEntry.Stay} or Toggle. */
        void runAndStay(String command);

        void drillIn(MenuScreen target);

        void goBack();

        void beginTyping(String argName, String commandPrefix, String commandSuffix, String initialBuffer);
    }

    private MenuEntryDispatcher() {}

    /**
     * Dispatch {@code entry}; multi-cell rows recurse into the cell at {@code subIdx}.
     *
     * @param shiftDown whether Shift is held — a Toggle with an "others" command uses it for
     *                  "toggle all but this one", matching the world-space dimension menus
     */
    public static void dispatch(CommandMenuEntry entry, int subIdx, Host host, boolean shiftDown) {
        if (entry == null) return;
        if (entry instanceof CommandMenuEntry.Run run) {
            host.runAndClose(run.command());
        } else if (entry instanceof CommandMenuEntry.Stay stay) {
            host.runAndStay(stay.command());
        } else if (entry instanceof CommandMenuEntry.SaveAction save) {
            // Already-saved rows are no-ops: drawn greyed and filtered by the hit-test, but
            // checked again here so a stray click cannot re-run them.
            if (save.saved()) return;
            save.onClick().run();
        } else if (entry instanceof CommandMenuEntry.Label) {
            // Non-clickable; the hit-test should never let a click through.
        } else if (entry instanceof CommandMenuEntry.ClientAction ca) {
            ca.action().run();
        } else if (entry instanceof CommandMenuEntry.DrillIn drill) {
            host.drillIn(drill.target());
        } else if (entry instanceof CommandMenuEntry.Back) {
            host.goBack();
        } else if (entry instanceof CommandMenuEntry.TypeArg type) {
            host.beginTyping(type.argName(), type.commandPrefix(), type.commandSuffix(), type.initialBuffer());
        } else if (entry instanceof CommandMenuEntry.Toggle toggle) {
            String cmd;
            if (toggle.cmdToToggleOthers() != null && shiftDown) {
                cmd = toggle.cmdToToggleOthers();
            } else {
                cmd = toggle.state() ? toggle.cmdToTurnOff() : toggle.cmdToTurnOn();
            }
            host.runAndStay(cmd);
        } else if (entry instanceof CommandMenuEntry.Split split) {
            dispatch(subIdx == 1 ? split.rightEntry() : split.leftEntry(), 0, host, shiftDown);
        } else if (entry instanceof CommandMenuEntry.Triple triple) {
            CommandMenuEntry target = switch (subIdx) {
                case 1 -> triple.middleEntry();
                case 2 -> triple.rightEntry();
                default -> triple.leftEntry();
            };
            dispatch(target, 0, host, shiftDown);
        } else if (entry instanceof CommandMenuEntry.Quad quad) {
            CommandMenuEntry target = switch (subIdx) {
                case 1 -> quad.e2();
                case 2 -> quad.e3();
                case 3 -> quad.e4();
                default -> quad.e1();
            };
            dispatch(target, 0, host, shiftDown);
        }
        // Loading — no-op.
    }
}
