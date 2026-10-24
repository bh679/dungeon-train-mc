package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Clears {@link PerkClientState} on logout so a new world never inherits the last server's perks. */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PerkClientEvents {

    private PerkClientEvents() {}

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PerkClientState.clear();
    }
}
