package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.TvSpawnPrefPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Tells the server whether this player lets spawned TVs come on ({@link LiveFeedClientConfig#tvsSpawnOn}),
 * on login and whenever the answer changes (the Options row, or a hand-edited config) — the same shape
 * as {@link NetworkConsentSyncClient}. See {@code compat.vista.TvSpawnPower}.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TvSpawnPrefSyncClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How often (client ticks) a mid-world change is looked for. */
    private static final int CHECK_TICKS = 20;
    /** What the server was last told this connection; null when not connected. */
    @Nullable private static volatile Boolean lastSent;
    private static int ticks;

    private TvSpawnPrefSyncClient() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        send();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (++ticks % CHECK_TICKS != 0 || lastSent == null) return;
        if (LiveFeedClientConfig.tvsSpawnOn() != lastSent) send();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        lastSent = null;
    }

    private static void send() {
        try {
            if (Minecraft.getInstance().getConnection() == null) return;
            boolean allow = LiveFeedClientConfig.tvsSpawnOn();
            DungeonTrainNet.sendToServer(new TvSpawnPrefPacket(allow));
            lastSent = allow;
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] TV spawn preference sync failed: {}", t.toString());
        }
    }
}
