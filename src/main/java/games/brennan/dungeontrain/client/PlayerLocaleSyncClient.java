package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PlayerLocaleSyncPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;

/**
 * Client-side sender for the player's selected game language. On login it reads
 * {@code Minecraft.getInstance().options.languageCode} (the Options → Language selection, e.g.
 * {@code "en_us"}) and syncs it to the server ({@link PlayerLocaleSyncPacket}) so the server can stamp
 * player-written content (community shared books, lectern letters) with the author's language for
 * language-matched delivery (see {@link games.brennan.dungeontrain.event.PlayerLocaleMirror}).
 *
 * <p>Mirrors how {@link NetworkConsentSyncClient} pushes its consent on
 * {@link ClientPlayerNetworkEvent.LoggingIn}. The language is a CLIENT-scope setting only the client
 * can read, so a login sync is the only way the server learns it. No-throw around the send — a failure
 * to sync simply leaves the server with no language tag (content stored untagged) rather than crashing
 * the login.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PlayerLocaleSyncClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PlayerLocaleSyncClient() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        sendLocaleIfConnected();
    }

    /** Read the client's selected language and, if connected to a server, sync it. No-throw. */
    private static void sendLocaleIfConnected() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() == null || mc.options == null) return;
            String lang = mc.options.languageCode;
            DungeonTrainNet.sendToServer(new PlayerLocaleSyncPacket(lang == null ? "" : lang));
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] player-locale sync to server failed: {}", t.toString());
        }
    }
}
