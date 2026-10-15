package games.brennan.dungeontrain.discord.chat;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side memory of which Discord messages are on someone's in-game chat, so a reaction on one goes
 * to the right clients: a player's chat line was shown to {@link Kind#EVERYONE everyone}; a reply in a
 * player's thread or a Developer message was shown only to its {@link Kind#OWNER owner}.
 *
 * <p>Thread-safe (filled from DP's HTTP and gateway threads, read on the server thread); bounded.</p>
 */
public final class DiscordLineRegistry {

    public enum Kind { EVERYONE, OWNER }

    public record Entry(Kind kind, UUID owner) {}

    static final int MAX = 2000;

    private static final Map<String, Entry> BY_MESSAGE = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX;
        }
    };

    private DiscordLineRegistry() {}

    public static synchronized void put(String messageId, Kind kind, UUID owner) {
        if (messageId == null || messageId.isBlank() || owner == null) return;
        BY_MESSAGE.put(messageId, new Entry(kind, owner));
    }

    @Nullable
    public static synchronized Entry get(String messageId) {
        return messageId == null ? null : BY_MESSAGE.get(messageId);
    }

    public static synchronized void clear() {
        BY_MESSAGE.clear();
    }
}
