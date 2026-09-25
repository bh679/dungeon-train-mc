package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.editor.PlotCategory;

import java.util.ArrayList;
import java.util.List;

/**
 * Drilldown reached from the Editor menu's "Phases" row. One
 * {@link CommandMenuEntry.Toggle} row per band option, grouped — lets the author pick
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
        List<CommandMenuEntry> out = new ArrayList<>();
        String prefix = phaseCommandPrefix();
        if (prefix != null) {
            int mask = EditorStatusHudOverlay.phaseMask();
            out.addAll(BandOptionEntries.of(mask, m -> prefix + " mask " + m));
        }
        out.add(new CommandMenuEntry.Back(MenuLang.t("common.back")));
        return out;
    }

    /**
     * Per-category command prefix up to (but excluding) the phase token, or null when the model
     * is unaddressable. Mirrors {@code EditorMenuScreen.weightTripleFor}'s command shapes.
     */
    private String phaseCommandPrefix() {
        if (modelId.isEmpty()) return null;
        if (category == null) return null;
        return switch (category) {
            case CARRIAGES -> "dungeontrain editor phase " + modelId;
            case CONTENTS -> "dungeontrain editor contents phase " + modelId;
            case TRACKS -> modelName.isEmpty() ? null
                : "dungeontrain editor tracks phase " + modelId + " " + modelName;
            case PORTALS -> modelName.isEmpty() ? null
                : "dungeontrain editor portals phase " + modelId + " " + modelName;
            // No per-template spawn gate to edit.
            case WHOLE -> "dungeontrain editor whole phase " + modelId;
            case WHOLE_GROUP -> "dungeontrain editor whole group phase " + modelId;
            case PARTS, ARCHITECTURE -> null;
        };
    }

    /** Visible-for-test accessor. */
    public String modelId() {
        return modelId;
    }
}
