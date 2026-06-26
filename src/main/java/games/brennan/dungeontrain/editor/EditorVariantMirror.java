package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;

/**
 * Variant-pool mirroring — the editor "V" toggle. Where {@link EditorMirror}
 * reflects <em>structural</em> blocks, this reflects the per-cell variant
 * candidate pools stored in the four {@link BlockVariantPlot} sidecars, so an
 * author who builds one octant gets the symmetric half's variant pools for
 * free. Like structural mirroring it runs two ways:
 *
 * <ul>
 *   <li>{@link #mirrorEditLive} — live, as the author adds / removes a variant
 *       (called from {@link VariantBlockInteractions} and
 *       {@link VariantBlockBreakHandler}); and</li>
 *   <li>{@link #rebuildFromMaster} — a save-time backstop run from each editor's
 *       {@code save()} that makes the far half's pools an exact reflection of
 *       the authored low-octant master, regardless of edit history.</li>
 * </ul>
 *
 * <p>Both are gated on {@code plot.mirrorVariants() && (mirrorX|Y|Z)} — with the
 * "V" toggle off (the default) nothing here runs and variant cells keep their
 * historical single-marker behavior (e.g. the tunnel section chest).</p>
 *
 * <p>A reflected cell's in-world base block is stamped via {@link SilentBlockOps}
 * (the first pool entry, mirrored) because a variant shift-click suppresses
 * vanilla placement — so the structural live handler never fires for it, and
 * the far side would otherwise look empty until regeneration.</p>
 */
public final class EditorVariantMirror {

    private static final Logger LOGGER = LogUtils.getLogger();

    private EditorVariantMirror() {}

    /**
     * Propagate one variant edit at {@code localEdited} to its mirror-image
     * cells. {@code updatedOrNull} is the new candidate pool (an add / edit) —
     * image cells receive the axis-reflected pool and their base block; pass
     * {@code null} for a removal (image cells' pools are dropped and their
     * blocks cleared to air). No-op unless the plot opts into variant mirroring.
     */
    public static void mirrorEditLive(ServerLevel level, BlockVariantPlot plot, BlockPos localEdited,
                                      @Nullable List<VariantState> updatedOrNull) {
        boolean mx = plot.mirrorX(), my = plot.mirrorY(), mz = plot.mirrorZ();
        if (!plot.mirrorVariants() || (!mx && !my && !mz)) return;
        if (!plot.inBounds(localEdited)) return;

        BlockPos origin = plot.origin();
        Vec3i f = plot.footprint();
        // Mirror the source cell's lock-id too so the reflected cells join the
        // same lock group (a pasted lock-group cell stays grouped on both sides).
        int srcLockId = plot.lockIdAt(localEdited);
        boolean changed = false;
        for (EditorMirror.Image img : EditorMirror.imagesOf(localEdited, f, mx, my, mz)) {
            BlockPos tgtWorld = origin.offset(img.local().getX(), img.local().getY(), img.local().getZ());
            if (updatedOrNull == null) {
                plot.remove(img.local());
                SilentBlockOps.clearBlockSilent(level, tgtWorld);
            } else {
                List<VariantState> reflected =
                    EditorMirror.reflectStates(updatedOrNull, img.flipX(), img.flipY(), img.flipZ());
                plot.put(img.local(), reflected);
                plot.setLockId(img.local(), srcLockId); // 0 clears — mirrors the source's lock state
                SilentBlockOps.setBlockSilent(level, tgtWorld, reflected.get(0).state());
            }
            changed = true;
        }
        if (changed) trySave(plot);
    }

    /**
     * Rebuild the far-half variant pools from the authored low-octant master,
     * reflecting across the enabled axes. Run from each editor's {@code save()}
     * <em>before</em> the structural {@link EditorMirror#rebuildFromMaster}: it
     * writes (or clears) the far cells' pools + base blocks, after which the
     * caller recomputes its marker set so the structural pass skips the
     * freshly-mirrored variant cells. No-op unless the plot opts into variant
     * mirroring.
     */
    public static void rebuildFromMaster(ServerLevel level, BlockVariantPlot plot) {
        boolean mx = plot.mirrorX(), my = plot.mirrorY(), mz = plot.mirrorZ();
        if (!plot.mirrorVariants() || (!mx && !my && !mz)) return;

        BlockPos origin = plot.origin();
        Vec3i f = plot.footprint();
        boolean changed = false;
        for (int dx = 0; dx < f.getX(); dx++) {
            if (EditorMirror.source(dx, f.getX(), mx) != dx) continue; // far column — owned by a master
            for (int dy = 0; dy < f.getY(); dy++) {
                if (EditorMirror.source(dy, f.getY(), my) != dy) continue;
                for (int dz = 0; dz < f.getZ(); dz++) {
                    if (EditorMirror.source(dz, f.getZ(), mz) != dz) continue;
                    BlockPos masterLocal = new BlockPos(dx, dy, dz);
                    List<VariantState> masterPool = plot.statesAt(masterLocal);
                    int masterLockId = plot.lockIdAt(masterLocal);
                    for (EditorMirror.Image img : EditorMirror.imagesOf(masterLocal, f, mx, my, mz)) {
                        if (masterPool == null || masterPool.isEmpty()) {
                            // No master pool — drop any stale far entry; the
                            // structural pass mirrors the master's block here.
                            plot.remove(img.local());
                        } else {
                            List<VariantState> reflected =
                                EditorMirror.reflectStates(masterPool, img.flipX(), img.flipY(), img.flipZ());
                            plot.put(img.local(), reflected);
                            plot.setLockId(img.local(), masterLockId); // join the master's lock group
                            BlockPos tgtWorld = origin.offset(
                                img.local().getX(), img.local().getY(), img.local().getZ());
                            SilentBlockOps.setBlockSilent(level, tgtWorld, reflected.get(0).state());
                        }
                        changed = true;
                    }
                }
            }
        }
        if (changed) trySave(plot);
    }

    private static void trySave(BlockVariantPlot plot) {
        try {
            plot.save();
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Variant-mirror save failed for {}: {}", plot.key(), e.toString());
        }
    }
}
