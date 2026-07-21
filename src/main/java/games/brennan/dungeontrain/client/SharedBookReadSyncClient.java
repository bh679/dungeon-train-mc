package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.SharedBookReadSyncPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side sender for the player's GLOBAL community-book read history. On login it reads
 * {@link ClientDisplayConfig#readSharedIds()} (the client-authoritative, cross-world read set persisted in
 * {@code dungeontrain-client.toml}) and syncs the whole set to the server
 * ({@link SharedBookReadSyncPacket}) so the shared-book loot selector can prefer books this player hasn't
 * read — the fallback used when the relay can't personalise the pool (older relay, no consent, offline).
 *
 * <p>Mirrors {@link NetworkConsentSyncClient}: the read history is a CLIENT-scope config only the client can
 * read, so a login sync is the only way the server learns it. Mid-session additions are pushed
 * incrementally by {@link BookReadClientEvents} as each community book is finished. No-throw around the
 * send — a failure just leaves the server's mirror as-is (the relay path still works).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class SharedBookReadSyncClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    private SharedBookReadSyncClient() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        sendReadSetIfConnected();
    }

    /** Read the client's global shared-book read set and, if connected, sync the full set to the server. No-throw. */
    private static void sendReadSetIfConnected() {
        try {
            if (Minecraft.getInstance().getConnection() == null) return;
            List<Integer> ids = new ArrayList<>(ClientDisplayConfig.readSharedIds());
            if (ids.isEmpty()) return; // nothing read yet — server mirror starts empty, no need to send
            DungeonTrainNet.sendToServer(new SharedBookReadSyncPacket(ids));
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] shared-book read sync to server failed: {}", t.toString());
        }
    }
}
