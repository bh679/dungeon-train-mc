package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.NetworkConsentSyncPacket;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * Client-side sender for the Discord Presence network-access consent. On login it reads
 * {@link DiscordPresenceClientConfig#isGranted()} (the answer to DP's one-time "use the internet?"
 * prompt) and syncs it to the server ({@link NetworkConsentSyncPacket}) so the server can gate the
 * community shared-books upload (see {@link games.brennan.dungeontrain.event.NetworkConsentMirror} and
 * {@link games.brennan.dungeontrain.event.SharedBookGate}).
 *
 * <p>Mirrors how {@link DevMessageConsentClient} pushes its consent on
 * {@link ClientPlayerNetworkEvent.LoggingIn}. The consent is a CLIENT-scope config only the client
 * can read, so a login sync is the only way the server learns it. No-throw around the send — a
 * failure to sync simply leaves the server fail-closed (contribution disabled) rather than crashing
 * the login.</p>
 */
public final class NetworkConsentSyncClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private NetworkConsentSyncClient() {}

    public static void onLoggingIn() {
        sendConsentIfConnected();
    }

    /** Read the client's DP network consent and, if connected to a server, sync it. No-throw. */
    private static void sendConsentIfConnected() {
        try {
            if (Minecraft.getInstance().getConnection() == null) return;
            DungeonTrainNet.sendToServer(new NetworkConsentSyncPacket(DiscordPresenceClientConfig.isGranted()));
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] network-consent sync to server failed: {}", t.toString());
        }
    }
}
