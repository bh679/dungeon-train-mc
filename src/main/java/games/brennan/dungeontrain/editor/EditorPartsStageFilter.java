package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;

/**
 * The single, <b>global</b> "hide part plots not used by the focused stage" toggle for the in-game
 * editor — the parts-grid counterpart to {@link EditorStageSelection}'s carriage preview. Purely
 * in-memory editor UI state: never written to disk, cleared when the server stops.
 *
 * <p>Global (not per-player) on purpose, for the same reason as {@link EditorStageSelection}: the
 * parts grid is shared overworld geometry, so a single flag keeps the shared stamping coherent
 * between concurrently-editing OPs (last writer wins).</p>
 *
 * <p>When active and a stage is {@linkplain EditorStageSelection#effective() effective},
 * {@link CarriagePartEditor#stampAllPlots} airs out the footprint of every part plot whose
 * {@code (kind, name)} is not linked to that stage in any carriage template's {@code .parts.json}
 * (per {@link StageBlockIndex#partsForStage}), keeping the bedrock cage so the slot still reads as
 * a plot. Entering or saving a hidden part via {@link CarriagePartEditor#stampPlot} still paints it
 * — the filter only shapes the whole-grid pass.</p>
 */
public final class EditorPartsStageFilter {

    private static volatile boolean active = false;

    private EditorPartsStageFilter() {}

    /** True when the parts grid is filtered to the focused stage's parts. */
    public static boolean isActive() {
        return active;
    }

    /** Flip the filter; returns the new state. */
    public static boolean toggle() {
        active = !active;
        return active;
    }

    /** Reset to the unfiltered grid. */
    public static void clear() {
        active = false;
    }

        public static void onServerStopped(net.minecraft.server.MinecraftServer server) {
        clear();
    }
}
