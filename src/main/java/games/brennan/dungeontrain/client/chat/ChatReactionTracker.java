package games.brennan.dungeontrain.client.chat;

import games.brennan.dungeontrain.client.chat.emoji.DiscordEmojiCache;
import games.brennan.dungeontrain.client.chat.emoji.EmojiText;
import games.brennan.dungeontrain.discord.chat.ChatReaction;
import games.brennan.dungeontrain.discord.chat.ChatReactionEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Chat lines that came from (or went to) Discord, remembered so they can be re-drawn in place: Discord
 * reactions as chips after the text ({@code [👍 2]}, hover for who), and custom emoji images once they
 * finish downloading.
 *
 * <p>Three kinds of line, each keyed by where its reactions come from:</p>
 * <ul>
 *   <li>{@code d:<messageId>} — a player's own chat line, tied to its Discord message by the server's
 *       {@link games.brennan.dungeontrain.net.DiscordLineBindPacket} (matched against the recent player
 *       lines this client printed), or a Discord reply / Developer message the server sent with its id;</li>
 *   <li>{@code live:<seq>} — a Live stream chat line, whose reactions arrive on the relay poll;</li>
 *   <li>{@code e:<n>} — any other line still waiting on a custom emoji image.</li>
 * </ul>
 *
 * <p>When an emoji is added to a line that is the player's own and has already faded from the closed
 * chat, a short grey notice says who reacted. Client thread only; forgotten on logout.</p>
 */
public final class ChatReactionTracker {

    private static final int MAX_LINES = 300;
    private static final int MAX_RECENT = 100;
    private static final long BIND_WINDOW_MS = 60_000;
    private static final int SNIPPET = 24;
    public static final String NOTICE_KEY = "chat.dungeontrain.discord_react.notice";

    /** A tracked line: what it was printed from, what it shows now, and its reactions. */
    private static final class Line {
        final Component source;
        final boolean mine;
        final String snippet;
        Component shown;
        Set<String> pendingEmoji;
        List<ChatReaction> reactions = List.of();

        Line(Component source, Component shown, Set<String> pendingEmoji, boolean mine, String snippet) {
            this.source = source;
            this.shown = shown;
            this.pendingEmoji = pendingEmoji;
            this.mine = mine;
            this.snippet = snippet;
        }
    }

    /** A player chat line this client printed, waiting to learn its Discord id. */
    private record Recent(UUID sender, String raw, Component source, Component shown, Set<String> pending, long atMs) {}

