package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.chat.ChatClick;
import games.brennan.dungeontrain.client.ChatClickActions;
import games.brennan.dungeontrain.client.credits.CommunityLinkClient;
import games.brennan.dungeontrain.client.credits.DiscordAccountState;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatLine;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Source;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Discord-linked player reports a Kick or Twitch line in the Live chat as a bot.
 *
 * <p>Each Kick and Twitch line ends in a grey {@code [⚑]} ({@link #chip}) while this client's Discord is linked
 * ({@link DiscordAccountState}). A click prints a one-line confirm, since a Kick or Twitch delete can't be undone; its
 * {@code [Report]} sends {@code POST /live/chat/report} with the player's report key ({@link ReportKeyStore}). The relay
 * deletes the line for everyone, and this client's next poll turns it into vanilla's deleted marker like any other
 * deleted line. A link made before report keys gets a line asking for {@code /discord} once more instead.</p>
 *
 * <p>Buttons are {@link ChatClick} actions, never commands. Client thread only, apart from the HTTP.</p>
 */
public final class LiveBotReport {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String ACTION = "live-report";
    static final String ASK = "ask";
    static final String GO = "go";
    static final String CANCEL = "cancel";
    static final String FLAG = "[⚑]";

    static final String HOVER_KEY = "chat.dungeontrain.live_report.hover";
    static final String CONFIRM_KEY = "chat.dungeontrain.live_report.confirm";
    static final String YES_KEY = "chat.dungeontrain.live_report.yes";
    static final String CANCEL_KEY = "chat.dungeontrain.live_report.cancel";
    static final String DONE_KEY = "chat.dungeontrain.live_report.done";
    static final String GONE_KEY = "chat.dungeontrain.live_report.gone";
    static final String RELINK_KEY = "chat.dungeontrain.live_report.relink";
    static final String LIMIT_KEY = "chat.dungeontrain.live_report.limit";
    static final String FAILED_KEY = "chat.dungeontrain.live_report.failed";

    /** How long a missing Discord answer waits before this asks the relay again. */
    private static final long STATUS_RETRY_MS = 5 * 60 * 1000;
    private static final int MAX = 256;

    /** What a flag was put on: the line's sender and platform, and the room it was read in. */
    record Flagged(String author, Source source, @Nullable String room) {}

    /** How a report came back, for the line this client prints. */
    enum Outcome { DONE, GONE, RELINK, LIMIT, FAILED, RETRY_PENDING }

    private static final Map<Long, Flagged> FLAGGED = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Flagged> eldest) {
            return size() > MAX;
        }
    };
    private static final Set<Long> SENT = new HashSet<>();
    private static long lastStatusAskMs;

    private LiveBotReport() {}

    public static void registerClicks() {
        ChatClickActions.register(ACTION, LiveBotReport::onClick);
    }

    /** Whether a line from {@code source} can be reported at all: Kick and Twitch only. Pure. */
    static boolean reportable(Source source) {
        return source == Source.KICK || source == Source.TWITCH;
    }

    /** Whether this client's Discord is linked, by the last answer ({@link #onJoined} asks for one). */
    static boolean linked() {
        CommunityLinkClient.Status s = DiscordAccountState.status();
        return s != null && s.ok() && s.linked();
    }

    /** The client joined the Live chat: make sure there is a Discord answer to decide the flags by. */
    static void onJoined() {
        if (DiscordAccountState.status() != null) return;
        long now = System.currentTimeMillis();
        if (now - lastStatusAskMs < STATUS_RETRY_MS) return;
        lastStatusAskMs = now;
        try {
            DiscordAccountState.refresh(() -> {});
        } catch (RuntimeException e) {
            LOGGER.debug("[DungeonTrain] Live report: link status ask failed ({})", e.toString());
        }
    }

    /**
     * The {@code [⚑]} to end {@code line} with, or null: not a Kick or Twitch line, or not linked. Remembers the line
     * so a click knows who it is reporting.
     */
    @Nullable
    public static MutableComponent chip(ChatLine line) {
        if (!reportable(line.source()) || line.seq() <= 0 || !linked()) return null;
        FLAGGED.put(line.seq(), new Flagged(line.author(), line.source(), LiveChatBridge.room()));
        return chipComponent(line.seq());
    }

    static MutableComponent chipComponent(long seq) {
        return Component.literal(FLAG).withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY)
            .withClickEvent(click(ASK, seq))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(HOVER_KEY))));
    }

    static ClickEvent click(String step, long seq) {
        return ChatClick.event(ACTION, step, Long.toString(seq));
    }

    /** {@code [ask|go|cancel, seq]} → the seq, or -1 for anything else. Pure. */
    static long seqOf(List<String> args, String step) {
        if (args.size() != 2 || !step.equals(args.get(0))) return -1;
        try {
            long seq = Long.parseLong(args.get(1));
            return seq > 0 ? seq : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void onClick(List<String> args) {
        long ask = seqOf(args, ASK);
        if (ask > 0) {
            ask(ask);
            return;
        }
        long go = seqOf(args, GO);
        if (go > 0) go(go);
        // CANCEL: nothing to do; the confirm line stays as it was.
    }

    private static void ask(long seq) {
        Flagged f = FLAGGED.get(seq);
        if (f == null || SENT.contains(seq)) return;
        CommunityLinkClient.Status s = DiscordAccountState.status();
        if (s != null && s.linked() && s.reportReady()) {
            decide(seq, f);
            return;
        }
        // The answer may predate a re-link (/discord again): ask once more before saying "link again".
        DiscordAccountState.invalidate();
        DiscordAccountState.refresh(() -> decide(seq, f));
    }

    private static void decide(long seq, Flagged f) {
        CommunityLinkClient.Status s = DiscordAccountState.status();
        String uuid = CommunityLinkClient.ownUuid();
        if (uuid == null || s == null || !s.linked()) return;
        if (!s.reportReady() || !ReportKeyStore.load(uuid).any()) {
            say(relinkLine());
            return;
        }
        say(confirmLine(seq, f));
    }

    static Component confirmLine(long seq, Flagged f) {
        MutableComponent yes = Component.translatable(YES_KEY)
            .withStyle(s -> s.withColor(ChatFormatting.GREEN).withClickEvent(click(GO, seq)));
        MutableComponent no = Component.translatable(CANCEL_KEY)
            .withStyle(s -> s.withColor(ChatFormatting.GRAY).withClickEvent(click(CANCEL, seq)));
        return Component.translatable(CONFIRM_KEY, Component.literal(f.author()).withStyle(nameColour(f.source())), platform(f.source()))
            .withStyle(ChatFormatting.GRAY).append(" ").append(yes).append(" ").append(no);
    }

    private static void go(long seq) {
        Flagged f = FLAGGED.get(seq);
        String uuid = CommunityLinkClient.ownUuid();
        if (f == null || uuid == null || !SENT.add(seq)) return;
        ReportKeyStore.Keys keys = ReportKeyStore.load(uuid);
        String first = keys.current() != null ? keys.current() : keys.pending();
        if (first == null) {
            SENT.remove(seq);
            say(relinkLine());
            return;
        }
        String second = keys.current() != null ? keys.pending() : null;
        send(uuid, first, second, f, seq);
    }

    private static void send(String uuid, String key, @Nullable String fallback, Flagged f, long seq) {
        Minecraft mc = Minecraft.getInstance();
        LiveFeedClient.chatReport(LiveChatBridge.currentBase(), uuid, key, f.room(), seq).thenAcceptAsync(r -> {
            Outcome o = outcome(r.status(), r.str("error"), fallback != null);
            if (o == Outcome.RETRY_PENDING) {
                send(uuid, fallback, null, f, seq);
                return;
            }
            if (o == Outcome.DONE) ReportKeyStore.promote(uuid, key);
            if (o != Outcome.DONE && o != Outcome.GONE) SENT.remove(seq);
            if (o == Outcome.FAILED) LOGGER.debug("[DungeonTrain] Live report: relay answered {} {}", r.status(), r.str("error"));
            say(resultLine(o, f));
        }, mc).exceptionally(t -> {
            mc.execute(() -> {
                SENT.remove(seq);
                say(resultLine(Outcome.FAILED, f));
            });
            return null;
        });
    }

    /** What the relay's answer means for this client. {@code canRetry}: a second key is still untried. Pure. */
    static Outcome outcome(int status, String error, boolean canRetry) {
        if (status / 100 == 2) return Outcome.DONE;
        if (status == 403 && ("bad_key".equals(error) || "relink_needed".equals(error))) {
            return canRetry ? Outcome.RETRY_PENDING : Outcome.RELINK;
        }
        if (status == 403 && "not_linked".equals(error)) return Outcome.RELINK;
        if (status == 404 && "no_line".equals(error)) return Outcome.GONE;
        if (status == 409) return Outcome.GONE;
        if (status == 429 && !"hook".equals(error)) return Outcome.LIMIT;
        return Outcome.FAILED;
    }

    static Component resultLine(Outcome o, Flagged f) {
        return switch (o) {
            case DONE -> Component.translatable(DONE_KEY, Component.literal(f.author()).withStyle(nameColour(f.source())))
                .withStyle(ChatFormatting.GRAY);
            case GONE -> Component.translatable(GONE_KEY).withStyle(ChatFormatting.GRAY);
            case RELINK, RETRY_PENDING -> relinkLine();
            case LIMIT -> Component.translatable(LIMIT_KEY).withStyle(ChatFormatting.YELLOW);
            case FAILED -> Component.translatable(FAILED_KEY).withStyle(ChatFormatting.YELLOW);
        };
    }

    static Component relinkLine() {
        Component cmd = Component.literal("/discord").withStyle(s -> s.withColor(ChatFormatting.AQUA)
            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/discord")));
        return Component.translatable(RELINK_KEY, cmd).withStyle(ChatFormatting.YELLOW);
    }

    private static String platform(Source source) {
        return source == Source.KICK ? "Kick" : "Twitch";
    }

    private static ChatFormatting nameColour(Source source) {
        return LiveChatRules.nameColour(source);
    }

    /** The Live ring restarted or the client left the stream: its seqs mean nothing now. */
    static void forget() {
        FLAGGED.clear();
        SENT.clear();
    }

    private static void say(Component line) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(line, false);
    }
}
