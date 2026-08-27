package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.PortalTestSessionState;

import java.util.ArrayList;
import java.util.List;

/**
 * Root menu shown when the player is NOT in an editor plot. Kept minimal
 * per the spec — one Editor button, a Train submenu, and a Debug Scan
 * shortcut. The debug drill-in is labelled <b>Test Live</b>: what is behind
 * it is testing against a running world, and "Debug" undersold that. Editor-only actions (devmode, save, reset, category switch)
 * live in {@link EditorMenuScreen} and are only reachable while in an
 * editor plot.
 *
 * <p>While the player is standing in a test dimensional carriage
 * ({@link PortalTestSessionState}) a Back row is prepended — see {@code entries()}.</p>
 *
 * <p><b>My Builds</b> is the exception, and deliberately so: what it lists is everything this
 * player has uploaded, which is not a fact about the plot they happen to be standing in. Requiring
 * them to walk into one to reach their own profile is the gap this row closes — see
 * {@link EditorMenuScreen#myBuildsEntry()}, whose definition this shares. The same row is also on
 * the pause menu ({@code PauseMenuLayoutHandler}), which is the way in for a player who is not in
 * the editor at all.</p>
 */
public final class MainMenuScreen implements MenuScreen {

    @Override public String title() { return "Dungeon Train"; }

    @Override public List<CommandMenuEntry> entries() {
        // The way out of a test dimensional carriage, and only while standing in one. It has to live
        // here rather than on the editor panel: a test structure is stamped under the world, not in
        // an editor plot, so EditorStatusHudOverlay is inactive and that panel is not on screen at
        // all. First row, because it is the only thing anybody down there wants.
        if (PortalTestSessionState.active()) {
            List<CommandMenuEntry> out = new ArrayList<>(entriesBase());
            out.add(0, new CommandMenuEntry.Run(
                "< Back from " + labelFor(PortalTestSessionState.roomName()),
                "dungeontrain portal test back"));
            return List.copyOf(out);
        }
        return entriesBase();
    }

    /** The room's name for the Back row, or a plain fallback if the client was told nothing. */
    private static String labelFor(String roomName) {
        return roomName == null || roomName.isEmpty() ? "the Carriage" : "'" + roomName + "'";
    }

    private static List<CommandMenuEntry> entriesBase() {
        return List.of(
            new CommandMenuEntry.Run("Editor", "dungeontrain editor"),
            EditorMenuScreen.myBuildsEntry(),
            new CommandMenuEntry.DrillIn("Train", new TrainMenuScreen()),
            new CommandMenuEntry.DrillIn("Options", new OptionsMenuScreen()),
            new CommandMenuEntry.DrillIn("Test Live", new DebugMenuScreen())
        );
    }
}
