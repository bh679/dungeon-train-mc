package games.brennan.dungeontrain.platform.event;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Loader-neutral form of NeoForge's {@code PlayerInteractEvent.RightClickItem}:
 * fired when a player right-clicks holding an item (not targeting a block/entity).
 * NeoForge's event is cancellable, but none of DT's five handlers cancel — they
 * are pure observers (book-read telemetry / narrative triggers) — so this callback
 * is {@code void}. All DT handlers were NORMAL priority.
 *
 * <p>(NeoForge's {@code RightClickBlock} sub-event is NOT bridged — it has cancelling
 * handlers that set an {@code InteractionResult} and one HIGH-priority handler, so
 * it stays on the NeoForge bus. See {@code NeoForgeServerEvents}.)</p>
 *
 * @param player the interacting player (matches {@code getEntity()})
 * @param level  the level (matches {@code getLevel()})
 * @param item   the held stack (matches {@code getItemStack()})
 */
@FunctionalInterface
public interface DtRightClickItemCallback {
    void onRightClickItem(Player player, Level level, ItemStack item);
}
