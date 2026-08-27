package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.BookAuthorChatSyncPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;

/**
 * Client-side sender for the book-author burn-chat preference. Pushes
 * {@link ClientDisplayConfig#isBookAuthorBurnChatEnabled()} to the server on login, and again from
 * {@link ClientDisplayConfig#setBookAuthorBurnChat} whenever the player toggles it mid-session.
 *
 * <p>Mirrors {@link PoliticalFilterSyncClient}: a client-scope preference the server cannot read for
 * itself, no-throw around the send. A failed sync is not a hole — the server's mirror defaults to
 * OFF, which is also this option's default, so the worst case is a missing line, never chat the
 * player did not ask for.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BookAuthorChatSyncClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BookAuthorChatSyncClient() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        sendIfConnected();
    }

    /** Push the current preference to the server now, so a mid-world toggle applies to the next burn. */
    public static void syncNow() {
        sendIfConnected();
    }

    private static void sendIfConnected() {
        try {
            if (Minecraft.getInstance().getConnection() == null) return;
            DungeonTrainNet.sendToServer(
                new BookAuthorChatSyncPacket(ClientDisplayConfig.isBookAuthorBurnChatEnabled()));
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] book-author chat sync to server failed: {}", t.toString());
        }
    }
}
