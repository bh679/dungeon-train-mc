package games.brennan.dungeontrain.editor;

import net.minecraft.server.level.ServerLevel;

/**
 * One-shot "re-mirror this plot from its master octant" — the deliberate
 * counterpart to the live mirroring in {@link EditorMirrorLiveHandler} and
 * {@link EditorVariantMirror#mirrorEditLive}.
 *
 * <p>This used to run implicitly from every editor {@code save()}, which made a
 * save destructive: the far half was rebuilt from the low-octant master
 * regardless of edit history, so deliberate asymmetry — and anything placed by
 * a path that doesn't fire the live handler (clipboard paste, {@code /fill},
 * structure stamping, edits made before the axis was toggled on) — was silently
 * overwritten, the world was mutated mid-save, and the variant sidecar was
 * rewritten as a side effect. Save now captures the plot as it stands, and this
 * runs only when the author asks for it via
 * {@code /dungeontrain editor mirror rebuild}.</p>
 */
public final class EditorMirrorRebuild {

    private EditorMirrorRebuild() {}

    /**
     * Rebuild {@code plot}'s far half from its authored master octant: variant
     * pools first, then structural blocks — the structural pass reads the marker
     * set <em>after</em> the variant pass so freshly reflected variant cells are
     * preserved.
     *
     * @return {@code false} (and no world writes) when the plot has no mirror
     *         axis enabled; {@code true} when a rebuild ran
     */
    public static boolean run(ServerLevel level, BlockVariantPlot plot) {
        if (!plot.mirrorX() && !plot.mirrorY() && !plot.mirrorZ()) return false;
        EditorVariantMirror.rebuildFromMaster(level, plot);
        EditorMirror.rebuildFromMaster(level, plot.origin(), plot.footprint(),
            plot.mirrorX(), plot.mirrorY(), plot.mirrorZ(), plot.allFlaggedPositions());
        return true;
    }
}
