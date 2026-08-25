package games.brennan.dungeontrain.client.menu;

import java.util.List;

/**
 * Root menu shown when the player is NOT in an editor plot. Kept minimal
 * per the spec — one Editor button, a Train submenu, and a Debug Scan
 * shortcut. Editor-only actions (devmode, save, reset, category switch)
 * live in {@link EditorMenuScreen} and are only reachable while in an
 * editor plot.
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
        return List.of(
            new CommandMenuEntry.Run("Editor", "dungeontrain editor"),
            EditorMenuScreen.myBuildsEntry(),
            new CommandMenuEntry.DrillIn("Train", new TrainMenuScreen()),
            new CommandMenuEntry.DrillIn("Options", new OptionsMenuScreen()),
            new CommandMenuEntry.DrillIn("Debug", new DebugMenuScreen())
        );
    }
}
