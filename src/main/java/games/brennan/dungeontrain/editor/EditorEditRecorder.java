package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a player's block edits inside an editor plot into {@link EditorEditHistory}
 * steps.
 *
 * <p>Shape and guards mirror {@link EditorMirrorLiveHandler} — the same three
 * place / break events, the same "is this player in a plot" resolution — with
 * two differences that matter:</p>
 *
 * <ul>
 *   <li><b>{@link EventPriority#HIGHEST}.</b> The recorder must read each cell's
 *       <i>before</i> state ahead of {@link EditorMirrorLiveHandler}, which
 *       writes the mirror images from its own listener on the same events. At
 *       default priority the two would race and a mirrored edit could record
 *       the mirror's output as its own input.</li>
 *   <li><b>Mirror images are recorded too.</b> Candidate cells are the edited
 *       cell plus {@link EditorMirror#imagesOf} across the plot's enabled axes,
 *       so an edit that live mirroring fanned out to eight cells undoes as one
 *       step rather than leaving seven orphans behind.</li>
 * </ul>
 *
 * <p><b>One tick, one step.</b> Cells accumulate into a per-player pending map
 * and flush on {@link ServerTickEvent.Post}, where each is compared against the
 * world as it now stands and the unchanged are dropped. That groups a
 * drag-placed run into a single undo, and it means anything else that wrote to
 * those cells during the tick — live mirroring above all — is captured without
 * the recorder needing to know it exists.</p>
 *
 * <p>Undo and redo apply their writes through
 * {@link games.brennan.dungeontrain.worldgen.SilentBlockOps}, which fires no
 * block events, so this recorder cannot see them. {@link #suppressed} guards
 * the tick flush for the same reason at one remove: an applier running
 * mid-tick must not have its own writes swept up by a flush of edits made
 * earlier in that tick.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EditorEditRecorder {

    private EditorEditRecorder() {}

    /**
     * A cell's state before this tick's edits touched it, captured at event
     * time. {@code nbt} is the block entity's full contents, or null when the
     * state has none.
     */
    private record Before(BlockState state, @Nullable CompoundTag nbt) {}

    /** One player's in-flight edits for the current tick. */
    private static final class Pending {
        private final ServerLevel level;
        private final String plotKey;
        /** Insertion-ordered so the recorded cells read in edit order. */
        private final Map<BlockPos, Before> cells = new LinkedHashMap<>();
        /** Variant-sidecar plot key → its serialised state before this tick's edits. */
        private final Map<String, String> sidecars = new LinkedHashMap<>();
        /** Names the step; set when the pending is created and kept for the whole tick. */
        private final String label;

        Pending(ServerLevel level, String plotKey, String label) {
            this.level = level;
            this.plotKey = plotKey;
            this.label = label;
        }
    }

    /**
     * A player's plot resolution for the current tick. Both fields may be null:
     * {@code scope} when they are outside every plot, {@code plot} when they are
     * in a category {@link BlockVariantPlot#resolveAt} does not cover.
     */
    private record Resolved(@Nullable EditorPlotScope scope, @Nullable BlockVariantPlot plot) {}

    /** player → this tick's pending edits. Server-thread only; no synchronisation. */
    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    /**
     * Per-tick memo for {@link #resolve}. A multi-block place fires one capture
     * per cell, and resolving the plot cascade for each of them — twice over,
     * once for the scope and once for the mirror handle — turns a large fill
     * into real server time for an answer that cannot change within a tick.
     */
    private static final Map<UUID, Resolved> RESOLVED = new HashMap<>();

    /** True while {@link EditorEditApplier} is writing — suppresses recording of its own work. */
    private static boolean suppressed = false;

    /**
     * Run {@code body} with recording turned off. Used by the applier so an
     * undo is not itself recorded as an edit.
     */
    public static void withRecordingSuppressed(Runnable body) {
        boolean previous = suppressed;
        suppressed = true;
        try {
            body.run();
        } finally {
            suppressed = previous;
        }
    }

    /** This tick's plot resolution for {@code player}, computed once and reused. */
    private static Resolved resolve(ServerPlayer player, ServerLevel level) {
        Resolved cached = RESOLVED.get(player.getUUID());
        if (cached != null) return cached;
        EditorPlotScope scope = EditorPlotScope.resolveAt(player, level).orElse(null);
        BlockVariantPlot plot = scope == null
            ? null
            : BlockVariantPlot.resolveAt(player, DungeonTrainWorldData.get(level).dims());
        Resolved resolved = new Resolved(scope, plot);
        RESOLVED.put(player.getUUID(), resolved);
        return resolved;
    }

    // ─── Capture ───────────────────────────────────────────────────────────

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        capture(player, level, event.getPos(), "Place");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMultiBlockPlace(BlockEvent.EntityMultiPlaceEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        for (BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
            capture(player, level, snapshot.getPos(), "Place");
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        capture(player, level, event.getPos(), "Break");
    }

    /**
     * Register the edited cell — and every cell live mirroring is about to
     * reflect it into — as pending for this tick. No-op outside an editor plot,
     * outside the plot's box, or while an applier is running.
     */
    private static void capture(ServerPlayer player, ServerLevel level, BlockPos worldPos, String label) {
        if (suppressed) return;
        Resolved resolved = resolve(player, level);
        if (resolved.scope() == null) return;
        if (!resolved.scope().contains(worldPos)) return;

        Pending pending = pendingFor(player, level, resolved.scope().key(), label);
        rememberBefore(pending, level, worldPos);
        for (BlockPos image : mirrorImagesOf(resolved.plot(), worldPos)) {
            rememberBefore(pending, level, image);
        }
    }

    /**
     * This tick's pending edits for {@code player}, created on first use.
     *
     * <p>A plot switch mid-tick is not something a player can do, but a stale
     * entry left by a flush that threw would be — so a pending filed against a
     * different plot is replaced rather than appended to.</p>
     */
    private static Pending pendingFor(ServerPlayer player, ServerLevel level, String plotKey, String label) {
        Pending pending = PENDING.get(player.getUUID());
        if (pending == null || !pending.plotKey.equals(plotKey)) {
            pending = new Pending(level, plotKey, label);
            PENDING.put(player.getUUID(), pending);
        }
        return pending;
    }

    /**
     * Note that {@code player} is about to change the variant sidecar of the
     * plot they are standing in, so this tick's step captures it.
     *
     * <p>Call this <b>before</b> the mutation. Every sidecar-editing path funnels
     * through here rather than pushing its own step, which is what makes one
     * Ctrl+Z undo a break <i>and</i> the variant entry that break removed —
     * they are one action to the author, and they happen in one tick.</p>
     *
     * <p>{@code label} is used only if this is the tick's first edit; a block
     * edit that also touches a sidecar keeps its "Place" / "Break" label.</p>
     */
    public static void notePendingSidecar(ServerPlayer player, String label) {
        if (suppressed) return;
        ServerLevel level = player.serverLevel();
        Resolved resolved = resolve(player, level);
        if (resolved.scope() == null || resolved.plot() == null) return;
        BlockVariantPlot plot = resolved.plot();

        Pending pending = pendingFor(player, level, resolved.scope().key(), label);
        // First write wins — the state from before the tick, not between two
        // edits within it.
        pending.sidecars.putIfAbsent(plot.key(), plot.snapshotJson());
    }

    /**
     * World positions live mirroring will reflect {@code worldPos} into, or
     * empty when the player's plot has no mirror axis enabled (or is a category
     * {@link BlockVariantPlot#resolveAt} does not cover, e.g. a portal room —
     * which has no live mirroring either, so there is nothing to miss).
     */
    private static List<BlockPos> mirrorImagesOf(@Nullable BlockVariantPlot plot, BlockPos worldPos) {
        if (plot == null) return List.of();
        if (!plot.mirrorX() && !plot.mirrorY() && !plot.mirrorZ()) return List.of();

        BlockPos local = worldPos.subtract(plot.origin());
        if (!plot.inBounds(local)) return List.of();

        Vec3i footprint = plot.footprint();
        List<EditorMirror.Image> images =
            EditorMirror.imagesOf(local, footprint, plot.mirrorX(), plot.mirrorY(), plot.mirrorZ());
        List<BlockPos> out = new ArrayList<>(images.size());
        for (EditorMirror.Image image : images) {
            out.add(plot.origin().offset(image.local()));
        }
        return out;
    }

    /** First write wins — a cell edited twice in one tick keeps its state from before the tick. */
    private static void rememberBefore(Pending pending, ServerLevel level, BlockPos worldPos) {
        if (pending.cells.containsKey(worldPos)) return;
        pending.cells.put(worldPos, new Before(level.getBlockState(worldPos), readNbt(level, worldPos)));
    }

    /** Full block-entity contents at {@code pos}, or null when there is no block entity. */
    @Nullable
    static CompoundTag readNbt(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be == null ? null : be.saveWithFullMetadata(level.registryAccess());
    }

    // ─── Flush ─────────────────────────────────────────────────────────────

    /**
     * Close out the tick: diff every pending cell against the world as it now
     * stands and push one step per player who actually changed something.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        RESOLVED.clear();
        if (PENDING.isEmpty()) return;
        Map<UUID, Pending> flushing = new HashMap<>(PENDING);
        PENDING.clear();
        if (suppressed) return;

        for (Map.Entry<UUID, Pending> entry : flushing.entrySet()) {
            Pending pending = entry.getValue();
            List<EditorEditHistory.Cell> changed = new ArrayList<>(pending.cells.size());
            for (Map.Entry<BlockPos, Before> cell : pending.cells.entrySet()) {
                BlockPos pos = cell.getKey();
                Before before = cell.getValue();
                BlockState after = pending.level.getBlockState(pos);
                if (after == before.state()) continue;
                changed.add(new EditorEditHistory.Cell(
                    pos, before.state(), before.nbt(), after, readNbt(pending.level, pos)));
            }
            EditorEditHistory.push(entry.getKey(),
                new EditorEditHistory.Step(pending.plotKey, pending.label, changed,
                    changedSidecars(pending)));
        }
    }

    /**
     * Sidecars noted this tick whose serialised form actually moved. A noted
     * sidecar that ends the tick unchanged — a variant add rejected by a bounds
     * check, say — contributes nothing.
     */
    private static List<EditorEditHistory.SidecarSnapshot> changedSidecars(Pending pending) {
        if (pending.sidecars.isEmpty()) return List.of();
        CarriageDims dims = DungeonTrainWorldData.get(pending.level).dims();
        List<EditorEditHistory.SidecarSnapshot> out = new ArrayList<>(pending.sidecars.size());
        for (Map.Entry<String, String> entry : pending.sidecars.entrySet()) {
            BlockVariantPlot plot = BlockVariantPlot.resolveByKey(entry.getKey(), dims);
            if (plot == null) continue;
            String after = plot.snapshotJson();
            if (after.equals(entry.getValue())) continue;
            out.add(new EditorEditHistory.SidecarSnapshot(entry.getKey(), entry.getValue(), after));
        }
        return out;
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────

    /** A logged-out player's history is unreachable — drop it rather than leak it. */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        PENDING.remove(player.getUUID());
        RESOLVED.remove(player.getUUID());
        EditorEditHistory.clearPlayer(player.getUUID());
    }

    /**
     * The history is per-session by design, like {@link EditorPlotSnapshots} —
     * plots are re-stamped on the next {@code /dt editor} run anyway, which
     * would invalidate every step. Clear on the way out so a single-player
     * world reopened in the same process starts empty.
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
        RESOLVED.clear();
        EditorEditHistory.clearAll();
    }
}
