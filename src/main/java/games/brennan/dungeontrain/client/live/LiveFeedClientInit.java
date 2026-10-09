package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.vista.LiveBroadcastSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.minecraft.client.Minecraft;

/** Hands the (common) live broadcast source its client-only video source; tidies up on logout. */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class LiveFeedClientInit {

    private LiveFeedClientInit() {}

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        LiveBroadcastSource.setClientSource(() -> LiveFeedSource.MAIN);
    }

    @EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
    public static final class Game {
        private Game() {}

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            Minecraft.getInstance().execute(() -> {
                LiveFeedSource.MAIN.dropSession();
                LiveStatusPoller.reset();
            });
        }
    }
}
