package games.brennan.dungeontrain.platform.event;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Loader-neutral form of NeoForge's {@code BlockEvent.BreakEvent}: fired on the
 * server thread when a player breaks a block. NeoForge's event is cancellable, but
 * no DT handler cancels it, so this callback is {@code void}; it carries the
 * current {@code isCanceled()} flag because DT's handlers short-circuit on it. All
 * DT handlers were NORMAL priority and none used {@code receiveCanceled}, so the
 * bridge fires them under a single NORMAL subscription (a higher-priority other-mod
 * cancel skips the bridge, exactly as before).
 *
 * @param level    the level (matches {@code getLevel()})
 * @param player   the breaking player (matches {@code getPlayer()})
 * @param pos      the block position (matches {@code getPos()})
 * @param state    the block state broken (matches {@code getState()})
 * @param canceled the event's current cancel flag (matches {@code isCanceled()})
 */
@FunctionalInterface
public interface DtBlockBreakCallback {
    void onBlockBreak(LevelAccessor level, Player player, BlockPos pos, BlockState state, boolean canceled);
}
