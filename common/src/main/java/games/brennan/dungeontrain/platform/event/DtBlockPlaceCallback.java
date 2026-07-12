package games.brennan.dungeontrain.platform.event;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Loader-neutral form of NeoForge's {@code BlockEvent.EntityPlaceEvent} (single-
 * block place): fired on the server thread when an entity places a block.
 * Cancellable upstream, but no DT handler cancels — void callback carrying
 * {@code isCanceled()} read-only. All DT handlers NORMAL. (NeoForge's
 * {@code EntityMultiPlaceEvent} — the multi-block variant — is NOT routed here; it
 * remains on the NeoForge bus because its {@code getReplacedBlockSnapshots()}
 * returns a loader-specific {@code BlockSnapshot} type.)
 *
 * @param entity      the placing entity (matches {@code getEntity()})
 * @param level       the level (matches {@code getLevel()})
 * @param placedBlock the placed block state (matches {@code getPlacedBlock()})
 * @param pos         the block position (matches {@code getPos()})
 * @param canceled    the event's current cancel flag (matches {@code isCanceled()})
 */
@FunctionalInterface
public interface DtBlockPlaceCallback {
    void onBlockPlace(Entity entity, LevelAccessor level, BlockState placedBlock, BlockPos pos, boolean canceled);
}
