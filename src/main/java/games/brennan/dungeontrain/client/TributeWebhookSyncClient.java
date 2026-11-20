package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.TributeWebhookClientConfig;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.TributeWebhookSyncPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;

/**
 * Sends this player's Tribute webhooks ({@link TributeWebhookClientConfig}) to the server on login, and
 * again when they save or remove it in Options ({@link #syncNow()}). Mirrors {@link ContentModeSyncClient}.
 * Never logs the link.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TributeWebhookSyncClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private TributeWebhookSyncClient() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        syncNow();
    }

    /** Push the current links (possibly none) to the server now. No-op when not connected. No-throw. */
    public static void syncNow() {
        try {
            if (Minecraft.getInstance().getConnection() == null) return;
            DungeonTrainNet.sendToServer(new TributeWebhookSyncPacket(TributeWebhookClientConfig.urls()));
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] tribute webhook sync to server failed: {}", t.getClass().getSimpleName());
        }
    }
}
