package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.vista.TvItems;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatLine;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatPage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The stream's Discord chat in this client's chat, and this client's chat back to it.
 *
 * <p>While {@link LiveChatRules#granted} holds — the Live Chat switch on, not Kid mode, and this client
 * either streaming or wearing a TV with someone on air — the bridge polls the relay's
 * {@code GET /live/chat} every {@link #POLL_MS} (single-flight, cursor = the relay's seq; the first
 * poll takes the head so joining never replays history) and prints each new line as
 * {@code [Live] Name: text}. Every non-command line typed in chat goes out through
 * {@code POST /live/chat/send}, at most one a second, fire-and-forget. Which relay: the streamer's own
 * (the channel it claimed); a viewer's, whatever channel its TVs show ({@link LiveStatusPoller#channel()}).</p>
 *
 * <p>Failures are quiet: a bad poll is retried next interval, a failed send is logged once per session.
 * Nothing here touches the stream itself.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveChatBridge {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long POLL_MS = 3000;
    private static final long SEND_GAP_MS = 1000;

    private static volatile boolean connected;
    private static volatile long cursor = -1;
    private static volatile long lastPollMs;
    private static volatile long lastSendMs;
    private static volatile boolean sendFailureLogged;
    private static final AtomicBoolean inFlight = new AtomicBoolean();

    private LiveChatBridge() {}

    /** Whether this client is in the chat right now. */
    public static boolean connected() {
        return connected;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            if (connected) reset();
            return;
        }
        boolean streaming = LiveStreamController.get().streaming();
        boolean wearingTv = !streaming && TvItems.isTv(mc.player.getItemBySlot(EquipmentSlot.HEAD));
        boolean setting = LiveFeedClientConfig.liveChat();
        boolean kid = ClientDisplayConfig.isKidMode();
        // A worn TV keeps the status fresh even with the pinned viewer hidden; the poll also counts
        // the wearer as a viewer, which they are — they are reading the stream's chat.
        boolean someoneLive = wearingTv && setting && !kid && LiveStatusPoller.wanted().live();
        boolean granted = LiveChatRules.granted(setting, kid, streaming, wearingTv, someoneLive);
        if (!granted) {
            if (connected) disconnect();
            return;
        }
        if (!connected) connect(mc);
        long now = System.currentTimeMillis();
        if (now - lastPollMs < POLL_MS || !inFlight.compareAndSet(false, true)) return;
        lastPollMs = now;
        LiveFeedClient.chat(base(streaming), cursor).thenAcceptAsync(r -> {
            inFlight.set(false);
            if (connected) deliver(mc, LiveFeedClient.parseChat(r));
        }, mc).exceptionally(t -> {
            inFlight.set(false);
            return null;
        });
    }

    @SubscribeEvent
    public static void onClientChat(ClientChatEvent event) {
        if (!connected) return;
        String content = LiveChatRules.outbound(event.getMessage());
        if (content == null) return;
        long now = System.currentTimeMillis();
        if (now - lastSendMs < SEND_GAP_MS) return;
        lastSendMs = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getUser() == null) return;
        UUID me = mc.getUser().getProfileId();
        String name = mc.getUser().getName();
        LiveFeedClient.chatSend(base(LiveStreamController.get().streaming()), me, name, content).thenAccept(r -> {
            if (!r.ok() && !sendFailureLogged) {
                sendFailureLogged = true;
                LOGGER.warn("[DungeonTrain] live chat send failed (status {}); further failures this session are silent", r.status());
            }
        });
    }

    private static void connect(Minecraft mc) {
        connected = true;
        cursor = -1;
        lastPollMs = 0;
        mc.gui.getChat().addMessage(Component.translatable(LiveChatRules.CONNECTED_KEY,
            LiveOptionsCommand.pathLink(LiveChatRules.PATH_KEY)).withStyle(ChatFormatting.GRAY));
    }

    private static void disconnect() {
        connected = false;
        cursor = -1;
    }

    /** Logout / world change: forget the cursor and the connected line. */
    public static void reset() {
        disconnect();
        lastPollMs = 0;
        sendFailureLogged = false;
    }

    private static void deliver(Minecraft mc, ChatPage page) {
        if (page.seq() < 0) return; // a failed poll leaves the cursor alone
        if (cursor < 0) {
            cursor = page.seq(); // joined: start from now
            return;
        }
        if (mc.player == null || mc.level == null) return;
        UUID me = mc.getUser() != null ? mc.getUser().getProfileId() : null;
        Set<UUID> here = new HashSet<>();
        for (AbstractClientPlayer p : mc.level.players()) here.add(p.getUUID());
        for (ChatLine line : page.lines()) {
            if (LiveChatRules.shouldShow(line, me, here)) mc.gui.getChat().addMessage(LiveChatRules.line(line));
        }
        cursor = Math.max(cursor, page.seq());
    }

    private static String base(boolean streaming) {
        return streaming ? DungeonTrain.relayBaseUrl() : LiveFeedClient.viewerBase(LiveStatusPoller.channel());
    }
}
