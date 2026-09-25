package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.menu.plot.EditorPlotTeleport;
import games.brennan.dungeontrain.worldgen.LapBand;

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

    private final String stageId;

    public StageEditScreen(String stageId) {
        this.stageId = stageId == null ? "" : stageId;
    }

    @Override
    public String title() {
        return MenuLang.t("stage.title", stageId);
    }

    @Override
    public List<CommandMenuEntry> entries() {
        List<CommandMenuEntry> out = new ArrayList<>();
        ClientStages.Info s = ClientStages.byId(stageId);
        int minLevel = s == null ? 0 : s.minLevel();
        int maxLevel = s == null ? -1 : s.maxLevel();
        int phaseMask = s == null ? LapBand.ALL_MASK : s.phaseMask();

        // Min / Max Diff-Level steppers — [-] / value (typeable) / [+].
        out.add(levelTriple("minlevel", MenuLang.t("editor.min_level", minLevel), "0-1000"));
        out.add(levelTriple("maxlevel", MenuLang.t("editor.max_level", maxLevel < 0 ? MenuLang.t("editor.max_level_all") : Integer.toString(maxLevel)), "-1..1000"));

        // Band options under their groups; click toggles one, shift-click its whole group.
        out.addAll(BandOptionEntries.of(phaseMask,
            m -> EditorPlotTeleport.stagePhaseCommandFor(stageId, "mask", String.valueOf(m))));

        out.add(new CommandMenuEntry.DrillIn(MenuLang.t("stage.delete"),
            new ConfirmScreen(MenuLang.t("stage.delete_confirm", stageId),
                "dungeontrain editor stage delete " + stageId)));
        out.add(new CommandMenuEntry.Back(MenuLang.t("common.back")));
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
