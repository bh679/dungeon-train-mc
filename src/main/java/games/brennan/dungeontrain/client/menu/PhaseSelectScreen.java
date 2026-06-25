package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.worldgen.TrainPhase;

import java.util.ArrayList;
import java.util.List;

/**
 * Drilldown reached from the Editor menu's "Phases" row. Four
 * {@link CommandMenuEntry.Toggle} rows — Overworld / Nether / Void / End — let the author pick
 * which worldgen phases the active weighted template may spawn in. Toggling a row dispatches
 * {@code /dungeontrain editor [contents|tracks] phase <id> [<name>] <phase> on|off} and the server
 * pushes a fresh {@link games.brennan.dungeontrain.net.EditorStatusPacket} carrying the updated
 * phase mask, so the next {@link #entries()} rebuild reflects the new state.
 *
 * <p>The default (every phase set) means "spawns everywhere". Clearing the last phase normalises
 * back to all phases server-side, so a template can never be gated to "spawns in no phase".</p>
 */
public final class PhaseSelectScreen implements MenuScreen {

    private final String category;
    private final String modelId;
    private final String modelName;

    public PhaseSelectScreen(String category, String modelId, String modelName) {
        this.category = category == null ? "" : category;
        this.modelId = modelId == null ? "" : modelId;
        this.modelName = modelName == null ? "" : modelName;
    }

    @Override
    public String title() {
        return "Phases";
    }

    @Override
    public List<CommandMenuEntry> entries() {
        List<CommandMenuEntry> out = new ArrayList<>(TrainPhase.values().length + 1);
        String prefix = phaseCommandPrefix();
        if (prefix != null) {
            int mask = EditorStatusHudOverlay.phaseMask();
            for (TrainPhase p : TrainPhase.values()) {
                boolean on = (mask & p.bit()) != 0;
                out.add(new CommandMenuEntry.Toggle(
                    displayName(p), on,
                    prefix + " " + p.token() + " on",
                    prefix + " " + p.token() + " off"
                ));
            }
        }
        out.add(new CommandMenuEntry.Back("< Back"));
        return out;
    }

    /**
     * Per-category command prefix up to (but excluding) the phase token, or null when the model
     * is unaddressable. Mirrors {@code EditorMenuScreen.weightTripleFor}'s command shapes.
     */
    private String phaseCommandPrefix() {
        if (modelId.isEmpty()) return null;
        return switch (category) {
            case "carriages" -> "dungeontrain editor phase " + modelId;
            case "contents" -> "dungeontrain editor contents phase " + modelId;
            case "tracks" -> modelName.isEmpty() ? null
                : "dungeontrain editor tracks phase " + modelId + " " + modelName;
            default -> null;
        };
    }

    private static String displayName(TrainPhase p) {
        return switch (p) {
            case OVERWORLD -> "Overworld";
            case NETHER -> "Nether";
            case VOID -> "Void";
            case END -> "End";
        };
    }

    /** Visible-for-test accessor. */
    public String modelId() {
        return modelId;
    }
}
