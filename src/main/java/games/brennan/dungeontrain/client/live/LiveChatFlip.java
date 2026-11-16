package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.chat.ChatClick;
import games.brennan.dungeontrain.client.ChatClickActions;
import games.brennan.dungeontrain.client.chat.ChatReactionTracker;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatLine;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A click on a translated Live chat line's text flips it between the translation, with its flag, and the original,
 * without one: the same as the Live page. Only lines shown as the translation alone. Client thread only.
 */
public final class LiveChatFlip {

    static final String ACTION = "live-chat-flip";
    /** Lines remembered, newest kept: the relay's ring holds 200. */
    private static final int MAX = 256;

    private record Entry(ChatLine line, String translation, @Nullable MutableComponent flag, boolean original) {}

    private static final Map<Long, Entry> LINES = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Entry> eldest) {
            return size() > MAX;
        }
    };

    private LiveChatFlip() {}

    public static void registerClicks() {
        ChatClickActions.register(ACTION, LiveChatFlip::onClick);
    }

    private static void onClick(List<String> args) {
        if (args.size() != 1) return;
        long seq;
        try {
            seq = Long.parseLong(args.get(0));
        } catch (NumberFormatException e) {
            return; // not one of ours: a click value is only ever built here
        }
        Component next = toggle(seq);
        if (next != null) ChatReactionTracker.replaceLive(seq, next);
    }

    /** The translated line {@code line} is showing: a click can now flip it. */
    static void track(ChatLine line, String translation, @Nullable MutableComponent flag) {
        LINES.put(line.seq(), new Entry(line, translation, flag, false));
    }

    /** What line {@code seq} shows after a click, or null when it is not tracked. Package-private for tests. */
    @Nullable
    static Component toggle(long seq) {
        Entry e = LINES.get(seq);
        if (e == null) return null;
        LINES.put(seq, new Entry(e.line(), e.translation(), e.flag(), !e.original()));
        return e.original()
            ? LiveChatTranslations.flippableLine(e.line(), e.translation(), e.flag())
            : LiveChatTranslations.flippedLine(e.line());
    }

    /** The Live ring restarted or the client left the stream: its seqs mean nothing now. */
    static void forget() {
        LINES.clear();
    }

    static ClickEvent click(long seq) {
        return ChatClick.event(ACTION, Long.toString(seq));
    }
}
