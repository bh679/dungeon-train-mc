package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.editor.EditorDirtyCheck;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorUnsavedRequestPacket;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Confirmation screen drilled into from {@link EnterCategoryMenuScreen}
 * before {@code /dt editor &lt;targetCategory&gt;} runs. The category
 * switch wipes every plot via
 * {@link games.brennan.dungeontrain.editor.EditorCategory#clearAllPlots},
 * which silently destroys in-world edits not yet saved to disk. This
 * screen surfaces the loss-risk list and lets the player save (or
 * knowingly skip) before the destructive switch.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #entries()} first call → send
 *       {@link EditorUnsavedRequestPacket} to the server, render a
 *       Loading row.</li>
 *   <li>Server replies with
 *       {@link games.brennan.dungeontrain.net.EditorUnsavedListPacket}
 *       which populates {@link EditorStatusHudOverlay#unsavedList()}.</li>
 *   <li>Subsequent rebuilds (every client tick — see
 *       {@link CommandMenuState#onClientTick}) read the list:
 *     <ul>
 *       <li>Empty → bypass: dispatch
 *           {@code dungeontrain editor &lt;targetCategory&gt;} and close
 *           the menu so the clean-state path stays one click.</li>
 *       <li>Non-empty → render one row per dirty entry plus a Continue
 *           row at the bottom (with a spacer above for breathing
 *           room).</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>Per-row Save uses {@link CommandMenuEntry.SaveAction} which dispatches
 * {@code dungeontrain save model &lt;cat&gt; &lt;id&gt; [default]} and
 * marks the row locally saved (greyed out). View is a {@link
 * CommandMenuEntry.Stay} that runs {@code dungeontrain editor view
 * &lt;cat&gt; &lt;id&gt;} — teleports without re-stamping so the user
 * can inspect the dirty geometry in place. Continue's label flips
 * between "Don't save - continue" and "Continue" depending on whether
 * any rows still need saving.
 */
public final class UnsavedCheckScreen implements MenuScreen {

    private final String targetCategory;
    private boolean requestSent = false;
    private boolean bypassDispatched = false;
    /** Model ids the user has already clicked Save on this session. Greys out the row's Save button. */
    private final Set<String> savedThisSession = new HashSet<>();

    public UnsavedCheckScreen(String targetCategory) {
        this.targetCategory = targetCategory;
    }

    @Override public String title() { return "Save before switch?"; }

    @Override public List<CommandMenuEntry> entries() {
        if (!requestSent) {
            // Clear any stale list from a previous drill-in so we don't
            // momentarily render rows from the last category we checked.
            EditorStatusHudOverlay.clearUnsavedList();
            DungeonTrainNet.sendToServer(new EditorUnsavedRequestPacket());
            requestSent = true;
            return List.of(new CommandMenuEntry.Loading("Checking..."));
        }

        List<EditorDirtyCheck.DirtyEntry> rows = EditorStatusHudOverlay.unsavedList();
        if (rows == null) {
            // Server hasn't replied yet — keep showing Loading.
            return List.of(new CommandMenuEntry.Loading("Checking..."));
        }

        if (rows.isEmpty()) {
            // Clean state: bypass the screen entirely. Dispatching from inside
            // entries() is safe — CommandRunner posts to the chat queue, then
            // we close the menu before the next render. The bypass guard
            // prevents the dispatch firing twice on the same screen.
            if (!bypassDispatched) {
                bypassDispatched = true;
                CommandRunner.run("dungeontrain editor " + targetCategory);
                CommandMenuState.close();
            }
            return List.of(new CommandMenuEntry.Loading("Entering..."));
        }

        return buildDirtyEntries(rows);
    }

    private List<CommandMenuEntry> buildDirtyEntries(List<EditorDirtyCheck.DirtyEntry> rows) {
        List<CommandMenuEntry> out = new ArrayList<>(rows.size() + 3);
        boolean devmode = EditorStatusHudOverlay.isDevModeOn();
        boolean anyOutstanding = false;

        for (EditorDirtyCheck.DirtyEntry r : rows) {
            boolean saved = savedThisSession.contains(r.modelId());
            if (!saved) anyOutstanding = true;

            String saveCmd = "dungeontrain save model " + r.categoryId() + " " + r.modelId()
                + (devmode ? " default" : "");
            // Track-side save methods (TrackEditor / PillarEditor / TunnelEditor)
            // resolve the plot from the player's position, so saving a row whose
            // plot the player isn't standing in errors out. Chain a teleport
            // first; the save command on the next packet sees the new position
            // and captures the right plot. Carriages and contents resolve by id
            // directly and don't need the teleport.
            boolean needsTeleport = "tracks".equals(r.categoryId());
            String viewCmd = "dungeontrain editor view " + r.categoryId() + " " + r.modelId();

            // The Save closure captures the model id locally so the next-tick
            // rebuild picks up the grey state without needing a server round-trip.
            // The server's re-broadcast of the dirty list reconciles after the
            // save completes; if the optimistic grey was wrong (save failed)
            // the row reappears with savedThisSession out of sync, but the
            // worst outcome is the user clicking Save again — idempotent.
            final String modelId = r.modelId();
            CommandMenuEntry.SaveAction save = new CommandMenuEntry.SaveAction(
                "Save",
                () -> {
                    savedThisSession.add(modelId);
                    if (needsTeleport) {
                        CommandRunner.run(viewCmd);
                    }
                    CommandRunner.run(saveCmd);
                },
                saved
            );
            // Name cell teleports the player into the plot — quicker access
            // than the View column. Stay so the menu follows.
            CommandMenuEntry.Stay nameTeleport = new CommandMenuEntry.Stay(
                displayLabel(r),
                "dungeontrain editor view " + r.categoryId() + " " + r.modelId()
            );
            // View cell drills into a per-template list of changed positions
            // so the player can audit what's flagged dirty before deciding
            // whether to save or discard.
            CommandMenuEntry.DrillIn view = new CommandMenuEntry.DrillIn(
                "View",
                new ChangesListScreen(r.categoryId(), r.modelId(), r.displayName())
            );
            // Triple layout: name (50%) | Save (25%) | View (25%).
            out.add(new CommandMenuEntry.Triple(nameTeleport, save, view, 0.50, 0.75));
        }

        // Spacer before Continue per the spec ("extra space above").
        out.add(new CommandMenuEntry.Label(""));

        String continueLabel = anyOutstanding ? "Don't save - continue" : "Continue";
        out.add(new CommandMenuEntry.Run(continueLabel,
            "dungeontrain editor " + targetCategory));
        out.add(new CommandMenuEntry.Back("< Back"));
        return out;
    }

    /**
     * Format the row label. Includes category prefix when the category is
     * different from the target — switching to Tracks while a Carriage row
     * is dirty is the most common "what is this?" case the user will hit,
     * so disambiguating helps.
     */
    private String displayLabel(EditorDirtyCheck.DirtyEntry r) {
        StringBuilder sb = new StringBuilder();
        if (!r.categoryId().equals(targetCategory)) {
            sb.append(r.categoryId()).append(" / ");
        }
        sb.append(r.displayName());
        if (r.isUnpromoted() && !r.isUnsaved()) {
            // DevMode-only: row is saved-to-config but not promoted to source.
            // Mark it so the user knows the Save button will promote, not just save.
            sb.append(" (unpromoted)");
        }
        return sb.toString();
    }
}
