package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.PortalEditMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * The twin half of hallway-portal edit mirroring: an edit to a twin corridor's blocks is copied into
 * the carriage it pairs with, so the two stay identical and the crossing stays invisible.
 *
 * <p>A twin's blocks are ordinary world blocks, so the usual place / break events reach them —
 * modelled on {@link games.brennan.dungeontrain.editor.EditorMirrorLiveHandler}, which does the same
 * job for the editor's mirror axes. The other direction is fed from Sable's per-block-change hook
 * instead, because a carriage's blocks live in a sub-level plot rather than the world; see
 * {@link PortalEditMirror}.</p>
 *
 * <p>The same events also tell {@link PortalCarriageEvents#markTouchedAt} which room copy a player
 * has changed, so the copy is remembered even when they never stood in it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalEditEvents {

    private PortalEditEvents() {}

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        PortalCarriageEvents.markTouchedAt(level, event.getPos());
        PortalEditMirror.onTwinBlockChanged(level, event.getPos(), event.getPlacedBlock());
    }

    @SubscribeEvent
    public static void onMultiBlockPlace(BlockEvent.EntityMultiPlaceEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // Every cell is already in the world by the time this fires, so read each back rather than
        // trusting a single placed state — the same approach EditorMirrorLiveHandler takes.
        for (BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
            BlockPos pos = snapshot.getPos();
            PortalCarriageEvents.markTouchedAt(level, pos);
            PortalEditMirror.onTwinBlockChanged(level, pos, level.getBlockState(pos));
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // Fires before the block is removed, so mirror air rather than reading the position back.
        PortalCarriageEvents.markTouchedAt(level, event.getPos());
        PortalEditMirror.onTwinBlockChanged(level, event.getPos(), Blocks.AIR.defaultBlockState());
    }

    /** Opening a chest, barrel or pot in a copy counts as changing it: its loot can leave. */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        PortalCarriageEvents.markTouchedAt(level, event.getPos());
    }
}
