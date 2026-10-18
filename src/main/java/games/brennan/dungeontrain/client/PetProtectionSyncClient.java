package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PetProtectionSyncPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;

/**
 * Client-side sender for the "protect my pets" preference. Pushes
 * {@link ClientDisplayConfig#isProtectOwnPetsEnabled()} to the server on login, and again from
 * {@link ClientDisplayConfig#setProtectOwnPets} whenever the player toggles it mid-session.
 *
 * <p>Mirrors {@link BookAuthorChatSyncClient}, no-throw around the send. A failed sync is not a
 * hole: the server's mirror defaults to ON, which is also this option's default.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PetProtectionSyncClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PetProtectionSyncClient() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        sendIfConnected();
    }

    /** Push the current preference to the server now, so a mid-world toggle applies to the next hit. */
    public static void syncNow() {
        sendIfConnected();
    }

    private static void sendIfConnected() {
        try {
            if (Minecraft.getInstance().getConnection() == null) return;
            DungeonTrainNet.sendToServer(
                new PetProtectionSyncPacket(ClientDisplayConfig.isProtectOwnPetsEnabled()));
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] pet protection sync to server failed: {}", t.toString());
        }
    }
}
