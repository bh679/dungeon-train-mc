package games.brennan.dungeontrain.player;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether anyone, in any world, is broadcasting on the Live Feed — as the server sees it.
 *
 * <p>The relay holds the one Live Feed for every world, so "someone is live" is the relay's
 * {@code /live/status} on the public ({@code LIVE}) channel. Polled every {@link #POLL_TICKS} while players are online (an idle server
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
    /** The relay's last answer carried a live second stream (Two Streams). */
    private static volatile boolean relaySecondLive;
    private static volatile long relayAtMs;
    /** The relay's name for whoever is live, when it gave one. */
    private static volatile String relayStreamer;
    /** The relay's name for whoever holds the second stream, when it gave one. */
    private static volatile String relaySecondStreamer;
    /** Poll on the next tick instead of waiting out {@link #POLL_TICKS}: a stream here just ended. */
    private static volatile boolean pollRequested;

    private ServerLiveStatus() {}

    /**
     * True while a second stream is live (the Two Streams reward): a fresh relay answer carrying a
     * second stream, or two streamers on this server while a fresh relay answer says someone is live.
     * Local streamers alone never count: their claims are only their clients' word, and a server that
     * cannot reach the relay keeps the one-stream TV it always had. A placed TV's right-click only
     * steps to Stream 2 while this holds ({@code compat/vista/TvChannelCycle}).
     */
    public static boolean isSecondStreamLive(MinecraftServer server) {
        int local = server == null ? 0 : LiveStreamers.all(server).size();
        return secondStreamLive(local, relayLive, relaySecondLive, relayAtMs, System.currentTimeMillis());
    }

    /** Pure form for the tests. */
    static boolean secondStreamLive(int localStreamers, boolean relayLive, boolean relaySecondLive, long relayAtMs, long nowMs) {
        if (isFresh(relaySecondLive, relayAtMs, nowMs)) return true;
        return localStreamers >= 2 && isFresh(relayLive, relayAtMs, nowMs);
    }

    /**
     * A stream on this server just ended or was cut off: while the last answer had a second stream,
     * ask the relay again on the next tick, so Stream 2 goes away within seconds rather than at the
     * next 30 s poll. A failed poll still keeps the old answer until {@link #STALE_MS}.
     */
    public static void requestPoll() {
        if (relaySecondLive) pollRequested = true;
    }

    /** True while the relay recently said someone is live, or someone on this server is streaming. */
    public static boolean isAnyoneLive(MinecraftServer server) {
        if (server != null && LiveStreamers.any(server) != null) return true;
        return isFresh(relayLive, relayAtMs, System.currentTimeMillis());
    }

    /**
     * True while someone other than {@code player} is broadcasting — what a TV on their head would be
     * showing. Another streamer on this server counts (with Two Streams on there may be two, so the
     * player's own stream is set aside first); otherwise a fresh relay answer counts unless it names
     * them (a nameless answer counts as someone else), and so does a fresh second stream that is not
     * theirs.
     */
    public static boolean isSomeoneElseLive(MinecraftServer server, ServerPlayer player) {
        UUID local = server == null ? null : firstOf(LiveStreamers.othersThan(server, player.getUUID()));
        long now = System.currentTimeMillis();
        return someoneElseLive(local, player.getUUID(), isFresh(relayLive, relayAtMs, now), relayStreamer,
            isFresh(relaySecondLive, relayAtMs, now), relaySecondStreamer, player.getGameProfile().getName());
    }

    /**
     * True only when it is known that nobody, in any world, is broadcasting: no streamer on this server and
     * a fresh relay answer saying not live. A stale or failed relay answer is not knowledge, so it reads
     * false here (unlike {@link #isAnyoneLive}, where it reads as not live).
     */
    public static boolean isNobodyLive(MinecraftServer server) {
        UUID local = server == null ? null : LiveStreamers.any(server);
        return nobodyLive(local, relayLive, relayAtMs, System.currentTimeMillis());
    }

    @Nullable
    private static UUID firstOf(java.util.Set<UUID> ids) {
        return ids.isEmpty() ? null : ids.iterator().next();
    }

    /** Pure form for the tests. */
    static boolean nobodyLive(UUID localStreamer, boolean relayLive, long relayAtMs, long nowMs) {
        if (localStreamer != null || relayLive) return false;
        return relayAtMs > 0 && nowMs - relayAtMs < STALE_MS;
    }

    /** Pure form for the tests. */
    static boolean someoneElseLive(UUID localStreamer, UUID self, boolean relayFresh, String relayName,
                                   boolean secondFresh, String secondName, String selfName) {
        if (localStreamer != null && !localStreamer.equals(self)) return true;
        return (relayFresh && notSelf(relayName, selfName)) || (secondFresh && notSelf(secondName, selfName));
    }

    /** A nameless answer counts as someone else. */
    private static boolean notSelf(String name, String selfName) {
        return name == null || name.isEmpty() || !name.equalsIgnoreCase(selfName);
    }

    /** Pure form for the tests. */
    static boolean isFresh(boolean live, long atMs, long nowMs) {
        return live && atMs > 0 && nowMs - atMs < STALE_MS;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % POLL_TICKS != 0 && !pollRequested) return;
        if (server.getPlayerCount() == 0) return;
        if (!IN_FLIGHT.compareAndSet(false, true)) return;
        pollRequested = false;
        LiveFeedClient.status(null, LiveFeedClient.Channel.LIVE).whenComplete((result, error) -> {
            try {
                if (error == null && result != null && result.ok()) {
                    LiveFeedClient.Status status = LiveFeedClient.parseStatus(result);
                    relayLive = status.live();
                    relaySecondLive = status.live() && status.second() != null;
                    relayStreamer = status.streamer();
                    relaySecondStreamer = relaySecondLive ? status.second().streamer() : null;
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
        relaySecondLive = false;
        relayAtMs = 0L;
        relayStreamer = null;
        relaySecondStreamer = null;
        pollRequested = false;
    }
}
