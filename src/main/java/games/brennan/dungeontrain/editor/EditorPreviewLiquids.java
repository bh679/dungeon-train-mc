package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the editor's live preview is currently showing a liquid — the set the fluid mixins veto
 * against so a previewed water or lava source <b>renders without flowing</b>.
 *
 * <p>Flow suppression here is not cosmetic. The editor plot's world blocks <i>are</i> the template
 * until the author saves, so anything a liquid does to the plot gets baked in: water spreading
 * across the floor would be captured as authored water, and lava would burn the author's build out
 * from under them. A previewed liquid therefore has to sit exactly in its own cell.</p>
 *
 * <p>Positions rather than a geometric editor-region test, deliberately. The set is rebuilt whole
 * by {@link VariantEditorPreviewTicker} on every 1 Hz pass — a pass that visits no plots clears it,
 * so nothing can go stale — and it is exact, so no real-world fluid at editor altitude is ever
 * caught by accident. {@link #anyPresent} makes the common case (no editor, or no liquid
 * candidates) a single volatile read on the fluid hot path.</p>
 *
 * <p>Keep this class free of Sable / JOML types: it is referenced from mixin bodies, which load on
 * the transformer's bootstrap classloader. Same constraint as
 * {@link games.brennan.dungeontrain.ship.TrainFluidBarrier}.</p>
 */
public final class EditorPreviewLiquids {

    /** Dimension → packed {@link BlockPos#asLong()} positions currently previewing a liquid. */
    private static final Map<ResourceKey<Level>, Set<Long>> BY_LEVEL = new ConcurrentHashMap<>();

    /** Hot-path fast-out: false whenever no level is previewing any liquid at all. */
    private static volatile boolean anyPresent = false;

    private EditorPreviewLiquids() {}

    /**
     * Replace {@code level}'s whole set. Called once per preview pass with everything that pass
     * stamped, so a plot the author has walked away from drops out on the next tick rather than
     * leaving a stale veto behind.
     */
    public static void replace(ResourceKey<Level> dimension, Set<Long> packedPositions) {
        if (packedPositions.isEmpty()) {
            BY_LEVEL.remove(dimension);
        } else {
            BY_LEVEL.put(dimension, Set.copyOf(packedPositions));
        }
        anyPresent = !BY_LEVEL.isEmpty();
    }

    /** True when the editor is previewing a liquid at exactly {@code pos}. */
    public static boolean isPreviewLiquid(ResourceKey<Level> dimension, BlockPos pos) {
        if (!anyPresent) return false;
        Set<Long> positions = BY_LEVEL.get(dimension);
        return positions != null && positions.contains(pos.asLong());
    }

    /** Drop everything — server shutdown / test isolation. */
    public static void clear() {
        BY_LEVEL.clear();
        anyPresent = false;
    }

    /** Live count for {@code dimension}; diagnostics and tests. */
    public static int count(ResourceKey<Level> dimension) {
        Set<Long> positions = BY_LEVEL.get(dimension);
        return positions == null ? 0 : positions.size();
    }
}
