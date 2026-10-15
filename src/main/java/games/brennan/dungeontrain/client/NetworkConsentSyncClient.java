package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.NetworkConsentSyncPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;
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
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class NetworkConsentSyncClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How often (client ticks) a mid-world consent change is looked for. */
    private static final int CHECK_TICKS = 20;
    /** What the server was last told this connection; null when not connected. */
    @Nullable private static volatile Boolean lastSent;
    private static int ticks;

    private NetworkConsentSyncClient() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        sendConsentIfConnected();
    }

    /**
     * Consent changed mid-world (the consent card — first launch, Options → Content, the Live Eye's
     * chat link — or DP's {@code /chatconnect}): the server hears it now rather than at the next
     * login, so e.g. a Live Eye refused for internet-off goes on as soon as they accept.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (++ticks % CHECK_TICKS != 0 || lastSent == null) return;
        if (DiscordPresenceClientConfig.isGranted() != lastSent) syncNow();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        lastSent = null;
    }

    /**
     * Push the current DP network-consent state to the server now (e.g. after the player flips it in a
     * settings screen mid-world), so the server-side gate updates without waiting for the next login.
     * No-op when not connected. No-throw.
     */
    public static void syncNow() {
        sendConsentIfConnected();
    }

    /** Read the client's DP network consent and, if connected to a server, sync it. No-throw. */
    private static void sendConsentIfConnected() {
        try {
            if (Minecraft.getInstance().getConnection() == null) return;
            boolean granted = DiscordPresenceClientConfig.isGranted();
            DungeonTrainNet.sendToServer(new NetworkConsentSyncPacket(granted));
            lastSent = granted;
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] network-consent sync to server failed: {}", t.toString());
        }
    }
}
