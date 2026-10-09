package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.block.entity.LiveAntennaBlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.minecraft.client.Minecraft;

/** Hands the (common) antenna block entity its client-only video source; tidies up on logout. */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class LiveFeedClientInit {

    private LiveFeedClientInit() {}

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        LiveAntennaBlockEntity.setClientSource(() -> LiveFeedSource.MAIN);
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
