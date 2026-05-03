package games.brennan.dungeontrain.client.menu;

import java.util.List;

/**
 * Root menu shown when the player is NOT in an editor plot. Kept minimal
 * per the spec — one Editor button, a Train submenu, and a Debug Scan
 * shortcut. Editor-only actions (devmode, save, reset, category switch)
 * live in {@link EditorMenuScreen} and are only reachable while in an
 * editor plot.
 */
public final class MainMenuScreen implements MenuScreen {

    @Override public String title() { return "Dungeon Train"; }

    @Override public List<CommandMenuEntry> entries() {
        return List.of(
            new CommandMenuEntry.Run("Editor", "dungeontrain editor"),
            new CommandMenuEntry.DrillIn("Train", new TrainMenuScreen()),
            new CommandMenuEntry.DrillIn("Debug", new DebugMenuScreen())
        );
    }
}
