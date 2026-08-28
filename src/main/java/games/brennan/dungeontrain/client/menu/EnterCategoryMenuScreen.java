package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;

import java.util.List;
import java.util.Locale;

/**
 * Category selector shown from inside an editor plot. Lets the player
 * jump to a different editor category — Carriages, Tracks, or
 * Contents — via {@code /dt editor &lt;category&gt;}.
 *
 * <p>Two distinct drill-ins depending on the row's relation to the
 * player's current category:
 *
 * <ul>
 *   <li><b>Same category</b> — drills into {@link CategoryTemplatesScreen}
 *       listing every template in that category so the player can
 *       teleport directly to a specific variant without the destructive
 *       {@code /dt editor &lt;cat&gt;} clear-and-restamp cycle.</li>
 *   <li><b>Different category</b> — dispatches
 *       {@code /dt editor &lt;category&gt;} straight away.
 *
 *       <p><b>The unsaved-check confirmation is currently bypassed.</b> This row used to drill
 *       into {@link UnsavedCheckScreen}, which asked the server for dirty plots and offered a
 *       per-row Save before the destructive switch. That check reported plots the author had
 *       never touched — its baseline diverges from the live world at part-template variant cells
 *       right after the stamp — so the screen listed most of the roster on every switch and the
 *       real warnings were lost in the noise. Bypassing it means an unsaved edit is destroyed
 *       silently by {@code EditorCategory.clearAllPlots}; that is the accepted trade until the
 *       dirty check is fixed. {@link UnsavedCheckScreen} is kept intact so re-enabling is
 *       restoring the drill-in below.</p></li>
 * </ul>
 */
public final class EnterCategoryMenuScreen implements MenuScreen {

    @Override public String title() { return "Enter"; }

    @Override public List<CommandMenuEntry> entries() {
        String current = EditorStatusHudOverlay.category().toLowerCase(Locale.ROOT);
        return List.of(
            entryFor("Tracks", "tracks", current),
            entryFor("Carriages", "carriages", current),
            entryFor("Contents", "contents", current),
            entryFor("Portals", "portals", current),
            new CommandMenuEntry.Back("< Back")
        );
    }

    /**
     * Build the row for one category:
     * <ul>
     *   <li>If the player is already inside this category, drill into the
     *       template-picker for in-category teleports.</li>
     *   <li>Otherwise run the category switch directly — the same command
     *       {@link UnsavedCheckScreen}'s own Continue row dispatched. See the
     *       class javadoc for why the confirmation is bypassed.</li>
     * </ul>
     */
    static CommandMenuEntry entryFor(String label, String catId, String currentCategory) {
        if (catId.equals(currentCategory)) {
            return new CommandMenuEntry.DrillIn(label, new CategoryTemplatesScreen(catId), true);
        }
        return new CommandMenuEntry.Run(label, "dungeontrain editor " + catId);
    }
}
