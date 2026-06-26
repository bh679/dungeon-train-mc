package games.brennan.dungeontrain.client.menu;

import java.util.ArrayList;
import java.util.List;

/**
 * The Stages management window — a list of every {@link ClientStages.Info Stage} (each drilling into
 * {@link StageEditScreen} to edit its gate or delete it) plus a "+ New Stage…" typing row to create
 * one. Reached from the editor menu's "Stages…" entry and from the world-space Stages panel header.
 * Pairs with the per-template "Stage / Custom" {@link StagePickerScreen} that applies a stage to a
 * template.
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
            out.add(new CommandMenuEntry.DrillIn(
                s.name() + "  [" + ClientStages.gateSummary(s) + "]",
                new StageEditScreen(s.id())));
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
