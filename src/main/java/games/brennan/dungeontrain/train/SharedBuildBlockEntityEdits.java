package games.brennan.dungeontrain.train;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import games.brennan.dungeontrain.ship.sable.CarriagePlotResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.UUID;

/**
 * Queues an edit that changes a block entity but no block state — writing a sign, dyeing it,
 * waxing it — on whichever drifting build holds the cell.
 *
 * <p>Every other edit reaches the relay through a block change: Sable's choke point for a carriage
 * ({@code SableBlockChangeGuardMixin}), the place/break events for a room ({@code SharedRoomEditEvents}).
 * A sign's text is not one. The sign is queued when it is placed, the flusher captures it seconds
 * later while its writer is still on the sign screen, and the words that follow changed nothing
 * either path could see — so the build went up with a blank sign, and every later rider leased it
 * blank. Queuing the cell again here makes the next flush capture the text, which the relay then
 * screens like any other.</p>
 *
 * <p>No {@link SharedCarriageChangeFilter} check: new text is always a build change.</p>
 */
public final class SharedBuildBlockEntityEdits {

    private SharedBuildBlockEntityEdits() {}

    /** A block entity at {@code pos} changed its authored content. Server thread only. */
    public static void onBlockEntityEdited(ServerLevel level, BlockPos pos) {
        if (queueOnCarriage(level, pos)) return;
        queueOnRoom(level, pos);
    }

    private static boolean queueOnCarriage(ServerLevel level, BlockPos pos) {
        ServerSubLevel serverSub = CarriagePlotResolver.subLevelAt(level, new ChunkPos(pos));
        if (serverSub == null) return false;
        UUID subLevelId = serverSub.getUniqueId();
        if (!SharedCarriageRegistry.hasSubLevel(subLevelId)) return true;
        SharedCarriageRegistry.Instance inst =
                SharedCarriageRegistry.resolve(subLevelId, pos.getX(), pos.getY(), pos.getZ());
        if (inst == null || inst.isCulled()) return true;
        inst.enqueue(pos);
        inst.markBlockEdited();
        return true;
    }

    private static void queueOnRoom(ServerLevel level, BlockPos pos) {
        if (SharedRoomRegistry.isEmpty()) return;
        SharedRoomRegistry.Instance inst = SharedRoomRegistry.byWorldPos(level, pos);
        if (inst == null || inst.isCulled()) return;
        inst.enqueue(inst.offsetOf(pos));
        inst.markBlockEdited();
    }
}
