package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.live.life.LiveLifeChat;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.chat.ChatReactionTracker;
import games.brennan.dungeontrain.compat.vista.TvItems;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.logrequest.LogRequestClient;
import games.brennan.dungeontrain.mixin.client.ChatComponentAccessor;
import games.brennan.dungeontrain.net.ClientActionPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatLine;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatPage;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.GiftJoin;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.GiftNotices;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.GiftOffer;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.LineTranslations;
import net.minecraft.ChatFormatting;
import net.minecraft.client.GuiMessage;
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
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The stream's Discord chat in this client's chat, and this client's chat back to it.
 *
 * <p>While {@link LiveChatRules#granted} holds — the Live Chat switch on, not Kid mode, and this client
 * either streaming or wearing a TV, on air or not — the bridge polls the relay's
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
 * <p>While the Live Translations community reward is on, each poll names this reader's language and the
 * relay answers with {@code translations} for earlier lines (or {@code tr} on a line read in the same
 * poll); {@link LiveChatTranslations} renders them where {@code LiveFeedClientConfig.liveChatTranslation()}
 * says: under the original (a second line of the same seq), in its place (redrawn through
 * {@link ChatReactionTracker}, so its reaction chips stay), or first with the original under it.</p>
 *
 * <p>Joining through a TV also tells the relay ({@code POST /live/chat/join}), so a viewer who has never
 * streamed can be offered a Live Eye by the streamer; each poll's gift notices go to {@link LiveGiftClient}.</p>
 *
 * <p>Two streams up (Two Streams), each has its own room ({@link LiveChatRoom}): reads, sends, joins and
 * death lines name it, and moving to the other room starts its chat from the head, with one short line
 * saying which. With one stream up no room is named, exactly as before.</p>
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
    /** Joined through a TV: the relay's join list is memory only, so a relay restart needs the join again. */
    private static volatile boolean joinedViaTv;
    /** The room this client reads and posts in ({@link LiveChatRoom}); null with one stream up. */
    @org.jetbrains.annotations.Nullable private static volatile String room;
    private static final AtomicBoolean inFlight = new AtomicBoolean();
    /** Relay-restart lines waiting their turn: printed at the relay's own gaps, not a poll's batch at once. */
    private static final ArrayDeque<ChatLine> restartLines = new ArrayDeque<>();
    private static long lastRestartTs;
    private static long lastRestartPrintedMs;
    private static final LiveChatSignatures signatures = new LiveChatSignatures();
    /** Lines printed this session, by seq, waiting on a translation; bounded like the signatures. */
    private static final Map<Long, Printed> printed = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Printed> eldest) {
            return size() > LiveChatSignatures.MAX;
        }
    };
    /** The one-time "being translated into …" notice went out this session. */
    private static boolean translationNoticeShown;

    /** A printed live line, whether its translation has been shown, and whether the reader wrote it (never translated). */
    private record Printed(ChatLine line, boolean translated, boolean mine) {}

    private LiveChatBridge() {}

    /** Whether this client is in the chat right now. */
    public static boolean connected() {
        return connected;
    }

    /** The room this client is in, for a request that must land there; null with one stream up. */
    @org.jetbrains.annotations.Nullable
    public static String room() {
        return room;
    }

    /** Who this client reads the chat as, for {@link LiveChatRoom}. */
    static LiveChatRoom.Role role() {
        LiveStreamController c = LiveStreamController.get();
        if (c.streaming()) return LiveChatRoom.Role.STREAMER;
        return c.watchingWithEye() ? LiveChatRoom.Role.EYE : LiveChatRoom.Role.TV;
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
        boolean granted = LiveChatRules.granted(setting, kid, streaming, wearingTv);
        // A worn TV keeps the status fresh even with the pinned viewer hidden (it picks the Two Streams
        // room); the poll also counts the wearer as a viewer, which they are — they are reading the chat.
        if (granted && wearingTv) LiveStatusPoller.wanted();
        if (!granted) {
            if (connected) disconnect();
            return;
        }
        LiveFeedClient.Status status = LiveStatusPoller.current();
        if (status.second() == null) LiveChatRoom.reset();
        LiveChatRoom.Pick pick = LiveChatRoom.pick(status, role(), LiveStreamController.get().onSecondChannel(),
            HeadViewerPanes.choice(), LiveChatRoom.chosen());
        if (!connected) {
            connect(mc, wearingTv, pick);
        } else if (!Objects.equals(LiveChatRoom.roomOf(pick), room)) {
            switchRoom(mc, pick);
        }
        long now = System.currentTimeMillis();
        printRestartLines(mc, now);
        if (now - lastPollMs < LiveRelayRestart.chatPollMs(now, POLL_MS) || !inFlight.compareAndSet(false, true)) return;
        lastPollMs = now;
        String asked = room;
        LiveFeedClient.chat(base(streaming), cursor, asked, LiveChatTranslations.readerLang(mc.options.languageCode)).thenAcceptAsync(r -> {
            inFlight.set(false);
            // A page from the room this client just left would move the new room's cursor: dropped.
            if (connected && Objects.equals(asked, room)) {
                deliver(mc, LiveFeedClient.parseChat(r), LiveFeedClient.parseGiftNotices(r));
            }
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
        LiveFeedClient.chatSend(base(streaming), me, name, content, room).thenAccept(r -> {
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

    private static void connect(Minecraft mc, boolean viaTv, @org.jetbrains.annotations.Nullable LiveChatRoom.Pick pick) {
        connected = true;
        joinedViaTv = viaTv;
        cursor = -1;
        lastPollMs = 0;
        room = LiveChatRoom.roomOf(pick);
        signatures.clear();
        mc.gui.getChat().addMessage(Component.translatable(LiveChatRules.CONNECTED_KEY,
            LiveOptionsCommand.pathLink(LiveChatRules.PATH_KEY)).withStyle(ChatFormatting.GRAY));
        if (pick != null) mc.gui.getChat().addMessage(roomLine(pick));
        if (viaTv) join(mc);
    }

    /**
     * Into another stream's room (or, the second stream gone, back to the one room): its own seqs, so the
     * chat starts again from its head; a TV viewer joins it so the streamer there sees them arrive.
     */
    private static void switchRoom(Minecraft mc, @org.jetbrains.annotations.Nullable LiveChatRoom.Pick pick) {
        room = LiveChatRoom.roomOf(pick);
        cursor = -1;
        lastPollMs = 0;
        restartLines.clear();
        LiveTakeoverChat.reset();
        signatures.clear();
        printed.clear();
        ChatReactionTracker.forgetLive();
        if (pick != null) mc.gui.getChat().addMessage(roomLine(pick));
        if (joinedViaTv) join(mc);
    }

    /** "Now chatting in Stream 2 (Bo)": which room this client just entered. */
    static Component roomLine(LiveChatRoom.Pick pick) {
        Component line = pick.streamer() == null || pick.streamer().isBlank()
            ? Component.translatable(ROOM_UNNAMED_KEY, pick.stream())
            : Component.translatable(ROOM_KEY, pick.stream(), pick.streamer());
        return line.copy().withStyle(ChatFormatting.GRAY);
    }

    static final String ROOM_KEY = "chat.dungeontrain.live_room.now";
    static final String ROOM_UNNAMED_KEY = "chat.dungeontrain.live_room.now_unnamed";

    private static void join(Minecraft mc) {
        if (mc.getUser() == null) return;
        // Only a viewer who can take a Live Eye may be offered one (LiveGiftClient#canReceive).
        LiveFeedClient.chatJoin(base(false), mc.getUser().getProfileId(), mc.getUser().getName(),
            LiveGiftClient.canReceive(mc), room);
    }

    /**
     * The relay restarting (LiveRelayRestart): each line in this player's language, not the relay's English,
     * at the gap the relay left after the one before, so a poll that brought several still reads 3, 2, 1.
     */
    private static void printRestartLines(Minecraft mc, long now) {
        while (!restartLines.isEmpty()) {
            ChatLine line = restartLines.peek();
            if (now < lastRestartPrintedMs + LiveRelayRestart.gapMs(lastRestartTs, line.ts())) return;
            restartLines.poll();
            lastRestartTs = line.ts();
            lastRestartPrintedMs = now;
            LiveRelayRestart.note(line.restart(), line.ts(), now);
            GuiMessageTag tag = mc.isSingleplayer() ? GuiMessageTag.systemSinglePlayer() : GuiMessageTag.system();
            mc.gui.getChat().addMessage(LiveRelayRestart.line(line.restart(), line.ts()), signatures.sign(line.seq()), tag);
        }
    }

    private static void disconnect() {
        connected = false;
        room = null;
        restartLines.clear();
        LiveTakeoverChat.reset();
        cursor = -1;
        signatures.clear();
        printed.clear();
        ChatReactionTracker.forgetLive();
    }

    /** Logout / world change: forget the cursor and the connected line. */
    public static void reset() {
        disconnect();
        lastPollMs = 0;
        sendFailureLogged = false;
        tvChatReported = false;
        joinedViaTv = false;
        translationNoticeShown = false;
        LiveChatRoom.reset();
        lastRestartTs = 0L;
        lastRestartPrintedMs = 0L;
        LiveRelayRestart.reset();
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
            signatures.clear();
            printed.clear();
            ChatReactionTracker.forgetLive();
            if (joinedViaTv) join(mc); // the restart forgot this viewer was in the room
            if (LiveRelayRestart.inWindow(System.currentTimeMillis())) {
                // An announced restart: the new ring holds only what was said since the boot (the "back"
                // line among it), so read it from the start next poll rather than skip to its head.
                cursor = 0;
                return;
            }
            cursor = page.seq();
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
        String readerLang = LiveChatTranslations.readerLang(mc.options.languageCode);
        boolean streaming = LiveStreamController.get().streaming();
        // This life's "the crowd" death page: who said what in the room while this player was on air.
        if (streaming) LiveLifeChat.onLines(page.lines(), me != null ? me.toString().replace("-", "") : null);
        for (ChatLine line : page.lines()) {
            if (line.restart() != null) {
                restartLines.add(line); // printed by printRestartLines, a second apart as the relay sent them
                continue;
            }
            if (line.poll() != null) {
                Component shown = LiveTakeoverChat.onPollLine(line.poll(), me);
                if (shown != null) chat.addMessage(shown, signatures.sign(line.seq()), tag);
                continue;
            }
            if (line.death() != null) {
                // A TV watcher died (LiveDeathAnnounce): printed in this reader's language.
                if (LiveChatRules.showDeath(line.death(), myName, names)) {
                    chat.addMessage(LiveDeathAnnounce.line(mc, line), signatures.sign(line.seq()), tag);
                }
                continue;
            }
            if (LiveChatRules.shouldShow(line, me, here, names)) {
                boolean mine = me != null && line.uuid() != null && line.uuid().equalsIgnoreCase(me.toString().replace("-", ""));
                // The reader's own words are shown as typed, never translated back at them.
                printLine(mc, chat, tag, line, mine, mine ? null : LiveChatTranslations.pick(line.tr(), readerLang));
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
            for (MessageSignature sig : signatures.takeAll(seq)) chat.deleteMessage(sig);
            printed.remove(seq);
        }
        for (LineTranslations t : page.translations()) {
            Printed p = printed.get(t.line());
            if (p == null || p.translated() || p.mine()) continue;
            String mine = LiveChatTranslations.pick(t.tr(), readerLang);
            if (mine == null) continue;
            applyLate(mc, chat, tag, p.line(), mine);
        }
        for (LiveFeedClient.LineReactions r : page.reactions()) {
            ChatReactionTracker.applyLive(r.line(), r.list(), r.event());
        }
        if (streaming) LiveLifeChat.onReactions(page.reactions());
        cursor = LiveChatRules.nextCursor(cursor, page.seq());
    }

    /**
     * A line into chat, with its translation when one came with it: under it, in its place, or first with
     * the original under it. Without one, the line as before, remembered for a later translation.
     */
    private static void printLine(Minecraft mc, ChatComponent chat, GuiMessageTag tag, ChatLine line, boolean mine,
                                  @Nullable String translation) {
        long seq = line.seq();
        if (translation == null) {
            chat.addMessage(ChatReactionTracker.printLive(seq, LiveChatRules.line(line), mine, line.content()), signatures.sign(seq), tag);
            printed.put(seq, new Printed(line, false, mine));
            return;
        }
        switch (LiveFeedClientConfig.liveChatTranslation()) {
            case UNDER -> {
                chat.addMessage(ChatReactionTracker.printLive(seq, LiveChatRules.line(line), mine, line.content()), signatures.sign(seq), tag);
                chat.addMessage(ChatReactionTracker.onOtherChat(LiveChatTranslations.underLine(line, translation, true)),
                    signatures.signPart(seq, 1), tag);
            }
            case REPLACE -> chat.addMessage(ChatReactionTracker.printLive(seq, LiveChatTranslations.replacedLine(line, translation), mine, line.content()),
                signatures.sign(seq), tag);
            case FIRST -> {
                chat.addMessage(ChatReactionTracker.printLive(seq, LiveChatTranslations.replacedLine(line, translation), mine, line.content()),
                    signatures.sign(seq), tag);
                chat.addMessage(ChatReactionTracker.onOtherChat(LiveChatTranslations.underLine(line, line.content(), true)),
                    signatures.signPart(seq, 1), tag);
            }
        }
        printed.put(seq, new Printed(line, true, mine));
        noteTranslating(mc, chat);
    }

    /**
     * A translation that arrived after its line was printed: added under it, or swapped in where the line stands.
     * A line added under it reads {@code ↳ text} only while the original is still the newest line in chat;
     * after anything else, live or not, it carries the author's name.
     */
    private static void applyLate(Minecraft mc, ChatComponent chat, GuiMessageTag tag, ChatLine line, String translation) {
        long seq = line.seq();
        boolean adjacent = signatures.isNewest(seq, newestSignature(chat));
        switch (LiveFeedClientConfig.liveChatTranslation()) {
            case UNDER -> chat.addMessage(ChatReactionTracker.onOtherChat(LiveChatTranslations.underLine(line, translation, adjacent)),
                signatures.signPart(seq, 1), tag);
            case REPLACE -> {
                if (!ChatReactionTracker.replaceLive(seq, LiveChatTranslations.replacedLine(line, translation))) return;
            }
            case FIRST -> {
                if (!ChatReactionTracker.replaceLive(seq, LiveChatTranslations.replacedLine(line, translation))) return;
                chat.addMessage(ChatReactionTracker.onOtherChat(LiveChatTranslations.underLine(line, line.content(), adjacent)),
                    signatures.signPart(seq, 1), tag);
            }
        }
        printed.put(seq, new Printed(line, true, false));
        noteTranslating(mc, chat);
    }

    /** The signature of the newest line in chat (null when it has none, or chat is empty). */
    @Nullable
    private static MessageSignature newestSignature(ChatComponent chat) {
        List<GuiMessage> all = ((ChatComponentAccessor) chat).dungeontrain$getAllMessages();
        return all.isEmpty() ? null : all.get(0).signature();
    }

    /** Once a session, the first time a translation shows: what is happening, and where to change how it looks. */
    private static void noteTranslating(Minecraft mc, ChatComponent chat) {
        if (translationNoticeShown) return;
        translationNoticeShown = true;
        String lang = LiveChatTranslations.readerLang(mc.options.languageCode);
        chat.addMessage(Component.translatable(LiveChatTranslations.ON_KEY,
            LiveChatTranslations.langName(lang == null ? "en_us" : lang),
            LiveOptionsCommand.pathLink(LiveChatTranslations.PATH_KEY)).withStyle(ChatFormatting.GRAY));
    }

    static String base(boolean streaming) {
        // A Live Eye waiting on a takeover claimed on the streamer's relay: its vote is in that room.
        boolean ownRelay = streaming || LiveStreamController.get().watchingWithEye();
        return ownRelay ? DungeonTrain.relayBaseUrl() : LiveFeedClient.viewerBase(LiveStatusPoller.channel());
    }
}
