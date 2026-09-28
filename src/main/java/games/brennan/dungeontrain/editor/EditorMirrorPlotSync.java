package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.EditorMirrorPlotPacket;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps each editor player's client told which mirror-enabled plot they are standing in
 * ({@link EditorMirrorPlotPacket}), for client-side mirrored previews. Driven from
 * {@link VariantOverlayRenderer#onLevelTick}; deduped, so a steady tick sends nothing.
 *
 * <p>Resolved from where the player stands first; failing that, from the block they are aiming at
 * (and the cell a placement there would fill), so the preview still mirrors when building into a
 * plot from outside it. When neither finds a plot the last one is kept — safe, because the client
 * only mirrors cells inside the synced plot, so a stale plot can never mirror anything elsewhere.
 * It is cleared by {@link #forget} on leaving the build area. Server thread only.</p>
 */
public final class EditorMirrorPlotSync {

    private static final Map<UUID, EditorMirrorPlotPacket> LAST = new HashMap<>();

    /** How far the aim ray reaches — well past Effortless Building's default reach. */
    private static final double AIM_REACH = 96.0;

    /** The aim ray runs every this many ticks, and only when the player's feet are in no plot. */
    private static final int AIM_EVERY_TICKS = 5;

    private EditorMirrorPlotSync() {}

    public static void push(ServerPlayer player, CarriageDims dims) {
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) {
            if (player.tickCount % AIM_EVERY_TICKS != 0) return;
            plot = resolveAimed(player, dims);
            if (plot == null) return; // keep the last plot — see the class javadoc
        }
        EditorMirrorPlotPacket next = plot == null
            ? EditorMirrorPlotPacket.empty()
            : new EditorMirrorPlotPacket(plot.origin().immutable(), plot.footprint(),
                plot.mirrorX(), plot.mirrorY(), plot.mirrorZ());
        if (!next.active()) next = EditorMirrorPlotPacket.empty();
        EditorMirrorPlotPacket prev = LAST.get(player.getUUID());
        if (next.equals(prev) || (prev == null && !next.active())) return;
        LAST.put(player.getUUID(), next);
        DungeonTrainNet.sendTo(player, next);
    }

    private static BlockVariantPlot resolveAimed(ServerPlayer player, CarriageDims dims) {
        if (!(player.pick(AIM_REACH, 1.0f, false) instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        ServerLevel level = player.serverLevel();
        BlockVariantPlot plot = BlockVariantPlot.resolveAtPos(level, hit.getBlockPos(), dims);
        return plot != null ? plot
            : BlockVariantPlot.resolveAtPos(level, hit.getBlockPos().relative(hit.getDirection()), dims);
    }

    /** Clear the client's copy once when the player leaves the build area. */
    public static void forget(ServerPlayer player) {
        EditorMirrorPlotPacket prev = LAST.remove(player.getUUID());
        if (prev != null && prev.active()) DungeonTrainNet.sendTo(player, EditorMirrorPlotPacket.empty());
    }

    public static void clearAll() {
        LAST.clear();
    }
}
