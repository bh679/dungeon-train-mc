package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PoliticalFilterSyncPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;

/**
 * Client-side sender for the Political Filter preference. Pushes the RESOLVED preference
 * ({@link PoliticalFilterPrefs#isEnabled()}) to the server on login, and again from
 * {@link PoliticalFilterPrefs#answer} whenever the player changes it mid-session.
 *
 * <p>Mirrors {@link NetworkConsentSyncClient}: a client-scope preference the server cannot read for
 * itself, no-throw around the send. A failed sync is not a hole — the server falls back to the same
 * locale-derived default the client applies to an unanswered prompt (see
 * {@link games.brennan.dungeontrain.event.PoliticalFilterMirror}), so the filter stays on for the
 * players it exists for.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PoliticalFilterSyncClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PoliticalFilterSyncClient() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        sendIfConnected();
    }

    /**
     * Push the current preference to the server now, so a mid-world toggle takes effect on the next
     * book the player picks up rather than at the next login. No-op when not connected. No-throw.
     */
    public static void syncNow() {
        sendIfConnected();
    }

    private static void sendIfConnected() {
        try {
            if (Minecraft.getInstance().getConnection() == null) return;
            DungeonTrainNet.sendToServer(new PoliticalFilterSyncPacket(PoliticalFilterPrefs.isEnabled()));
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] political-filter sync to server failed: {}", t.toString());
        }
    }
}
