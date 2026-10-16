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

        /**
         * Type a {@link CommandMenuEntry.Number}'s value — the cmd-click gesture. Defaults to the
         * number pad over whatever is showing, which returns there when it closes.
         */
        default void typeNumber(games.brennan.dungeontrain.client.menu.number.NumberField field) {
            games.brennan.dungeontrain.client.menu.number.NumberPad.overCurrent().type(field);
        }
    }

    private MenuEntryDispatcher() {}

    /**
     * Whether the cell at {@code subIdx} of {@code entry} is a number — the only kind of cell a
     * right-click acts on (as its step-down) on a surface where right-click otherwise does nothing.
     */
    public static boolean isNumber(CommandMenuEntry entry, int subIdx) {
        CommandMenuEntry cell = cellAt(entry, subIdx);
        return cell instanceof CommandMenuEntry.Number || cell instanceof CommandMenuEntry.ClientNumber;
    }

    /** The single-cell entry a multi-cell row resolves to at {@code subIdx}. */
    private static CommandMenuEntry cellAt(CommandMenuEntry entry, int subIdx) {
        if (entry instanceof CommandMenuEntry.Split split) return subIdx == 1 ? split.rightEntry() : split.leftEntry();
        if (entry instanceof CommandMenuEntry.Triple t) {
            return switch (subIdx) { case 1 -> t.middleEntry(); case 2 -> t.rightEntry(); default -> t.leftEntry(); };
        }
        if (entry instanceof CommandMenuEntry.Quad q) {
            return switch (subIdx) { case 1 -> q.e2(); case 2 -> q.e3(); case 3 -> q.e4(); default -> q.e1(); };
        }
        if (entry instanceof CommandMenuEntry.Cells c) {
            return c.cells().get(Math.max(0, Math.min(subIdx, c.cells().size() - 1)));
        }
        return entry;
    }

    /**
     * Dispatch {@code entry}; multi-cell rows recurse into the cell at {@code subIdx}.
     *
     * @param shiftDown whether Shift is held — a Toggle with an "others" command uses it for
     *                  "toggle all but this one", matching the world-space dimension menus
     */
    public static void dispatch(CommandMenuEntry entry, int subIdx, Host host, boolean shiftDown) {
        dispatch(entry, subIdx, host, shiftDown, 0, false);
    }

    /**
     * Dispatch with the full click: {@code button} (0 left, 1 right) and whether cmd was held. Only
     * number rows read them — click steps up, shift- or right-click steps down, cmd-click types;
     * every other row acts the same on either button, as it always has.
     */
    public static void dispatch(CommandMenuEntry entry, int subIdx, Host host, boolean shiftDown,
                                int button, boolean cmdDown) {
        if (entry == null) return;
        if (entry instanceof CommandMenuEntry.Number number) {
            games.brennan.dungeontrain.client.menu.number.NumberStepper.click(
                number.field(host::runAndStay), button, shiftDown, cmdDown, host::typeNumber);
        } else if (entry instanceof CommandMenuEntry.ClientNumber number) {
            games.brennan.dungeontrain.client.menu.number.NumberStepper.click(
                number.field(), button, shiftDown, cmdDown, host::typeNumber);
        } else if (entry instanceof CommandMenuEntry.Run run) {
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
            dispatch(subIdx == 1 ? split.rightEntry() : split.leftEntry(), 0, host, shiftDown, button, cmdDown);
        } else if (entry instanceof CommandMenuEntry.Triple triple) {
            CommandMenuEntry target = switch (subIdx) {
                case 1 -> triple.middleEntry();
                case 2 -> triple.rightEntry();
                default -> triple.leftEntry();
            };
            dispatch(target, 0, host, shiftDown, button, cmdDown);
        } else if (entry instanceof CommandMenuEntry.Quad quad) {
            CommandMenuEntry target = switch (subIdx) {
                case 1 -> quad.e2();
                case 2 -> quad.e3();
                case 3 -> quad.e4();
                default -> quad.e1();
            };
            dispatch(target, 0, host, shiftDown, button, cmdDown);
        } else if (entry instanceof CommandMenuEntry.Cells cells) {
            int i = Math.max(0, Math.min(subIdx, cells.cells().size() - 1));
            dispatch(cells.cells().get(i), 0, host, shiftDown, button, cmdDown);
        }
        // Loading — no-op.
    }
}
