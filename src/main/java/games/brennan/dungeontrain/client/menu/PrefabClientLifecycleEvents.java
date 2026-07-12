package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * Clear cached prefab state when the player disconnects so the next
 * connection starts clean. Without this, ids from the previous server
 * persist into the next session and the creative tab shows stale entries
 * until a new sync packet arrives.
 */
public final class PrefabClientLifecycleEvents {

    private PrefabClientLifecycleEvents() {}

    public static void onLoggingOut() {
        PrefabTabState.clear();
    }
}
