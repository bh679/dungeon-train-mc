package games.brennan.dungeontrain.platform.event;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Loader-neutral form of NeoForge's {@code BlockDropsEvent}: fired on the server
 * thread with the item-entity drops a broken block will spawn. Not cancellable by
 * DT. The {@code drops} list is the event's LIVE mutable list — DT's handler edits
 * it in place (removing/adding entries), so the reference is passed through and
 * mutations propagate exactly as before. All DT handlers NORMAL.
 *
 * @param level   the server level (matches {@code getLevel()})
 * @param pos     the broken block position (matches {@code getPos()})
 * @param state   the broken block state (matches {@code getState()})
 * @param breaker the breaking entity, may be null (matches {@code getBreaker()})
 * @param drops   the live, mutable drop list (matches {@code getDrops()})
 */
@FunctionalInterface
public interface DtBlockDropsCallback {
    void onBlockDrops(ServerLevel level, BlockPos pos, BlockState state, Entity breaker, List<ItemEntity> drops);
}
