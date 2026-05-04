package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.editor.EditorDirtyCheck;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorChangesRequestPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-template "what changed?" drilldown reached from the View column
 * of {@link UnsavedCheckScreen}. Sends an
 * {@link EditorChangesRequestPacket} on first render and waits for the
 * server to respond with a list of (relative position, expected, live)
 * triples that the player can scan before deciding to save or discard.
 *
 * <p>Truncates to {@link #MAX_ROWS} entries to keep the panel small
 * — most realistic edits touch a handful of blocks; templates with
 * dozens of differences are usually downstream of a parts-overlay
 * change rather than direct edits and the truncated tail is rarely
 * informative.</p>
 */
public final class ChangesListScreen implements MenuScreen {

    /** Soft cap on rendered rows so the panel doesn't become a wall of text. */
    private static final int MAX_ROWS = 12;

    private final String categoryId;
    private final String modelId;
    private final String displayName;
    private boolean requestSent = false;

    public ChangesListScreen(String categoryId, String modelId, String displayName) {
        this.categoryId = categoryId;
        this.modelId = modelId;
        this.displayName = displayName;
    }

    @Override public String title() { return "Changes: " + displayName; }

    @Override public List<CommandMenuEntry> entries() {
        if (!requestSent) {
            // Drop any stale changes so the loading row renders cleanly even
            // if a previous drill-in left a different template's data behind.
            EditorStatusHudOverlay.clearChangesList();
            DungeonTrainNet.sendToServer(new EditorChangesRequestPacket(categoryId, modelId));
            requestSent = true;
            return List.of(new CommandMenuEntry.Loading("Computing diff..."));
        }

        List<EditorDirtyCheck.DiffEntry> changes =
            EditorStatusHudOverlay.changesListFor(categoryId, modelId);
        if (changes == null) {
            return List.of(new CommandMenuEntry.Loading("Computing diff..."));
        }

        if (changes.isEmpty()) {
            return List.of(
                new CommandMenuEntry.Label("No differences"),
                new CommandMenuEntry.Back("< Back")
            );
        }

        List<CommandMenuEntry> out = new ArrayList<>(Math.min(changes.size(), MAX_ROWS) + 2);
        out.add(new CommandMenuEntry.Label(changes.size() + " change" + (changes.size() == 1 ? "" : "s")));
        int shown = Math.min(changes.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            EditorDirtyCheck.DiffEntry d = changes.get(i);
            out.add(new CommandMenuEntry.Label(formatRow(d)));
        }
        if (changes.size() > MAX_ROWS) {
            out.add(new CommandMenuEntry.Label("... " + (changes.size() - MAX_ROWS) + " more"));
        }
        out.add(new CommandMenuEntry.Back("< Back"));
        return out;
    }

    /**
     * One-line row in the form {@code (x,y,z) expected → live} — local
     * coordinates relative to the plot origin so the position lines up
     * with what the author sees on the in-world overlay.
     */
    private static String formatRow(EditorDirtyCheck.DiffEntry d) {
        return "(" + d.localPos().getX() + "," + d.localPos().getY() + "," + d.localPos().getZ() + ") "
            + d.expectedDescription() + " > " + d.liveDescription();
    }
}
