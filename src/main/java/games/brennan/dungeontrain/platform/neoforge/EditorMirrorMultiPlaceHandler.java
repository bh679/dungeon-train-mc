package games.brennan.dungeontrain.platform.neoforge;

import games.brennan.dungeontrain.DtCore;
import games.brennan.dungeontrain.editor.EditorMirrorLiveHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Root residue split out of {@code EditorMirrorLiveHandler}: the sole handler bound
 * to NeoForge's {@code BlockEvent.EntityMultiPlaceEvent}, whose
 * {@code getReplacedBlockSnapshots()} returns a loader-specific
 * {@link BlockSnapshot} list with no vanilla equivalent. This handler performs the
 * same guards the original did, unpacks the snapshots to plain {@link BlockPos}es,
 * and delegates to the loader-neutral
 * {@link EditorMirrorLiveHandler#onMultiBlockPlace(ServerPlayer, ServerLevel, List)}
 * — so all the mirroring logic stays in the (movable) game-logic class and only this
 * thin adapter remains loader-specific.
 *
 * <p>Not {@code Dist.CLIENT}-gated (server-thread block edits), matching the former
 * {@code @EventBusSubscriber(modid = ...)} on {@code EditorMirrorLiveHandler}.</p>
 */
@EventBusSubscriber(modid = DtCore.MOD_ID)
public final class EditorMirrorMultiPlaceHandler {

    private EditorMirrorMultiPlaceHandler() {}

    @SubscribeEvent
    public static void onMultiBlockPlace(BlockEvent.EntityMultiPlaceEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        List<BlockPos> positions = new ArrayList<>();
        for (BlockSnapshot snapshot : event.getReplacedBlockSnapshots()) {
            positions.add(snapshot.getPos());
        }
        EditorMirrorLiveHandler.onMultiBlockPlace(player, level, positions);
    }
}
