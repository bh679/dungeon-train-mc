package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.live.life.LiveLifeChat;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.chat.ChatReactionTracker;
import games.brennan.dungeontrain.compat.vista.TvItems;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.logrequest.LogRequestClient;
import games.brennan.dungeontrain.net.ClientActionPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatLine;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatPage;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.GiftJoin;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.GiftNotices;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.GiftOffer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.world.entity.EquipmentSlot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
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
 * <p>While connected, DiscordPresence's "whispers into the void" auto-responses are dropped
 * ({@link LiveChatRules#isVoidWhisper}) — the player's words are reaching the stream.</p>
 *
 * <p>A line the relay later lists as {@code deleted} (a Twitch mod removed it) becomes vanilla's grey
 * "deleted" marker, through {@link LiveChatSignatures}.</p>
 *
 * <p>Joining through a TV also tells the relay ({@code POST /live/chat/join}), so a viewer who has never
 * streamed can be offered a Live Eye by the streamer; each poll's gift notices go to {@link LiveGiftClient}.</p>
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
    /** Part Of the Community's action went to the server this session; one is enough, the grant is one-shot. */
    private static volatile boolean tvChatReported;
    private static final AtomicBoolean inFlight = new AtomicBoolean();
    private static final LiveChatSignatures signatures = new LiveChatSignatures();

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
        // A Live Eye waiting on a takeover works as a TV: its wearer reads the room, and sees the vote.
        boolean wearingTv = !streaming && (TvItems.isTv(mc.player.getItemBySlot(EquipmentSlot.HEAD))
            || LiveStreamController.get().watchingWithEye());
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
        if (!connected) connect(mc, wearingTv);
        long now = System.currentTimeMillis();
        if (now - lastPollMs < POLL_MS || !inFlight.compareAndSet(false, true)) return;
        lastPollMs = now;
        LiveFeedClient.chat(base(streaming), cursor).thenAcceptAsync(r -> {
            inFlight.set(false);
            if (connected) deliver(mc, LiveFeedClient.parseChat(r), LiveFeedClient.parseGiftNotices(r));
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
        // A takeover vote stays hidden until the result: not in vanilla chat either (LiveTakeoverChat).
        String vote = LiveTakeoverChat.voteOpen() ? LiveTakeoverChat.voteOf(content) : null;
        if (vote != null) {
            event.setCanceled(true);
            LiveTakeoverChat.vote(vote);
            return;
        }
        send(content);
    }

    /**
     * A chat command the relay answers for the whole room ({@code /streamer}): typed in game it is a client
     * command, which never reaches {@link #onClientChat}, so it is sent from here. False when this client is
     * not in the chat.
     */
    public static boolean sendCommand(String content) {
        if (!connected) return false;
        send(content);
        return true;
    }

    /** One line to the relay, at most one a second, fire-and-forget. */
    private static void send(String content) {
        long now = System.currentTimeMillis();
        if (now - lastSendMs < SEND_GAP_MS) return;
        lastSendMs = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getUser() == null) return;
        UUID me = mc.getUser().getProfileId();
        String name = mc.getUser().getName();
        boolean streaming = LiveStreamController.get().streaming();
        boolean fromTv = !streaming && mc.player != null && TvItems.isTv(mc.player.getItemBySlot(EquipmentSlot.HEAD));
        LiveFeedClient.chatSend(base(streaming), me, name, content).thenAccept(r -> {
            if (r.ok()) {
                if (fromTv) mc.execute(LiveChatBridge::reportTvChat);
            } else if (!sendFailureLogged) {
                sendFailureLogged = true;
                LOGGER.warn("[DungeonTrain] live chat send failed (status {}); further failures this session are silent", r.status());
            }
        });
    }

    /** The relay took a line typed under a worn TV: Part Of the Community, once a session. */
    private static void reportTvChat() {
        if (tvChatReported || Minecraft.getInstance().getConnection() == null) return;
        tvChatReported = true;
        DungeonTrainNet.sendToServer(new ClientActionPacket("live_chat_from_tv"));
    }

    /** Connected, the player is being heard: DiscordPresence's "into the void" whispers would say otherwise. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onSystemChat(ClientChatReceivedEvent.System event) {
        if (connected && !event.isOverlay() && LiveChatRules.isVoidWhisper(event.getMessage())) {
            event.setCanceled(true);
        }
    }

    private static void connect(Minecraft mc, boolean viaTv) {
        connected = true;
        cursor = -1;
        lastPollMs = 0;
        signatures.clear();
        mc.gui.getChat().addMessage(Component.translatable(LiveChatRules.CONNECTED_KEY,
            LiveOptionsCommand.pathLink(LiveChatRules.PATH_KEY)).withStyle(ChatFormatting.GRAY));
        if (viaTv && mc.getUser() != null) {
            // Only a viewer who can take a Live Eye may be offered one (LiveGiftClient#canReceive).
            LiveFeedClient.chatJoin(base(false), mc.getUser().getProfileId(), mc.getUser().getName(),
                LiveGiftClient.canReceive(mc));
        }
    }

    private static void disconnect() {
        connected = false;
        LiveTakeoverChat.reset();
        cursor = -1;
        signatures.clear();
        ChatReactionTracker.forgetLive();
    }

    /** Logout / world change: forget the cursor and the connected line. */
    public static void reset() {
        disconnect();
        lastPollMs = 0;
        sendFailureLogged = false;
        tvChatReported = false;
    }

    private static void deliver(Minecraft mc, ChatPage page, GiftNotices gifts) {
        if (page.seq() < 0) return; // a failed poll leaves the cursor alone
        if (cursor < 0) {
            cursor = page.seq(); // joined: start from now
            return;
        }
        if (LiveChatRules.restarted(cursor, page.seq())) {
            // The relay was redeployed: its ring starts again at 1 while the stream carries on. Follow
            // the new head or every later poll asks for seqs that will never come and chat goes silent.
            cursor = page.seq();
            signatures.clear();
            ChatReactionTracker.forgetLive();
        }
        if (mc.player == null || mc.level == null) return;
        UUID me = mc.getUser() != null ? mc.getUser().getProfileId() : null;
        // The tab list, not level.players(): that only holds players within render range.
        Set<UUID> here = new HashSet<>();
        Set<String> names = new HashSet<>();
        if (mc.getConnection() != null) {
            for (PlayerInfo p : mc.getConnection().getOnlinePlayers()) {
                here.add(p.getProfile().getId());
                names.add(p.getProfile().getName());
            }
        }
        ChatComponent chat = mc.gui.getChat();
        // The tag plain addMessage(Component) would pick, so a printed line looks exactly as before.
        GuiMessageTag tag = mc.isSingleplayer() ? GuiMessageTag.systemSinglePlayer() : GuiMessageTag.system();
        String myName = mc.getUser() != null ? mc.getUser().getName() : "";
        boolean streaming = LiveStreamController.get().streaming();
        // This life's "the crowd" death page: who said what in the room while this player was on air.
        if (streaming) LiveLifeChat.onLines(page.lines(), me != null ? me.toString().replace("-", "") : null);
        for (ChatLine line : page.lines()) {
            if (line.poll() != null) {
                Component shown = LiveTakeoverChat.onPollLine(line.poll(), me);
                if (shown != null) chat.addMessage(shown, signatures.sign(line.seq()), tag);
                continue;
            }
            if (LiveChatRules.shouldShow(line, me, here, names)) {
                boolean mine = me != null && line.uuid() != null && line.uuid().equalsIgnoreCase(me.toString().replace("-", ""));
                Component shown = ChatReactionTracker.printLive(line.seq(), LiveChatRules.line(line), mine, line.content());
                chat.addMessage(shown, signatures.sign(line.seq()), tag);
            }
            if (line.logRequest() != null && line.logRequest().isFor(myName, streaming)) {
                LogRequestClient.onLiveRequest(line.logRequest().id());
            }
        }
        if (streaming) {
            for (GiftJoin join : gifts.joins()) LiveGiftClient.onJoin(join);
        }
        String myId = me != null ? me.toString().replace("-", "") : null;
        for (GiftOffer offer : gifts.gifts()) {
            if (myId != null && offer.target().equalsIgnoreCase(myId)) LiveGiftClient.onOffer(offer, LiveStatusPoller.channel());
        }
        for (long seq : page.deleted()) {
            MessageSignature sig = signatures.take(seq);
            if (sig != null) chat.deleteMessage(sig);
        }
        for (LiveFeedClient.LineReactions r : page.reactions()) {
            ChatReactionTracker.applyLive(r.line(), r.list(), r.event());
        }
        if (streaming) LiveLifeChat.onReactions(page.reactions());
        cursor = LiveChatRules.nextCursor(cursor, page.seq());
    }

    static String base(boolean streaming) {
        // A Live Eye waiting on a takeover claimed on the streamer's relay: its vote is in that room.
        boolean ownRelay = streaming || LiveStreamController.get().watchingWithEye();
        return ownRelay ? DungeonTrain.relayBaseUrl() : LiveFeedClient.viewerBase(LiveStatusPoller.channel());
    }
}
