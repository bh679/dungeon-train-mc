package games.brennan.dungeontrain.client.menu;

import java.util.List;

/**
 * Category selector shown from inside an editor plot. Lets the player
 * jump to a different editor category — Carriages, Tracks, or
 * Architecture — via {@code /dt editor &lt;category&gt;}.
 */
public final class EnterCategoryMenuScreen implements MenuScreen {

    @Override public String title() { return "Enter"; }

    @Override public List<CommandMenuEntry> entries() {
        return List.of(
            new CommandMenuEntry.Run("Tracks", "dungeontrain editor tracks"),
            new CommandMenuEntry.Run("Carriages", "dungeontrain editor carriages"),
            // Contents is now a full category — same one-click behaviour
            // as Tracks / Carriages: stamps every contents plot and drops
            // the player at the first one.
            new CommandMenuEntry.Run("Contents", "dungeontrain editor contents"),
            new CommandMenuEntry.Back("< Back")
        );
    }
}
