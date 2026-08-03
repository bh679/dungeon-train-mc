package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.ContentModeSyncPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;

/**
 * Client-side sender for the Adult / Kid {@link games.brennan.dungeontrain.config.ContentMode}. On
 * login it reads {@link ClientDisplayConfig#getContentMode()} and syncs it to the server
 * ({@link ContentModeSyncPacket}) so the per-player content gates can act on it — see
 * {@link games.brennan.dungeontrain.event.ContentModeMirror}.
 *
 * <p>Directly mirrors {@link NetworkConsentSyncClient}. The one difference that matters: a failed send
 * here leaves the server treating this player as KID (the mirror's fail-safe), so the failure mode is
 * "sees less than they chose", not "sees more". {@link #syncNow()} is called from the Options row so a
 * mid-world change takes effect without a relog.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ContentModeSyncClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ContentModeSyncClient() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        sendModeIfConnected();
    }

    /**
     * Push the current content mode to the server now — used when the player changes it mid-world from
     * Options, so the server-side gates follow immediately. No-op when not connected. No-throw.
     */
    public static void syncNow() {
        sendModeIfConnected();
    }

    /** Read the client's content mode and, if connected to a server, sync it. No-throw. */
    private static void sendModeIfConnected() {
        try {
            if (Minecraft.getInstance().getConnection() == null) return;
            DungeonTrainNet.sendToServer(new ContentModeSyncPacket(ClientDisplayConfig.getContentMode()));
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] content-mode sync to server failed: {}", t.toString());
        }
    }
}
