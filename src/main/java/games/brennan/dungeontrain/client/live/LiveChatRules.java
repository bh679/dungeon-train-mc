package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatLine;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;

/**
 * The pure rules behind {@link LiveChatBridge}: who is in the stream's Discord chat, which lines to
 * show, and how a line reads. No Minecraft state — everything comes in as arguments.
 */
public final class LiveChatRules {

    public static final String PREFIX_KEY = "chat.dungeontrain.live_chat.prefix";
    public static final String CONNECTED_KEY = "chat.dungeontrain.live_chat.connected";
    public static final String PATH_KEY = "chat.dungeontrain.live_chat.path";
    /** Longest line the bridge sends; the relay caps at the same length. */
    public static final int MAX_CONTENT = 500;

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
     * a player in this same world — vanilla chat already showed that one.
     */
    public static boolean shouldShow(ChatLine line, @Nullable UUID me, Set<UUID> playersHere) {
        if (!line.fromGame() || line.uuid() == null) return true;
        if (me != null && line.uuid().equalsIgnoreCase(undashed(me))) return false;
        for (UUID p : playersHere) {
            if (line.uuid().equalsIgnoreCase(undashed(p))) return false;
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

    /** {@code [Live] Name: text} — the prefix grey, the name aqua, the text plain. */
    public static MutableComponent line(ChatLine line) {
        return Component.translatable(PREFIX_KEY).withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal(" " + line.author()).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(line.content()).withStyle(ChatFormatting.WHITE));
    }

    static String undashed(UUID u) {
        return u.toString().replace("-", "");
    }
}
