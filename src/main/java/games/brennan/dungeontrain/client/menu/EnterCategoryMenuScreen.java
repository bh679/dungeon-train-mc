package games.brennan.dungeontrain.client.menu;

import java.util.List;

/**
 * Category selector shown from inside an editor plot. Lets the player
 * jump to a different editor category — Carriages, Tracks, or
 * Contents — via {@code /dt editor &lt;category&gt;}.
 *
 * <p>Each row drills into {@link UnsavedCheckScreen} which queries the
 * server for dirty plots before dispatching the destructive switch. If
 * the world has no unsaved changes the check screen auto-dispatches and
 * closes, preserving the original one-click flow; otherwise it surfaces
 * a per-row Save / View list so the player doesn't lose work.
 */
public final class EnterCategoryMenuScreen implements MenuScreen {

    @Override public String title() { return "Enter"; }

    @Override public List<CommandMenuEntry> entries() {
        return List.of(
            new CommandMenuEntry.DrillIn("Tracks", new UnsavedCheckScreen("tracks")),
            new CommandMenuEntry.DrillIn("Carriages", new UnsavedCheckScreen("carriages")),
            new CommandMenuEntry.DrillIn("Contents", new UnsavedCheckScreen("contents")),
            new CommandMenuEntry.Back("< Back")
        );
    }
}
