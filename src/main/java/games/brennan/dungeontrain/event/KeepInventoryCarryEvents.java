package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.player.DifficultyPartition;
import games.brennan.dungeontrain.player.PendingInventory;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

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
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class KeepInventoryCarryEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private KeepInventoryCarryEvents() {}

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!PendingInventory.isPresentFor(player.getUUID())) {
            return;
        }
        // Each difficulty is its own profile (see DifficultyPartition) — a carry captured on one
        // difficulty must never land on another. Normally moot (the new world inherits the difficulty),
        // so a mismatch means something changed it in between; drop the carry rather than leak it.
        if (!PendingInventory.matchesPartition(player)) {
            LOGGER.info("[DungeonTrain] keepInventory carry discarded: captured on difficulty {}, "
                    + "logging in on {}", PendingInventory.capturedPartition(),
                DifficultyPartition.keyFor(player.level()));
            PendingInventory.clear();
            return;
        }
        PendingInventory.restore(player);
    }
}
