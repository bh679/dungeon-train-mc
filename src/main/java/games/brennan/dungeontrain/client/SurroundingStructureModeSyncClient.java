package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.SurroundingStructureModePacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;

/**
 * Client-side sender for the Surrounding Structures render-mode config. On login it reads
 * {@link ClientDisplayConfig#effectiveSurroundingStructureModes()} and syncs it to the server
 * ({@link SurroundingStructureModePacket}), which seeds
 * {@link games.brennan.dungeontrain.editor.surrounding.SurroundingStructureManager}'s per-player
 * default for the session. Directly mirrors {@link ContentModeSyncClient}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class SurroundingStructureModeSyncClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private SurroundingStructureModeSyncClient() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        syncNow();
    }

    /** Push the client's current surrounding-structure config to the server. No-op when not connected. No-throw. */
    public static void syncNow() {
        try {
            if (Minecraft.getInstance().getConnection() == null) return;
            DungeonTrainNet.sendToServer(
                new SurroundingStructureModePacket(ClientDisplayConfig.effectiveSurroundingStructureModes()));
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] surrounding-structure mode sync to server failed: {}", t.toString());
        }
    }
}
