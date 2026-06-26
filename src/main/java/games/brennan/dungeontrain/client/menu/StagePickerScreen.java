package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.menu.plot.EditorPlotTeleport;

import java.util.ArrayList;
import java.util.List;

/**
 * The "Stage / Custom" picker — the popup behind the {@code Stage ▾} affordance at every gate-edit
 * site. Lists {@code Custom} (detach to a hand-set inline gate, exactly as today) plus every existing
 * {@link ClientStages.Info Stage}; clicking one links the target template to it (or detaches) via
 * {@link EditorPlotTeleport#stageApplyCommandFor} and closes. The row matching the template's current
 * link is highlighted.
 *
 * <p>Constructed with the target template's {@code (category, modelId, modelName)} — the same tuple
 * the floating type menu and the keyboard editor menu already carry — so the dispatched command lands
 * on the right template regardless of which site opened the picker.</p>
 */
public final class StagePickerScreen implements MenuScreen {

    private final String category;
    private final String modelId;
    private final String modelName;
    private final String currentStageId;

    public StagePickerScreen(String category, String modelId, String modelName, String currentStageId) {
        this.category = category == null ? "" : category;
        this.modelId = modelId == null ? "" : modelId;
        this.modelName = modelName == null ? "" : modelName;
        this.currentStageId = currentStageId == null ? "" : currentStageId;
    }

    @Override
    public String title() {
        return "Select Stage";
    }

    @Override
    public List<CommandMenuEntry> entries() {
        List<CommandMenuEntry> out = new ArrayList<>();
        boolean linked = !currentStageId.isEmpty();

        String customCmd = EditorPlotTeleport.stageApplyCommandFor(category, modelId, modelName, "custom");
        if (customCmd != null) {
            out.add(new CommandMenuEntry.Run("Custom (set by hand)", customCmd, !linked));
        }

        for (ClientStages.Info s : ClientStages.all()) {
            String cmd = EditorPlotTeleport.stageApplyCommandFor(category, modelId, modelName, s.id());
            if (cmd == null) continue;
            String label = s.name() + "  [" + ClientStages.gateSummary(s) + "]";
            out.add(new CommandMenuEntry.Run(label, cmd, s.id().equals(currentStageId)));
        }

        if (ClientStages.isEmpty()) {
            out.add(new CommandMenuEntry.Label("No stages yet — add one in the Stages window."));
        }
        out.add(new CommandMenuEntry.Back("< Back"));
        return out;
    }
}
