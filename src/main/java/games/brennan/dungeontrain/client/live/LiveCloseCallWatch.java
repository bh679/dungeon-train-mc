package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Channel;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.GiftOffer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * After a close call ({@code net/LiveCloseCallPacket}): the streamer may gift this player the Live Eye,
 * and the offer arrives in the relay chat's {@code gifts[]}, which only {@link LiveChatBridge} reads, and
 * only while streaming or wearing a TV. For the watch window this polls the same {@code GET /live/chat}
 * every {@link #POLL_MS}, keeps nothing but gift offers made out to this player, and hands them to
 * {@link LiveGiftClient#onOffer}. The stream's chat itself is never shown. Stands aside while the bridge is
 * connected (it delivers the offer itself).
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveCloseCallWatch {

    private static final long POLL_MS = 3000;

    private static volatile long untilMs;
    private static volatile long cursor = -1;
    private static volatile long lastPollMs;
    private static final AtomicBoolean inFlight = new AtomicBoolean();

    private LiveCloseCallWatch() {}

    /** Watch for {@code seconds} from now. Client thread. */
    public static void start(int seconds) {
        long until = System.currentTimeMillis() + seconds * 1000L;
        if (until > untilMs) untilMs = until;
    }

    /** Whether a close call is still waiting on the streamer (an offer now is a close-call offer). */
    public static boolean watching() {
        return System.currentTimeMillis() < untilMs;
    }

    /** Pure: whether a poll is due. */
    static boolean shouldPoll(boolean watching, boolean bridgeConnected, long lastMs, long now) {
        return watching && !bridgeConnected && (now < lastMs || now - lastMs >= POLL_MS);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (mc.player == null || mc.getUser() == null) return;
        if (!shouldPoll(watching(), LiveChatBridge.connected(), lastPollMs, now)) return;
        if (!inFlight.compareAndSet(false, true)) return;
        lastPollMs = now;
        UUID me = mc.getUser().getProfileId();
        String myId = me.toString().replace("-", "");
        LiveFeedClient.chat(LiveFeedClient.viewerBase(Channel.LIVE), cursor).thenAcceptAsync(r -> {
            inFlight.set(false);
            if (!watching()) return;
            cursor = LiveChatRules.nextCursor(cursor, LiveFeedClient.parseChat(r).seq());
            for (GiftOffer offer : LiveFeedClient.parseGiftNotices(r).gifts()) {
                if (offer.target().equalsIgnoreCase(myId)) LiveGiftClient.onOffer(offer, Channel.LIVE);
            }
        }, mc).exceptionally(t -> {
            inFlight.set(false);
            return null;
        });
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        untilMs = 0;
        cursor = -1;
    }
}
