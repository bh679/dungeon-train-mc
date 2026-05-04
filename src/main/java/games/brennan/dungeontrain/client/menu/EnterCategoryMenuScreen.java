package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;

import java.util.List;
import java.util.Locale;

/**
 * Category selector shown from inside an editor plot. Lets the player
 * jump to a different editor category — Carriages, Tracks, or
 * Architecture — via {@code /dt editor &lt;category&gt;}.
 *
 * <p>For the category the player is currently editing inside, the row
 * drills into a {@link CategoryTemplatesScreen} listing every template in
 * that category — so you can teleport directly to a specific variant
 * without the {@code /dt editor &lt;cat&gt;} clear-and-restamp cycle that
 * dumps you at the first model. Cross-category rows keep the existing
 * one-click "switch + jump to first" behaviour.
 */
public final class EnterCategoryMenuScreen implements MenuScreen {

    @Override public String title() { return "Enter"; }

    @Override public List<CommandMenuEntry> entries() {
        String current = EditorStatusHudOverlay.category().toLowerCase(Locale.ROOT);
        return List.of(
            entryFor("Tracks", "tracks", current),
            entryFor("Carriages", "carriages", current),
            // Contents is a full category — same one-click behaviour as
            // Tracks / Carriages: stamps every contents plot and drops the
            // player at the first one.
            entryFor("Contents", "contents", current),
            new CommandMenuEntry.Back("< Back")
        );
    }

    /**
     * Build the row for one category. If the player is already inside this
     * category, drill into a list of every template so they can pick one to
     * teleport to. Otherwise run the existing category-switch command.
     */
    static CommandMenuEntry entryFor(String label, String catId, String currentCategory) {
        if (catId.equals(currentCategory)) {
            return new CommandMenuEntry.DrillIn(label, new CategoryTemplatesScreen(catId));
        }
        return new CommandMenuEntry.Run(label, "dungeontrain editor " + catId);
    }
}
