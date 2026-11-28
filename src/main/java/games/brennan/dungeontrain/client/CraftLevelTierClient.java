package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Client mirror of the Craft Level Stats level tier ({@code CraftLevelTierPacket}); 0 until sent. */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class CraftLevelTierClient {

    private static volatile int levelTier = 0;

    private CraftLevelTierClient() {}

    public static void set(int tier) {
        levelTier = Math.max(0, tier);
    }

    public static int levelTier() {
        return levelTier;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        levelTier = 0;
    }
}
