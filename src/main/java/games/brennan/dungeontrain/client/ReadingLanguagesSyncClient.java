package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.ReadingLanguagesSyncPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;

/**
 * Pushes this client's reading languages to the server — on login, and when the Book languages page
 * closes — so the books this player finds come as the mix they asked for. See
 * {@link games.brennan.dungeontrain.event.ReadingLanguagesMirror}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ReadingLanguagesSyncClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ReadingLanguagesSyncClient() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        syncNow();
    }

    public static void syncNow() {
        try {
            if (Minecraft.getInstance().getConnection() == null) return;
            DungeonTrainNet.sendToServer(new ReadingLanguagesSyncPacket(ClientDisplayConfig.gameLanguageWeight(),
                    ClientDisplayConfig.readingLanguages(ClientLanguage.selected())));
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] reading-languages sync to server failed: {}", t.toString());
        }
    }
}
