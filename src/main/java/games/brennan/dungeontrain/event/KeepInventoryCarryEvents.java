package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.player.PendingInventory;
import net.minecraft.server.level.ServerPlayer;

/**
 * Restores the inventory + experience carried across a Dungeon Train "next
 * world" transition. When {@code keepInventory} is on,
 * {@link games.brennan.dungeontrain.client.DeathScreenLayoutHandler#launchWorld}
 * snapshots the player into {@link PendingInventory} before tearing down the old
 * world; this handler re-applies that snapshot on the new world's first login.
 *
 * <p>One-shot and UUID-matched: {@link PendingInventory#restore} clears the
 * holder after applying, so a later normal login restores nothing. On a
 * dedicated server the holder is never populated, so this is a no-op.</p>
 *
 * <p>Runs at login, before the deferred (~0.5&nbsp;s) welcome-book lightning
 * strike in {@link StartingBookEvents} fires, so the restored items are in place
 * first and the book lands in a free slot (or on the ground) without clobbering
 * them.</p>
 */
public final class KeepInventoryCarryEvents {

    private KeepInventoryCarryEvents() {}

        public static void onPlayerLogin(net.minecraft.world.entity.player.Player joinedPlayer) {
        if (!(joinedPlayer instanceof ServerPlayer player)) {
            return;
        }
        if (PendingInventory.isPresentFor(player.getUUID())) {
            PendingInventory.restore(player);
        }
    }
}
