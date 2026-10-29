package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import games.brennan.dungeontrain.editor.PlotCategory;

import java.util.ArrayList;
import java.util.List;

/**
 * Drilldown reached from the Editor menu's "Phases" row. One
 * {@link CommandMenuEntry.Toggle} row per {@link TrainPhase} — Overworld … Chuncks — lets the author pick
 * which worldgen phases the active weighted template may spawn in. Toggling a row dispatches
 * {@code /dungeontrain editor [contents|tracks] phase <id> [<name>] <phase> on|off} and the server
 * pushes a fresh {@link games.brennan.dungeontrain.net.EditorStatusPacket} carrying the updated
 * phase mask, so the next {@link #entries()} rebuild reflects the new state.
 *
 * <p>The default (every phase set) means "spawns everywhere". Clearing the last phase normalises
 * back to all phases server-side, so a template can never be gated to "spawns in no phase".</p>
 */
public final class PhaseSelectScreen implements MenuScreen {

    private final PlotCategory category;
    private final String modelId;
    private final String modelName;

    public PhaseSelectScreen(PlotCategory category, String modelId, String modelName) {
        this.category = category;
        this.modelId = modelId == null ? "" : modelId;
        this.modelName = modelName == null ? "" : modelName;
    }

    @Override
    public String title() {
        return MenuLang.t("editor.phases");
    }

    @Override
    public List<CommandMenuEntry> entries() {
        List<CommandMenuEntry> out = new ArrayList<>(TrainPhase.values().length + 1);
        String prefix = phaseCommandPrefix();
        if (prefix != null) {
            int mask = EditorStatusHudOverlay.phaseMask();
            for (TrainPhase p : TrainPhase.values()) {
                boolean on = (mask & p.bit()) != 0;
                // Plain click toggles this dimension on/off; shift-click sends the shared "others"
                // action — "toggle all but that one" — matching the world-space dimension menus.
                out.add(new CommandMenuEntry.Toggle(
                    displayName(p), on,
                    prefix + " " + p.token() + " on",
                    prefix + " " + p.token() + " off",
                    true,
                    prefix + " " + p.token() + " others"
                ));
            }
        }
        out.add(new CommandMenuEntry.Back(MenuLang.t("common.back")));
        return out;
    }

    /**
     * Per-category command prefix up to (but excluding) the phase token, or null when the model
     * is unaddressable — {@link PlotCategory#command}'s PHASE row.
     */
    private String phaseCommandPrefix() {
        if (category == null || !category.hasGate()) return null;
        return category.command(PlotCategory.Verb.PHASE, modelId, modelName).orElse(null);
    }

    private static String displayName(TrainPhase p) {
        return MenuLang.named("phase", p.token(), p.displayName());
    }

    /** Visible-for-test accessor. */
    public String modelId() {
        return modelId;
    }
}
