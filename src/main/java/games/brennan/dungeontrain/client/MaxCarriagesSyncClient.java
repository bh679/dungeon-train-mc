package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.MaxCarriagesSyncPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;

/**
 * Client-side sender for this player's personal carriage ceiling. On login it reads
 * {@link ClientDisplayConfig#getMaxCarriages()} and syncs it to the server
 * ({@link MaxCarriagesSyncPacket}) so the per-player rolling window can honour it — see
 * {@link games.brennan.dungeontrain.event.MaxCarriagesMirror}.
 *
 * <p>Directly mirrors {@link ContentModeSyncClient}. A failed send leaves the server treating this
 * player as uncapped, i.e. exactly the pre-feature behaviour — the failure mode is "my train is as
 * long as my render distance says", not a broken world. {@link #syncNow()} is called from the Options
 * slider so a mid-world change takes effect without a relog.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class MaxCarriagesSyncClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private MaxCarriagesSyncClient() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        sendMaxIfConnected();
    }

    /**
     * Push the current ceiling to the server now — used when the player moves the slider mid-world, so
     * the window resizes immediately. No-op when not connected. No-throw.
     */
    public static void syncNow() {
        sendMaxIfConnected();
    }

    /** Read the client's ceiling and, if connected to a server, sync it. No-throw. */
    private static void sendMaxIfConnected() {
        try {
            if (Minecraft.getInstance().getConnection() == null) return;
            DungeonTrainNet.sendToServer(new MaxCarriagesSyncPacket(ClientDisplayConfig.getMaxCarriages()));
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] max-carriages sync to server failed: {}", t.toString());
        }
    }
}
