package games.brennan.dungeontrain.player;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.moderation.LiveReport;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether anyone, in any world, is broadcasting on the Live Feed — as the server sees it.
 *
 * <p>The relay holds the one Live Feed for every world, so "someone is live" is the relay's
 * {@code /live/status} on the public ({@code LIVE}) channel. Polled every {@link #POLL_TICKS} while players are online (an idle server
 * asks nothing); the answer goes stale after {@link #STALE_MS}, and a stale or failed answer reads
 * as not live. A streamer on this server ({@link LiveStreamers}) counts even if the relay is
 * unreachable. Read by {@code event/LiveEyeLootResolver} to make Live Eyes rare while someone is live
 * (unless the Two Streams reward has a second channel free: {@link #isLiveForEyes}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ServerLiveStatus {

    static final int POLL_TICKS = 30 * 20;
    static final long STALE_MS = 90_000L;

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static volatile boolean relayLive;
    /** The relay's last answer carried a live second stream (Two Streams). */
    private static volatile boolean relaySecondLive;
    /** The relay's last answer said Two Streams is on ({@code twoStreams}): a second channel can be claimed. */
    private static volatile boolean relayTwoStreams;
    private static volatile long relayAtMs;
    /** When the poll behind the last answer was sent: an answer only knows of claims made before that. */
    private static volatile long relayAskedAtMs;
    /** The relay's name for whoever is live, when it gave one. */
    private static volatile String relayStreamer;
    /** The relay's name for whoever holds the second stream, when it gave one. */
    private static volatile String relaySecondStreamer;
    /** The relay slots of the primary and second stream ({@code main} / {@code second}). */
    private static volatile String relayChannel = LiveFeedClient.CHANNEL_MAIN;
    private static volatile String relaySecondChannel = LiveFeedClient.CHANNEL_SECOND;
    /** Poll on the next tick instead of waiting out {@link #POLL_TICKS}: a stream here just ended. */
    private static volatile boolean pollRequested;

    private ServerLiveStatus() {}

    /**
     * True while a second stream is live (the Two Streams reward): a fresh relay answer carrying a
     * second stream, or, for the short wait before the relay reports the second stream ready, two
     * streamers on this server while a fresh relay answer says someone is live and that answer was asked
     * for before the second of them started. Once the relay has answered since both started and still
     * names no second stream, the local pair no longer counts (a lapsed claim, a cut-off streamer whose
     * name did not match the relay's). Local streamers alone never count: their claims are only their
     * clients' word, and a server that cannot reach the relay keeps the one-stream TV it always had. A
     * placed TV's right-click only steps to Stream 2 while this holds ({@code compat/vista/TvChannelCycle}).
     */
    public static boolean isSecondStreamLive(MinecraftServer server) {
        int local = server == null ? 0 : LiveStreamers.all(server).size();
        long bothStarted = server == null ? 0L : LiveStreamers.bothStartedMs(server);
        return secondStreamLive(local, bothStarted, relayLive, relaySecondLive, relayAtMs, relayAskedAtMs,
            System.currentTimeMillis());
    }

    /** Pure form for the tests. */
    static boolean secondStreamLive(int localStreamers, long bothStartedMs, boolean relayLive, boolean relaySecondLive,
                                    long relayAtMs, long relayAskedAtMs, long nowMs) {
        if (isFresh(relaySecondLive, relayAtMs, nowMs)) return true;
        return localStreamers >= 2 && isFresh(relayLive, relayAtMs, nowMs) && relayAskedAtMs < bothStartedMs;
    }

    /**
     * A stream on this server just ended or was cut off: while the last answer had a second stream,
     * ask the relay again on the next tick, so Stream 2 goes away within seconds rather than at the
     * next 30 s poll. A failed poll still keeps the old answer until {@link #STALE_MS}.
     */
    public static void requestPoll() {
        if (relaySecondLive) pollRequested = true;
    }

    /**
     * True while the Two Streams reward is on and its second channel is free: a fresh relay answer says
     * Two Streams is on, no second stream is live, and fewer than two players here are streaming. Nobody
     * would have to give up a Live Eye for another to go live, so Live Eyes are as easy to find as when
     * nobody is live ({@link #isLiveForEyes}). A stale or failed answer, or a relay that does not send
     * {@code twoStreams}, reads as not free.
     */
    public static boolean isSecondChannelFree(MinecraftServer server) {
        int local = server == null ? 0 : LiveStreamers.all(server).size();
        return secondChannelFree(local, relayTwoStreams, relaySecondLive, relayAtMs, System.currentTimeMillis());
    }

    /** Pure form for the tests. */
    static boolean secondChannelFree(int localStreamers, boolean relayTwoStreams, boolean relaySecondLive,
                                     long relayAtMs, long nowMs) {
        if (localStreamers >= 2 || !isFresh(relayTwoStreams, relayAtMs, nowMs)) return false;
        return !isFresh(relaySecondLive, relayAtMs, nowMs);
    }

    /**
     * {@link #isAnyoneLive} as Live Eye loot sees it: a free second channel ({@link #isSecondChannelFree})
     * counts as nobody live. Read by {@code event/LiveEyeLootResolver} and {@code compat/vista/LiveTvWeighting}.
     */
    public static boolean isLiveForEyes(MinecraftServer server) {
        return isAnyoneLive(server) && !isSecondChannelFree(server);
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

    /**
     * The streams on air as {@code /livereport} sees them: Stream 1 (the relay's primary) and Stream 2 (its
     * second), named as the relay names them, with a uuid where the streamer is on this server. A stale or
     * failed relay answer falls back to this server's own streamers, in claim order.
     */
    public static List<LiveReport.Stream> liveStreams(MinecraftServer server) {
        Map<UUID, String> local = new LinkedHashMap<>();
        if (server != null) {
            for (UUID id : LiveStreamers.all(server)) local.put(id, LiveStreamers.nameOf(server, id));
        }
        long now = System.currentTimeMillis();
        return liveStreams(isFresh(relayLive, relayAtMs, now), relayStreamer, relayChannel,
            isFresh(relaySecondLive, relayAtMs, now), relaySecondStreamer, relaySecondChannel, local);
    }

    /** Pure form for the tests. {@code local}: this server's streamers and their names, in claim order. */
    static List<LiveReport.Stream> liveStreams(boolean relayFresh, String name, String channel,
                                               boolean secondFresh, String secondName, String secondChannel,
                                               Map<UUID, String> local) {
        List<LiveReport.Stream> out = new ArrayList<>();
        if (relayFresh) {
            out.add(new LiveReport.Stream(1, channel, name == null ? "" : name, uuidByName(local, name)));
            if (secondFresh) {
                out.add(new LiveReport.Stream(2, secondChannel, secondName == null ? "" : secondName, uuidByName(local, secondName)));
            }
            return out;
        }
        int i = 0;
        for (Map.Entry<UUID, String> e : local.entrySet()) {
            if (i == 2) break;
            i++;
            out.add(new LiveReport.Stream(i, i == 1 ? LiveFeedClient.CHANNEL_MAIN : LiveFeedClient.CHANNEL_SECOND,
                e.getValue() == null ? "" : e.getValue(), e.getKey()));
        }
        return out;
    }

    @Nullable
    private static UUID uuidByName(Map<UUID, String> local, @Nullable String name) {
        if (name == null || name.isEmpty()) return null;
        for (Map.Entry<UUID, String> e : local.entrySet()) {
            if (name.equalsIgnoreCase(e.getValue())) return e.getKey();
        }
        return null;
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

    /** The status body's {@code twoStreams} flag; absent (an older relay) or malformed reads as off. */
    static boolean twoStreamsIn(@Nullable JsonObject body) {
        if (body == null || !body.has("twoStreams")) return false;
        try {
            return body.get("twoStreams").getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % POLL_TICKS != 0 && !pollRequested) return;
        if (server.getPlayerCount() == 0) return;
        if (!IN_FLIGHT.compareAndSet(false, true)) return;
        pollRequested = false;
        long askedAt = System.currentTimeMillis();
        LiveFeedClient.status(null, LiveFeedClient.Channel.LIVE).whenComplete((result, error) -> {
            try {
                if (error == null && result != null && result.ok()) {
                    LiveFeedClient.Status status = LiveFeedClient.parseStatus(result);
                    relayLive = status.live();
                    relaySecondLive = status.live() && status.second() != null;
                    relayTwoStreams = twoStreamsIn(result.body());
                    relayStreamer = status.streamer();
                    relaySecondStreamer = relaySecondLive ? status.second().streamer() : null;
                    relayChannel = LiveFeedClient.roomOf(status, false);
                    relaySecondChannel = LiveFeedClient.roomOf(status.second(), true);
                    relayAtMs = System.currentTimeMillis();
                    relayAskedAtMs = askedAt;
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
        relayTwoStreams = false;
        relayAtMs = 0L;
        relayAskedAtMs = 0L;
        relayStreamer = null;
        relaySecondStreamer = null;
        relayChannel = LiveFeedClient.CHANNEL_MAIN;
        relaySecondChannel = LiveFeedClient.CHANNEL_SECOND;
        pollRequested = false;
    }
}
