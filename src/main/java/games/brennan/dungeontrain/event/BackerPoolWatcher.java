package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.narrative.AinBackerOverlay;
import games.brennan.dungeontrain.narrative.BackerPool;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Keeps the backer name pool fetched and applied to AdventureItemNames for the life of a server.
 *
 * <p>Refreshed on {@link ServerStartedEvent} (so a single-player world picks the pool up as it
 * loads) and on player login (so a long-running dedicated server picks up new backers without a
 * restart, and so a world that started while the relay was down recovers on the next join). The
 * fetch itself is off-thread and drops overlapping calls, so a busy login burst costs one request.
 *
 * <p>On {@link ServerStoppedEvent} the overlay is stood down. AIN's registry is process-global
 * static state rather than per-server, so leaving DT's entries installed would leak a previous
 * world's backer pool into the next world loaded in the same client session — including into a
 * world where the player has the feature turned off.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BackerPoolWatcher {

    private BackerPoolWatcher() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        BackerPool.refresh();
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        BackerPool.refresh();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        AinBackerOverlay.clear();
        BackerPool.reset();
    }
}
