package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.vista.LiveBroadcastSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.minecraft.client.Minecraft;

/**
 * Hands the (common) live broadcast source its client-only video source, registers the headpiece's
 * aerial predicate ({@link LiveHeadpieceAntenna}); tidies up on logout.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class LiveFeedClientInit {

    private LiveFeedClientInit() {}

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        LiveBroadcastSource.setClientSource(() -> LiveFeedSource.MAIN);
        // ItemProperties is not thread-safe; registration belongs on the main thread like the rest
        event.enqueueWork(LiveHeadpieceAntenna::register);
    }

    @EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
    public static final class Game {
        private Game() {}

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            Minecraft.getInstance().execute(() -> {
                LiveFeedSource.MAIN.dropAll();
                LiveStatusPoller.reset();
                LiveHeadpieceAntenna.reset();
            });
        }
    }
}
