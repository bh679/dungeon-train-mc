package games.brennan.dungeontrain.player;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether anyone, in any world, is broadcasting on the Live Feed — as the server sees it.
 *
 * <p>The relay holds the one Live Feed for every world, so "someone is live" is the relay's
 * {@code /live/status}. Polled every {@link #POLL_TICKS} while players are online (an idle server
 * asks nothing); the answer goes stale after {@link #STALE_MS}, and a stale or failed answer reads
 * as not live. A streamer on this server ({@link LiveStreamers}) counts even if the relay is
 * unreachable. Read by {@code event/LiveEyeLootResolver} to make Live Eyes rare while someone is live.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ServerLiveStatus {

    static final int POLL_TICKS = 30 * 20;
    static final long STALE_MS = 90_000L;

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static volatile boolean relayLive;
    private static volatile long relayAtMs;

    private ServerLiveStatus() {}

    /** True while the relay recently said someone is live, or someone on this server is streaming. */
    public static boolean isAnyoneLive(MinecraftServer server) {
        if (server != null && LiveStreamers.get(server) != null) return true;
        return isFresh(relayLive, relayAtMs, System.currentTimeMillis());
    }

    /** Pure form for the tests. */
    static boolean isFresh(boolean live, long atMs, long nowMs) {
        return live && atMs > 0 && nowMs - atMs < STALE_MS;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % POLL_TICKS != 0) return;
        if (server.getPlayerCount() == 0) return;
        if (!IN_FLIGHT.compareAndSet(false, true)) return;
        LiveFeedClient.status(null).whenComplete((result, error) -> {
            try {
                if (error == null && result != null && result.ok()) {
                    relayLive = LiveFeedClient.parseStatus(result).live();
                    relayAtMs = System.currentTimeMillis();
                }
            } finally {
                IN_FLIGHT.set(false);
            }
        });
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        relayLive = false;
        relayAtMs = 0L;
    }
}