    private static final Map<String, Line> LINES = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Line> eldest) {
            return size() > MAX_LINES;
        }
    };
    /** Newest first. */
    private static final List<Recent> RECENT = new ArrayList<>();
    private static long anonCounter;
    private static boolean listening;

    private ChatReactionTracker() {}

    /** A player's chat line is about to print: emoji converted, remembered for its Discord id. */
    public static Component onPlayerChat(UUID sender, String raw, Component display) {
        ensureListening();
        EmojiText.Result r = EmojiText.convert(display);
        // A distinct instance per line even when nothing changed, so two identical lines stay apart.
        Component shown = r.component() == display ? display.copy() : r.component();
        RECENT.add(0, new Recent(sender, raw, display, shown, r.pendingIds(), System.currentTimeMillis()));
        while (RECENT.size() > MAX_RECENT) RECENT.remove(RECENT.size() - 1);
        return shown;
    }

    /** Any other line about to print: emoji converted; tracked only while a custom emoji is loading. */
    public static Component onOtherChat(Component display) {
        ensureListening();
        EmojiText.Result r = EmojiText.convert(display);
        if (!r.pendingIds().isEmpty()) {
            LINES.put("e:" + (anonCounter++), new Line(display, r.component(), r.pendingIds(), false, ""));
        }
        return r.component();
    }

    /** The server says {@code sender}'s line {@code raw} is Discord message {@code messageId}. */
    public static void bind(String messageId, UUID sender, String raw) {
        long now = System.currentTimeMillis();
        for (Iterator<Recent> it = RECENT.iterator(); it.hasNext(); ) {
            Recent r = it.next();
            if (now - r.atMs() > BIND_WINDOW_MS) break; // newest first: everything after is older still
            if (!r.sender().equals(sender) || !r.raw().equals(raw)) continue;
            it.remove();
            LINES.put("d:" + messageId, new Line(r.source(), r.shown(), r.pending(), sender.equals(me()), snippet(raw)));
            return;
        }
    }

    /** Print a Discord reply / Developer message the server sent with its Discord id. */
    public static void printDiscordLine(String messageId, Component line) {
        ensureListening();
        EmojiText.Result r = EmojiText.convert(line);
        Component shown = r.component() == line ? line.copy() : r.component();
        LINES.put("d:" + messageId, new Line(line, shown, r.pendingIds(), true, snippet(line.getString())));
        Minecraft.getInstance().gui.getChat().addMessage(shown);
    }

    /** A Live chat line about to print with {@code seq}: returns the instance to print. */
    public static Component printLive(long seq, Component line, boolean mine, String content) {
        ensureListening();
        EmojiText.Result r = EmojiText.convert(line);
        Component shown = r.component() == line ? line.copy() : r.component();
        LINES.put("live:" + seq, new Line(line, shown, r.pendingIds(), mine, snippet(content)));
        return shown;
    }

    public static void applyDiscord(String messageId, List<ChatReaction> reactions, @Nullable ChatReactionEvent event) {
        apply("d:" + messageId, reactions, event);
    }

    public static void applyLive(long seq, List<ChatReaction> reactions, @Nullable ChatReactionEvent event) {
        apply("live:" + seq, reactions, event);
    }

    /** The Live ring restarted or the client left the stream: its seqs mean nothing now. */
    public static void forgetLive() {
        LINES.keySet().removeIf(k -> k.startsWith("live:"));
    }

    /** Logout / world change. */
    public static void reset() {
        LINES.clear();
        RECENT.clear();
    }

    private static void apply(String key, List<ChatReaction> reactions, @Nullable ChatReactionEvent event) {
        Line line = LINES.get(key);
        if (line == null) return;
        line.reactions = List.copyOf(reactions);
        int age = redraw(line);
        if (event == null || !event.isAdd() || !line.mine) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ChatScreen) return; // chat is open: the chip itself is in view
        if (age >= 0 && age < ChatLineEditor.FADE_TICKS) return; // the line is still on screen
        MutableComponent emoji = EmojiText.reactionEmoji(event.name(), event.id(), Style.EMPTY);
        // Converted as a whole so emoji inside the quoted snippet draw in colour too.
        Component notice = Component.translatable(NOTICE_KEY, event.userName(), emoji, line.snippet)
            .withStyle(ChatFormatting.GRAY);
        mc.gui.getChat().addMessage(EmojiText.convert(notice).component());
    }

    /** Re-draws {@code line} from its source; returns the line's age in GUI ticks, -1 if gone. */
    private static int redraw(Line line) {
        EmojiText.Result r = EmojiText.convert(line.source);
        line.pendingEmoji = r.pendingIds();
        Component next = withChips(r.component() == line.source ? line.source.copy() : r.component(), line.reactions);
        int age = ChatLineEditor.replace(line.shown, next);
        line.shown = next;
        return age;
    }

    static Component withChips(Component base, List<ChatReaction> reactions) {
        if (reactions.isEmpty()) return base;
        MutableComponent out = Component.empty().append(base);
        for (ChatReaction r : reactions) {
            if (r.count() <= 0) continue;
            String who = String.join(", ", r.users());
            int unnamed = r.count() - r.users().size();
            if (unnamed > 0) who = who.isEmpty() ? "+" + unnamed : who + " +" + unnamed;
            Style chip = Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(who)));
            out.append(Component.literal(" [").withStyle(chip))
                .append(EmojiText.reactionEmoji(r.name(), r.id(), chip))
                .append(Component.literal(" " + r.count() + "]").withStyle(chip));
        }
        return out;
    }

    /** A custom emoji image landed: re-draw every line that was showing {@code :name:} for it. */
    private static void onEmojiLoaded(String id) {
        List<Map.Entry<String, Line>> waiting = new ArrayList<>();
        for (Map.Entry<String, Line> e : LINES.entrySet()) {
            if (e.getValue().pendingEmoji.contains(id)) waiting.add(e);
        }
        for (Map.Entry<String, Line> e : waiting) {
            redraw(e.getValue());
            if (e.getKey().startsWith("e:") && e.getValue().pendingEmoji.isEmpty()) LINES.remove(e.getKey());
        }
        for (int i = 0; i < RECENT.size(); i++) {
            Recent r = RECENT.get(i);
            if (!r.pending().contains(id)) continue;
            EmojiText.Result c = EmojiText.convert(r.source());
            Component next = c.component() == r.source() ? r.source().copy() : c.component();
            ChatLineEditor.replace(r.shown(), next);
            RECENT.set(i, new Recent(r.sender(), r.raw(), r.source(), next, c.pendingIds(), r.atMs()));
        }
    }

    private static void ensureListening() {
        if (listening) return;
        listening = true;
        DiscordEmojiCache.addListener(ChatReactionTracker::onEmojiLoaded);
    }

    @Nullable
    private static UUID me() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null ? mc.player.getUUID() : null;
    }

    static String snippet(String text) {
        String t = text == null ? "" : text.strip();
        int cut = t.offsetByCodePoints(0, Math.min(SNIPPET, t.codePointCount(0, t.length())));
        return cut < t.length() ? t.substring(0, cut) + "…" : t;
    }

    /** Package-private for tests. */
    static Set<String> keys() {
        return new HashSet<>(LINES.keySet());
    }
}
