package games.brennan.dungeontrain.client.menu;

import java.util.ArrayList;
import java.util.List;

/**
 * The Stages management window — a list of every {@link ClientStages.Info Stage} plus a
 * "+ New Stage…" typing row to create one. Reached from the editor menu's "Stages…" entry and from
 * the world-space Stages panel header. Pairs with the per-template "Stage / Custom"
 * {@link StagePickerScreen} that applies a stage to a template.
 *
 * <p>Clicking a stage row <b>toggles</b> it as the focused per-stage carriage preview (re-clicking
 * deselects — the toggle is handled server-side by {@code editor stage select}); the row is
 * highlighted while it is the selection, tracked via {@link ClientStages#selectedStageId()}. A
 * stage's gate is edited inline on the world-space Stages panel (≥ / ≤ / phase cells) and deleted
 * there via its remove mode, so this list no longer drills into a separate edit window.</p>
 */
public final class StagesListScreen implements MenuScreen {

    @Override
    public String title() {
        return "Stages";
    }

    @Override
    public List<CommandMenuEntry> entries() {
        List<CommandMenuEntry> out = new ArrayList<>();
        for (ClientStages.Info s : ClientStages.all()) {
            // Whole row toggles this stage as the focused preview (re-click deselects); highlighted
            // while it is the selection. Stay keeps the menu open so the toggle is visible.
            out.add(new CommandMenuEntry.Stay(
                s.name() + "  [" + ClientStages.gateSummary(s) + "]",
                "dungeontrain editor stage select " + s.id(),
                ClientStages.isSelected(s.id())));
        }
        if (ClientStages.isEmpty()) {
            out.add(new CommandMenuEntry.Label("No stages yet."));
        }
        // Create — types an id, Enter dispatches `editor stage new <id>` (id lower-cased server-side).
        out.add(new CommandMenuEntry.TypeArg("+ New Stage", "id", "dungeontrain editor stage new"));
        out.add(new CommandMenuEntry.Back("< Back"));
        return out;
    }
}
