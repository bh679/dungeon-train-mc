package games.brennan.dungeontrain.player;

import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

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
 */
public final class LiveStreamers {

    private static final Map<MinecraftServer, UUID> STREAMER = new WeakHashMap<>();

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

    /** Forget {@code player} if they are the streamer; true when they were. */
    public static synchronized boolean clearIf(MinecraftServer server, UUID player) {
        UUID cur = STREAMER.get(server);
        if (cur == null || !cur.equals(player)) return false;
        STREAMER.remove(server);
        return true;
    }
}
