package games.brennan.dungeontrain.client.menu;

import java.util.ArrayList;
import java.util.List;

/**
 * The Stages management window — a list of every {@link ClientStages.Info Stage} plus a
 * "+ New Stage…" typing row to create one. Reached from the editor menu's "Stages…" entry and from
 * the world-space Stages panel header. Pairs with the per-template "Stage / Custom"
 * {@link StagePickerScreen} that applies a stage to a template.
 *
 * <p>Each stage row is a {@link CommandMenuEntry.Split}: the wide left cell <b>selects</b> the stage
 * as the focused per-stage carriage preview (re-clicking the selected row deselects it, the toggle
 * handled server-side by {@code editor stage select}), highlighted amber while it is the selection;
 * the narrow right cell still drills into {@link StageEditScreen} to edit its gate or delete it. The
 * highlight tracks {@link ClientStages#selectedStageId()}, mirrored from the server in the same
 * type-menu snapshot this list reads.</p>
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
            // Left: toggle this stage as the focused preview (re-click deselects, server-side).
            // Right: edit its gate / delete. Highlight the left cell while it is the selection.
            CommandMenuEntry select = new CommandMenuEntry.Stay(
                s.name() + "  [" + ClientStages.gateSummary(s) + "]",
                "dungeontrain editor stage select " + s.id(),
                ClientStages.isSelected(s.id()));
            CommandMenuEntry edit = new CommandMenuEntry.DrillIn("edit", new StageEditScreen(s.id()), false);
            out.add(new CommandMenuEntry.Split(select, edit, 0.80));
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
