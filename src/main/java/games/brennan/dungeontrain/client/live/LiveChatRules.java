package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatLine;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Source;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;

/**
 * The pure rules behind {@link LiveChatBridge}: who is in the stream's chat room (Discord, Twitch and
 * other worlds), which lines to show, and how a line reads. No Minecraft state — everything comes in
 * as arguments.
 */
public final class LiveChatRules {

    public static final String PREFIX_KEY = "chat.dungeontrain.live_chat.prefix";
    public static final String CONNECTED_KEY = "chat.dungeontrain.live_chat.connected";
    public static final String PATH_KEY = "chat.dungeontrain.live_chat.path";
    /** Longest line the bridge sends; the relay caps at the same length. */
    public static final int MAX_CONTENT = 500;
    /** Longest author name shown; anything longer is cut. */
    public static final int MAX_AUTHOR = 32;

    private LiveChatRules() {}

    /**
     * In the chat: the switch is on, not Kid mode, and this client is either streaming or wearing a TV
     * while someone is on air. A TV with nobody live is silent — the chat belongs to a stream.
     */
    public static boolean granted(boolean setting, boolean kid, boolean streaming, boolean wearingTv, boolean someoneLive) {
        return setting && !kid && (streaming || (wearingTv && someoneLive));
    }

    /**
     * Whether an inbound line belongs in this client's chat: never its own, and never a game line from
     * a player in this same world — vanilla chat already showed that one. A world-mate is matched by
     * uuid or by name: on an offline-mode server the players' uuids are name-derived and differ from
     * the profile ids the relay was given, but names are unique per server either way.
     */
    public static boolean shouldShow(ChatLine line, @Nullable UUID me, Set<UUID> playersHere, Set<String> namesHere) {
        if (!line.fromGame()) return true;
        if (line.uuid() != null) {
            if (me != null && line.uuid().equalsIgnoreCase(undashed(me))) return false;
            for (UUID p : playersHere) {
                if (line.uuid().equalsIgnoreCase(undashed(p))) return false;
            }
        }
        for (String n : namesHere) {
            if (n.equalsIgnoreCase(line.author())) return false;
        }
        return true;
    }

    /** An in-game chat line worth sending: not a command, not blank; trimmed and capped. Null = skip. */
    @Nullable
    public static String outbound(@Nullable String typed) {
        if (typed == null) return null;
        String t = typed.strip();
        if (t.isEmpty() || t.startsWith("/")) return null;
        return t.length() > MAX_CONTENT ? t.substring(0, MAX_CONTENT) : t;
    }

    /**
     * Relay text made safe to print: {@code §} formatting codes, control characters and line breaks
     * removed (a Discord or Twitch user could otherwise colour, obfuscate or forge a second line),
     * then stripped and cut to {@code max}. The relay cleans too; the client does not trust it.
     */
    public static String clean(@Nullable String s, int max) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00a7') { i++; continue; } // the code and the char it formats
            if (c < 0x20 || c == 0x7f) continue;
            out.append(c);
        }
        String t = out.toString().strip();
        return t.length() > max ? t.substring(0, max).strip() : t;
    }

    /**
     * {@code [Live] Name: text} — the prefix grey, the text plain, the name coloured by room so the
     * reader knows where to answer: Discord aqua, Twitch light purple, another world green. Plain
     * literals with no click or hover events, cleaned again here in case a line was built elsewhere.
     */
    public static MutableComponent line(ChatLine line) {
        return Component.translatable(PREFIX_KEY).withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal(" " + clean(line.author(), MAX_AUTHOR)).withStyle(nameColour(line.source())))
            .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(clean(line.content(), MAX_CONTENT)).withStyle(ChatFormatting.WHITE));
    }

    /**
     * Whether a poll shows the relay's chat ring restarted (a deploy): its head seq fell below the
     * cursor. Everything the bridge remembers about seqs is then stale.
     */
    public static boolean restarted(long cursor, long pageSeq) {
        return cursor >= 0 && pageSeq >= 0 && pageSeq < cursor;
    }

    /**
     * The cursor after a poll: a failed page ({@code -1}) leaves it alone, the first page sets it to
     * the head, a restart follows the new lower head, otherwise it only grows.
     */
    public static long nextCursor(long cursor, long pageSeq) {
        if (pageSeq < 0) return cursor;
        if (cursor < 0 || restarted(cursor, pageSeq)) return pageSeq;
        return Math.max(cursor, pageSeq);
    }

    /** The name colour for a room. */
    public static ChatFormatting nameColour(Source source) {
        return switch (source) {
            case TWITCH -> ChatFormatting.LIGHT_PURPLE;
            case GAME -> ChatFormatting.GREEN;
            case DISCORD, OTHER -> ChatFormatting.AQUA;
        };
    }

    static String undashed(UUID u) {
        return u.toString().replace("-", "");
    }
}
