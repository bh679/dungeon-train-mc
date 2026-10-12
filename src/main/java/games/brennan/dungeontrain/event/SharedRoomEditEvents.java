package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.SharedCarriageChangeFilter;
import games.brennan.dungeontrain.train.SharedRoomRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Notices a player's block edits inside a drifting dimensional carriage and queues them for upload —
 * the room half of what {@code SableBlockChangeGuardMixin} does for a carriage.
 *
 * <p>A room's blocks are ordinary world blocks under the floor, so Sable's per-block hook never
 * sees them; the usual place / break events do, exactly as {@link PortalEditEvents} relies on for a
 * twin corridor. Which changes count is {@link SharedCarriageChangeFilter}'s call, as for a
 * carriage. What this does not catch: pistons, explosions and fluid — a change no player made.
 * Those reach a carriage through Sable's hook and reach a room only when a player's own edit next
 * uploads the cell; documented, and deliberate for a first version.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SharedRoomEditEvents {

    private SharedRoomEditEvents() {}

    // LOWEST on all three: a handler at normal priority that cancels the edit (builder protection,
    // a portal seal) has run by then, so a cancelled change is never queued as an upload.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockSnapshot before = event.getBlockSnapshot();
        noteChange(level, event.getPos(), before == null ? Blocks.AIR.defaultBlockState() : before.getState(),
                event.getPlacedBlock());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onMultiBlockPlace(BlockEvent.EntityMultiPlaceEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        for (BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
            BlockPos pos = snapshot.getPos();
            noteChange(level, pos, snapshot.getState(), level.getBlockState(pos));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // Fires before the block is removed; the flush re-reads the cell later and finds air.
        noteChange(level, event.getPos(), event.getState(), Blocks.AIR.defaultBlockState());
    }

    private static void noteChange(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (SharedRoomRegistry.isEmpty()) return; // the overwhelming majority of edits short-circuit here
        SharedRoomRegistry.Instance inst = SharedRoomRegistry.byWorldPos(level, pos);
        if (inst == null || inst.isCulled()) return;
        if (!SharedCarriageChangeFilter.isBuildChange(oldState, newState, inst.isOnRelay())) return;
        inst.enqueue(inst.offsetOf(pos));
        inst.markBlockEdited();
    }
}
