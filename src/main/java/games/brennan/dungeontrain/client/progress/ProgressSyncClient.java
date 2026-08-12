package games.brennan.dungeontrain.client.progress;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.ProgressAccountSyncPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;

/**
 * Client-side sender for this install's relay progress credential.
 *
 * <p>On login it obtains the credential (verifying with Mojang where possible — see
 * {@link ProgressAccountClient}) and hands it to the server, which is where the Ender Chest actually
 * lives and therefore where the sync has to run.</p>
 *
 * <p><b>Whose server matters.</b> The credential is a bearer secret: anything holding it can read and
 * overwrite the player's Ender Chest. So by default it is only sent when the player's OWN client owns
 * the server — single-player, or hosting a LAN world — where "sending" it never leaves the machine. A
 * remote dedicated server is opted in explicitly per-client
 * ({@code progressSyncOnDedicatedServers}), because trusting a stranger's server with that secret, and
 * letting the chest carry items between unrelated servers, is a decision the player should make rather
 * than inherit.</p>
 *
 * <p>Note the server-side config can't be read here — this runs before/independently of the server's
 * config sync — so the client's own copy of the flag governs sending, and the server independently
 * gates acting on it. Both must agree, which is the fail-closed arrangement.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ProgressSyncClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ProgressSyncClient() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        sendCredentialIfAllowed();
    }

    /** Obtain (or reuse) the credential and sync it to the server. No-op when not allowed. No-throw. */
    public static void sendCredentialIfAllowed() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getConnection() == null) return;
            if (!ownsServer(mc)) {
                LOGGER.debug("[DungeonTrain] progress: not syncing — this is a remote server and the "
                    + "credential stays on this machine.");
                return;
            }
            ProgressAccountClient.ensure().thenAccept(credential -> {
                if (credential == null || !credential.isUsable()) return;
                // Back onto the client thread before touching the connection.
                Minecraft client = Minecraft.getInstance();
                if (client == null) return;
                client.execute(() -> {
                    try {
                        if (client.getConnection() == null) return;
                        DungeonTrainNet.sendToServer(new ProgressAccountSyncPacket(credential.secret()));
                    } catch (Throwable t) {
                        LOGGER.debug("[DungeonTrain] progress: credential sync failed — {}", t.toString());
                    }
                });
            });
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] progress: credential sync could not start — {}", t.toString());
        }
    }

    /**
     * True when the credential may be sent: either this client owns the server (single-player or LAN
     * host — the secret never leaves the machine), or the player has explicitly opted in to trusting
     * remote servers with it. The server independently gates whether it acts on what it receives.
     */
    private static boolean ownsServer(Minecraft mc) {
        return mc.hasSingleplayerServer() || ClientDisplayConfig.isProgressSyncOnRemoteServers();
    }
}
