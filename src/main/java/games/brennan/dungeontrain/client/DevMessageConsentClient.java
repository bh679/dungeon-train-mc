package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.ConsentSyncPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;

/**
 * Client-side, persistent owner of the dev-message-consent state (see
 * {@link games.brennan.dungeontrain.event.DevMessageConsent} for the server side). The client owns
 * it because it must survive a world reload — in single-player the client process stays alive while
 * only the integrated server restarts — and because one of the window-resetting sources, the
 * main-menu chat, is client-only.
 *
 * <p>State lives in memory (survives world transitions within one launch) and is persisted to
 * {@code dungeontrain-client.toml} (survives a full restart) via {@link ClientDisplayConfig}. The
 * "last message to dev" window resets on:</p>
 * <ul>
 *   <li>any in-game chat line the player sends <em>after</em> consent was granted — observed here
 *       via {@link ClientChatEvent}; the server slides its own mirror independently from the same
 *       chat action, so no packet is sent for this case;</li>
 *   <li>any main-menu chat send to the dev — {@link #noteMenuMessageToDev()} (called from
 *       {@code ChatOutbox}); if connected to a world this also syncs the server mirror.</li>
 * </ul>
 *
 * <p>On login the persisted state is sent to the server ({@link ConsentSyncPacket}); when the
 * server grants consent it pushes the new state back ({@link #applyUpdate}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DevMessageConsentClient {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Disk writes are coalesced to this granularity — far finer than the 20-minute window. */
    private static final long PERSIST_MIN_INTERVAL_MS = 60_000L;

    private static boolean loaded;
    private static boolean granted;
    private static double grantSession;
    private static long lastMsgToDevMs;
    /** Wall-clock of the last time {@link #lastMsgToDevMs} was flushed to config; debounces disk writes. */
    private static long lastPersistedMs;

    private DevMessageConsentClient() {}

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        granted = ClientDisplayConfig.isDevConsentGranted();
        grantSession = ClientDisplayConfig.getDevConsentGrantSession();
        lastMsgToDevMs = (long) ClientDisplayConfig.getDevConsentLastMsgToDev();
    }

    /** Server pushed authoritative state (consent granted in-game): store and persist it. */
    public static synchronized void applyUpdate(boolean newGranted, double newGrantSession, double newLastMsgToDevMs) {
        ensureLoaded();
        granted = newGranted;
        grantSession = newGrantSession;
        lastMsgToDevMs = (long) newLastMsgToDevMs;
        persist(/*force*/ true);
    }

    /** A main-menu chat message was sent to the dev: slide the window and (if in a world) sync the server. */
    public static synchronized void noteMenuMessageToDev() {
        ensureLoaded();
        lastMsgToDevMs = System.currentTimeMillis();
        persist(/*force*/ false);
        sendSyncIfConnected();
    }

    @SubscribeEvent
    public static void onClientChat(ClientChatEvent event) {
        ensureLoaded();
        // Only counts once consent has been approved (per the consent rule). The server slides its
        // own mirror from the same ServerChatEvent, so this is a client-local persist only.
        if (granted) {
            lastMsgToDevMs = System.currentTimeMillis();
            persist(/*force*/ false);
        }
    }

    public static void onLoggingIn() {
        ensureLoaded();
        sendSyncIfConnected();
    }

    private static void sendSyncIfConnected() {
        try {
            if (Minecraft.getInstance().getConnection() == null) return;
            DungeonTrainNet.sendToServer(new ConsentSyncPacket(granted, grantSession, (double) lastMsgToDevMs));
        } catch (Throwable t) {
            LOGGER.debug("Dev-message consent: sync to server failed: {}", t.toString());
        }
    }

    /** Persist to config, coalescing frequent window slides unless {@code force} (state changed). */
    private static void persist(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastPersistedMs < PERSIST_MIN_INTERVAL_MS) return;
        lastPersistedMs = now;
        ClientDisplayConfig.setDevConsentState(granted, grantSession, (double) lastMsgToDevMs);
    }
}
