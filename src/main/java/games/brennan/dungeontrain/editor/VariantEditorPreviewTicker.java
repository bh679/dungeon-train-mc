package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side tick handler that animates variant cells in the editor world
 * so the author sees a live preview of what each entry's chosen rotation
 * would produce at carriage spawn time.
 *
 * <p>Behaviour (editor-only — never fires at carriage spawn):
 * <ul>
 *   <li>Cells with multiple variant entries cycle through entries every
 *       3 seconds (unpinned).</li>
 *   <li>Within each entry slot, if the entry's rotation is RANDOM or
 *       OPTIONS, the direction cycles every 1 second across the
 *       valid+selected direction set. LOCK shows its single direction
 *       statically.</li>
 *   <li>When the author clicks any rotation control on a row, that row's
 *       cell becomes pinned (entry cycle stops; direction cycle continues).
 *       See {@link VariantEditorPreviewState}.</li>
 * </ul></p>
 *
 * <p>Hot-path filter — cells skip the per-tick work unless they would
 * actually animate ({@code entries.size() > 1} or any entry has a
 * non-default rotation). Static / single-entry cells are written once
 * (when the author first enters their plot) and then idle.</p>
 *
 * <p>Performance: rate-gated to 1 Hz (every 20 server ticks). Only OP
 * players in editor plots trigger the scan. Worst case is a single
 * carriage editor plot with ~100 variant cells = 100 cheap state
 * comparisons + the few that actually changed get a {@code setBlockSilent}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class VariantEditorPreviewTicker {

    /** Cycle rate — one tick of preview work per second. */
    private static final int TICK_PERIOD = 20;

    /** Entry cycle slot length in 1Hz ticks (3 seconds at 1Hz). */
    private static final int ENTRY_SLOT_TICKS = 3;

    private VariantEditorPreviewTicker() {}

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;
        long gameTime = level.getGameTime();
        if (gameTime % TICK_PERIOD != 0) return;

        long previewTick = gameTime / TICK_PERIOD;

        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        for (ServerPlayer player : level.players()) {
            if (!player.hasPermissions(2)) continue;
            BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
            if (plot == null) continue;
            updatePlot(level, plot, previewTick);
        }
    }

    /**
     * Walk every cell in {@code plot} and update its world block to reflect
     * the current preview frame. Cells that wouldn't animate are skipped on
     * the second-and-later visits — first visit sets the initial state for
     * LOCK entries, subsequent ticks no-op.
     */
    private static void updatePlot(ServerLevel level, BlockVariantPlot plot, long previewTick) {
        java.util.Map<BlockPos, java.util.List<VariantState>> snapshot = collectCells(plot);
        for (java.util.Map.Entry<BlockPos, java.util.List<VariantState>> e : snapshot.entrySet()) {
            BlockPos localPos = e.getKey();
            List<VariantState> states = e.getValue();
            if (states == null || states.isEmpty()) continue;

            int entryIdx = pickEntryIndex(plot.key(), localPos, states.size(), previewTick);
            VariantState picked = states.get(entryIdx);
            // Skip the empty-placeholder sentinel so the cell's "this is a
            // variant cell" marker stays visible to the author when the
            // cycle lands on the empty entry. Showing an air-equivalent
            // would lose the marker until the next entry slot.
            if (CarriageVariantBlocks.isEmptyPlaceholder(picked.state())) continue;

            BlockState toShow = computePreviewState(picked, previewTick);
            BlockPos worldPos = plot.origin().offset(localPos);
            BlockState existing = level.getBlockState(worldPos);
            if (!existing.equals(toShow)) {
                SilentBlockOps.setBlockSilent(level, worldPos, toShow, picked.blockEntityNbt());
            }
        }
    }

    /**
     * Snapshot the plot's cells to a local map — defensive copy so the
     * sidecar can mutate freely while we iterate (the menu controller
     * runs on the same thread but this matches the existing ticker
     * patterns elsewhere in the codebase).
     */
    private static java.util.Map<BlockPos, java.util.List<VariantState>> collectCells(BlockVariantPlot plot) {
        java.util.Map<BlockPos, java.util.List<VariantState>> out = new java.util.LinkedHashMap<>();
        net.minecraft.core.Vec3i fp = plot.footprint();
        // Iterate the footprint; statesAt returns null for non-cells. This
        // is O(footprint) per tick — for a typical carriage plot ~500 cells
        // checked at 1 Hz, ~25 reads on average that need processing.
        for (int x = 0; x < fp.getX(); x++) {
            for (int y = 0; y < fp.getY(); y++) {
                for (int z = 0; z < fp.getZ(); z++) {
                    BlockPos local = new BlockPos(x, y, z);
                    java.util.List<VariantState> states = plot.statesAt(local);
                    if (states != null && !states.isEmpty()) out.put(local, states);
                }
            }
        }
        return out;
    }

    /**
     * Pinned entry overrides cycle. Otherwise rotate through entries one
     * per {@link #ENTRY_SLOT_TICKS}-tick slot.
     */
    static int pickEntryIndex(String plotKey, BlockPos localPos, int entryCount, long previewTick) {
        Integer pinned = VariantEditorPreviewState.pinnedEntry(plotKey, localPos);
        if (pinned != null && pinned >= 0 && pinned < entryCount) return pinned;
        if (entryCount <= 1) return 0;
        return (int) ((previewTick / ENTRY_SLOT_TICKS) % entryCount);
    }

    /**
     * Pure function: given a picked variant entry and the current preview
     * tick, return the BlockState to display in the world.
     * <ul>
     *   <li>LOCK with a direction → that direction.</li>
     *   <li>RANDOM → cycle 1Hz over directions valid for the block's
     *       rotation property.</li>
     *   <li>OPTIONS → cycle 1Hz over the intersection of the author's
     *       selected dirs and the property-valid set.</li>
     *   <li>No rotation property OR mode RANDOM with no valid dirs →
     *       return the base state unchanged.</li>
     * </ul>
     */
    static BlockState computePreviewState(VariantState picked, long previewTick) {
        VariantRotation rot = picked.rotation();
        BlockState base = picked.state();
        int validMask = RotationApplier.validDirMask(base);
        // Non-rotatable block: every mode degrades to "show base".
        if (validMask == VariantRotation.ALL_DIRS_MASK) {
            // Either the block has FACING (all 6 valid) or no rotation
            // property — distinguish via canRotate to avoid cycling
            // a block that wouldn't actually change.
            if (!RotationApplier.canRotate(base)) return base;
        }

        if (rot.mode() == VariantRotation.Mode.LOCK && rot.dirMask() != 0) {
            int bit = Integer.lowestOneBit(rot.dirMask());
            if ((validMask & bit) == 0) return base;
            return RotationApplier.applyDirection(base, Direction.values()[Integer.numberOfTrailingZeros(bit)]);
        }

        int requestMask = rot.mode() == VariantRotation.Mode.OPTIONS
            ? rot.dirMask()
            : VariantRotation.ALL_DIRS_MASK;
        int finalMask = validMask & requestMask;
        if (finalMask == 0) return base;
        List<Direction> options = new ArrayList<>(6);
        for (Direction d : Direction.values()) {
            if ((finalMask & VariantRotation.maskOf(d)) != 0) options.add(d);
        }
        if (options.isEmpty()) return base;
        int dirIdx = (int) (previewTick % options.size());
        return RotationApplier.applyDirection(base, options.get(dirIdx));
    }
}
