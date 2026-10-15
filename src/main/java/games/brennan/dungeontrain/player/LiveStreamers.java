package games.brennan.dungeontrain.player;

import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Which player on this server currently holds the Live Feed — server-side, in memory only.
 *
 * <p>Nothing persists: a server restart ends the stream anyway (the client's ffmpeg died with it)
 * and the relay's TTL forgets the channel within a minute. The relay, not this, decides who is
 * live across worlds; this only remembers who to tell when someone else on the same server takes
 * over, and who to stop on death or logout.</p>
 *
 * <p>A player who has put a Live Eye on but is not live yet — their client is still claiming, or
 * someone else holds the channel and they are deciding whether to take it over — is <em>pending</em>.
 * They become the streamer only when their client says the relay gave them the channel
 * ({@code net/LiveTakeoverPacket}).</p>
 */
public final class LiveStreamers {

    private static final Map<MinecraftServer, UUID> STREAMER = new WeakHashMap<>();
    /** Pending wearers → whether they put the Live Eye on in place of a TV while someone else was live. */
    private static final Map<MinecraftServer, Map<UUID, Boolean>> PENDING = new WeakHashMap<>();

    private LiveStreamers() {}

    /** The previous streamer on this server, or null if nobody was. */
    @Nullable
    public static synchronized UUID setStreamer(MinecraftServer server, UUID player) {
        return STREAMER.put(server, player);
    }

    @Nullable
    public static synchronized UUID get(MinecraftServer server) {
        return STREAMER.get(server);
    }

    /** {@code player} wears a Live Eye and is not live yet; {@code fromWatching} as {@link #takePending}. */
    public static synchronized void addPending(MinecraftServer server, UUID player, boolean fromWatching) {
        PENDING.computeIfAbsent(server, s -> new HashMap<>()).put(player, fromWatching);
    }

    public static synchronized boolean isPending(MinecraftServer server, UUID player) {
        Map<UUID, Boolean> m = PENDING.get(server);
        return m != null && m.containsKey(player);
    }

    /**
     * Forget {@code player} as pending. Null when they were not; else whether they swapped a TV for
     * the Live Eye while someone else was live (the "took over from a TV" advancement).
     */
    @Nullable
    public static synchronized Boolean takePending(MinecraftServer server, UUID player) {
        Map<UUID, Boolean> m = PENDING.get(server);
        return m == null ? null : m.remove(player);
    }

    /** Forget {@code player} if they are the streamer; true when they were. */
    public static synchronized boolean clearIf(MinecraftServer server, UUID player) {
        UUID cur = STREAMER.get(server);
        if (cur == null || !cur.equals(player)) return false;
        STREAMER.remove(server);
        return true;
    }
}
