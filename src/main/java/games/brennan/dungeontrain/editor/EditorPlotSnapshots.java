package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.template.TemplateDecor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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

    /**
     * {@code "carriages:standard"} → a value summarising the plot's hung decoration.
     *
     * <p>Separate from the block map because decoration is not blocks: an item frame is an entity,
     * so hanging one changes no {@link BlockState} anywhere and the position compare reads the plot
     * as untouched. Before templates carried decor that was correct; now it means an author can
     * decorate a carriage, walk out, and be told they had nothing to save.</p>
     */
    private static final Map<String, Long> DECOR = new HashMap<>();

    /**
     * {@code "carriages:standard"} → the local positions whose variant pool changed since the
     * baseline was taken. An empty set is still "edited" — a lock id, mirror flag or copy setting
     * has no cell of its own.
     *
     * <p>Variant cells are the one thing the block compare cannot see: {@link EditorDirtyCheck}
     * skips them because the preview ticker rewrites them every second, and the sidecar itself is
     * written to disk on every Z-menu edit. So an author could add a whole variant pool, walk out,
     * and be told there was nothing to save — the same gap decoration used to have.</p>
     */
    private static final Map<String, Set<BlockPos>> SIDECAR_EDITS = new HashMap<>();

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
        DECOR.put(key, decorFingerprint(level, origin, length, height, width));
        SIDECAR_EDITS.remove(key);
        // Every stamp and every save lands here, and after either the sidecar on disk is the plot's
        // baseline again — see EditorSidecarBaseline.
        EditorSidecarBaseline.forget(key);
    }

    /**
     * Record that {@code key}'s variant sidecar changed at {@code localPos} — or somewhere with no
     * cell, when null. Cleared by the next {@link #capture}, which every stamp and every save runs.
     */
    public static synchronized void markSidecarEdit(@Nullable String key, @Nullable BlockPos localPos) {
        if (key == null) return;
        Set<BlockPos> cells = SIDECAR_EDITS.computeIfAbsent(key, k -> new LinkedHashSet<>());
        if (localPos != null) cells.add(localPos.immutable());
    }

    /** Whether {@code key}'s variant sidecar changed since its baseline was taken. */
    public static synchronized boolean sidecarEdited(String key) {
        return SIDECAR_EDITS.containsKey(key);
    }

    /** The cells {@link #markSidecarEdit} recorded for {@code key}; empty when none or unknown. */
    public static synchronized Set<BlockPos> sidecarEdits(String key) {
        Set<BlockPos> cells = SIDECAR_EDITS.get(key);
        return cells == null ? Set.of() : new LinkedHashSet<>(cells);
    }

    /**
     * Whether the plot's decoration still matches its baseline.
     *
     * <p>{@code true} when no baseline was recorded, so a missing snapshot never produces a false
     * positive — the same contract {@link #get} documents.</p>
     */
    public static synchronized boolean decorMatches(String key, ServerLevel level, BlockPos origin,
                                                    int length, int height, int width) {
        Long baseline = DECOR.get(key);
        return baseline == null
            || baseline == decorFingerprint(level, origin, length, height, width);
    }

    /**
     * A value summarising which carried entities stand where in a plot.
     *
     * <p>{@link TemplateDecor#carried}, not the wall-hung three: a template carries the mobs an
     * author places as well, so placing one has to count as a change or the plot reads clean and
     * the editor never offers to save it.</p>
     *
     * <p>Order-independent (the entity query's order is not stable) and position-rounded to a third
     * of a block — finer than any deliberate reposition, coarser than float drift. Facing is folded
     * in because turning a picture to the opposite wall moves it barely at all.</p>
     *
     * <p>A mob contributes its type only. It turns to look at the author and wanders where its AI
     * takes it, so its position and facing change on their own; folding them in had every plot
     * with a mob in it pulsing "unsaved" for as long as the mob was awake. Placing or removing one
     * still changes the sum, which is the edit the fingerprint exists to catch.</p>
     */
    private static long decorFingerprint(ServerLevel level, BlockPos origin,
                                         int length, int height, int width) {
        AABB box = new AABB(
            origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + length, origin.getY() + height, origin.getZ() + width);
        long sum = 0L;
        for (Entity e : level.getEntities((Entity) null, box, TemplateDecor::carried)) {
            long h = e.getType().hashCode();
            if (e instanceof LivingEntity) {
                sum += h;
                continue;
            }
            h = h * 31 + Math.round((e.getX() - origin.getX()) * 3.0);
            h = h * 31 + Math.round((e.getY() - origin.getY()) * 3.0);
            h = h * 31 + Math.round((e.getZ() - origin.getZ()) * 3.0);
            h = h * 31 + Math.round(e.getYRot());
            sum += h;
        }
        return sum;
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

    /**
     * Whether {@code key} has a snapshot — i.e. whether its plot has been stamped this session.
     *
     * <p>Cheaper than {@link #get}, which copies the whole map, and says what the caller means when
     * the question is "is this plot standing in the world" rather than "what did it look like".</p>
     */
    public static synchronized boolean has(String key) {
        return SNAPSHOTS.containsKey(key);
    }

    /** Drop the snapshot for a specific (category, model). Called from each editor's {@code clearPlot} so a switched-away category doesn't leave stale snapshots that the next dirty check would compare an empty plot against. */
    public static synchronized void clear(String key) {
        SNAPSHOTS.remove(key);
        DECOR.remove(key);
        SIDECAR_EDITS.remove(key);
        EditorSidecarBaseline.forget(key);
    }

    /** Wipe all snapshots. */
    public static synchronized void clearAll() {
        SNAPSHOTS.clear();
        DECOR.clear();
        SIDECAR_EDITS.clear();
        EditorSidecarBaseline.clearAll();
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
