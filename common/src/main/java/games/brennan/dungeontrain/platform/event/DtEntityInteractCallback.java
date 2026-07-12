package games.brennan.dungeontrain.platform.event;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Loader-neutral form of NeoForge's {@code PlayerInteractEvent.EntityInteract}:
 * fired when a player right-clicks an entity. Cancellable upstream, but DT's sole
 * handler (a befriend-advancement observer) does not cancel, so this callback is
 * {@code void}. NORMAL priority.
 *
 * @param player the interacting player (matches {@code getEntity()})
 * @param level  the level (matches {@code getLevel()})
 * @param item   the held stack (matches {@code getItemStack()})
 * @param target the right-clicked entity (matches {@code getTarget()})
 */
@FunctionalInterface
public interface DtEntityInteractCallback {
    void onEntityInteract(Player player, Level level, ItemStack item, Entity target);
}
