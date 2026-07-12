package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.menu.plot.EditorPlotTeleport;
import games.brennan.dungeontrain.worldgen.TrainPhase;

import java.util.ArrayList;
import java.util.List;

/**
 * Edit one {@link ClientStages.Info Stage}'s gate — the band steppers + dimension toggles that mirror
 * the per-template gate editor in {@link EditorMenuScreen}, but pointed at the Stage instead of a
 * template (so the change retunes every linked template at once). Reached from
 * {@link StagesListScreen}. Live labels read from {@link ClientStages} (server-pushed in the
 * floating type-menu snapshot). Includes a Delete row.
 */
public final class StageEditScreen implements MenuScreen {

    private static final String[] PHASE_TOKENS = {"overworld", "nether", "void", "end"};
    private static final String[] PHASE_LABELS = {"Overworld", "Nether", "Void", "End"};

    private final String stageId;

    public StageEditScreen(String stageId) {
        this.stageId = stageId == null ? "" : stageId;
    }

    @Override
    public String title() {
        return "Stage: " + stageId;
    }

    @Override
    public List<CommandMenuEntry> entries() {
        List<CommandMenuEntry> out = new ArrayList<>();
        ClientStages.Info s = ClientStages.byId(stageId);
        int minLevel = s == null ? 0 : s.minLevel();
        int maxLevel = s == null ? -1 : s.maxLevel();
        int phaseMask = s == null ? TrainPhase.ALL_MASK : s.phaseMask();

        // Min / Max Diff-Level steppers — [-] / value (typeable) / [+].
        out.add(levelTriple("minlevel", "Min Lv (" + minLevel + ")", "0-1000"));
        out.add(levelTriple("maxlevel", "Max Lv (" + (maxLevel < 0 ? "all" : Integer.toString(maxLevel)) + ")", "-1..1000"));

        // Dimension toggles — plain click flips one, shift-click "toggle all but that one".
        for (int i = 0; i < PHASE_TOKENS.length; i++) {
            boolean on = (phaseMask & (1 << i)) != 0;
            out.add(new CommandMenuEntry.Toggle(
                PHASE_LABELS[i], on,
                EditorPlotTeleport.stagePhaseCommandFor(stageId, PHASE_TOKENS[i], "on"),
                EditorPlotTeleport.stagePhaseCommandFor(stageId, PHASE_TOKENS[i], "off"),
                true,
                EditorPlotTeleport.stagePhaseCommandFor(stageId, PHASE_TOKENS[i], "others")));
        }

        out.add(new CommandMenuEntry.DrillIn("Delete Stage",
            new ConfirmScreen("Delete stage '" + stageId + "'? Linked templates revert to their inline gate.",
                "dungeontrain editor stage delete " + stageId)));
        out.add(new CommandMenuEntry.Back("< Back"));
        return out;
    }

    private CommandMenuEntry levelTriple(String sub, String label, String hint) {
        String prefix = "dungeontrain editor stage " + sub + " " + stageId;
        CommandMenuEntry minus = new CommandMenuEntry.Stay("-", EditorPlotTeleport.stageLevelCommandFor(stageId, sub, "dec"));
        CommandMenuEntry middle = new CommandMenuEntry.TypeArg(label, hint, prefix);
        CommandMenuEntry plus = new CommandMenuEntry.Stay("+", EditorPlotTeleport.stageLevelCommandFor(stageId, sub, "inc"));
        return new CommandMenuEntry.Triple(minus, middle, plus, 0.10, 0.90);
    }
}
