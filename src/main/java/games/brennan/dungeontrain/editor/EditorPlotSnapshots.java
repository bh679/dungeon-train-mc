package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Server-side singleton that records the block geometry of each editor
 * plot immediately after it's been stamped. Compared against the live
 * world by {@link EditorDirtyCheck} to decide whether a plot has
 * unsaved edits.
 *
 * <p>Why a snapshot instead of comparing live to the on-disk template:
 * the editor's stamp pass composes a base NBT + parts overlay + sidecar
 * variants at runtime, and any of those input layers can drift between
 * save-time and re-entry — a parts template updated in a different
 * worktree, a variant sidecar with new entries, etc. Comparing the
 * post-stamp world to the saved NBT would flag every such drift as
 * "unsaved" even though the player hasn't touched the plot. The snapshot
 * captures whatever the stamp pass actually produced, so a re-entry
 * with no edits compares against itself and reads as clean.</p>
 *
 * <p>State is in-memory only — lost on server restart. Acceptable
 * because plots aren't auto-stamped on world load: the next
 * {@code /dt editor &lt;cat&gt;} run will re-stamp every plot and the
 * snapshot will refill at that moment. Server-restart-with-active-edits
 * is not a workflow we currently support.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EditorPlotSnapshots {

    /** {@code "carriages:standard"} → block-pos → state. Air positions excluded. */
    private static final Map<String, Map<BlockPos, BlockState>> SNAPSHOTS = new HashMap<>();

    private EditorPlotSnapshots() {}

    /**
     * Take a snapshot of the {@code length × height × width} region at
     * {@code origin} and store it under {@code key}. Air positions are
     * excluded so the map stays sparse — comparison treats absent keys
     * as "expected = AIR".
     */
    public static synchronized void capture(String key, ServerLevel level, BlockPos origin,
                                            int length, int height, int width) {
        Map<BlockPos, BlockState> snap = new HashMap<>();
        for (int dx = 0; dx < length; dx++) {
            for (int dy = 0; dy < height; dy++) {
                for (int dz = 0; dz < width; dz++) {
                    BlockState state = level.getBlockState(origin.offset(dx, dy, dz));
                    if (!state.isAir()) {
                        snap.put(new BlockPos(dx, dy, dz), state);
                    }
                }
            }
        }
        SNAPSHOTS.put(key, snap);
    }

    /**
     * Returns the snapshot for {@code key}, or {@code null} when no
     * snapshot has been recorded (e.g. the plot has never been stamped
     * this session, or the server just restarted). Callers should treat
     * {@code null} as "no baseline → not dirty" so a missing snapshot
     * never produces a false positive.
     */
    @Nullable
    public static synchronized Map<BlockPos, BlockState> get(String key) {
        Map<BlockPos, BlockState> v = SNAPSHOTS.get(key);
        return v == null ? null : new HashMap<>(v);
    }

    /** Drop the snapshot for a specific (category, model). Called from each editor's {@code clearPlot} so a switched-away category doesn't leave stale snapshots that the next dirty check would compare an empty plot against. */
    public static synchronized void clear(String key) {
        SNAPSHOTS.remove(key);
    }

    /** Wipe all snapshots. */
    public static synchronized void clearAll() {
        SNAPSHOTS.clear();
    }

    /**
     * Drop every snapshot when the integrated server stops.
     *
     * <p>The javadoc has claimed this happened since the class was written, but nothing actually
     * called it. On a dedicated server that was harmless — the JVM dies with the map. In a
     * single-player session it is not: the client process survives quitting to title, so
     * baselines from world A were still in the map when world B loaded. Keys collide by design
     * (both worlds have {@code carriages:standard}, and every Train Builder world has
     * {@code builder:carriage:0}), so world B's freshly-stamped plots would be compared against
     * world A's edits and reported as unsaved.</p>
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clearAll();
    }

    /** Stable key for a (category, model) pair. */
    public static String key(String categoryId, String modelId) {
        return categoryId + ":" + modelId;
    }
}
