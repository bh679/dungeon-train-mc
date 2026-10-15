package games.brennan.dungeontrain.client.live;

import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Channel;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Status;
import games.brennan.dungeontrain.net.LiveWatchedPacket;
import games.brennan.dungeontrain.player.LiveWatchTally;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asks the relay who is live, every 15 s, but only while something in this world is actually
 * showing the feed (a TV rendered the antenna's source recently). The poll also counts this client
 * as a viewer, which is how the relay's viewer cap works — there is no lease to hold.
 *
 * <p>Which channel: every run watches the public feed — except that while a dev is streaming (the
 * dev channel, also at brennan.games/dungeontrain/livedev), a Free Play run's TVs show that instead.
 * So a Free Play poll first asks the dev channel, uncounted, whether it is live, then polls (and is
 * counted on) whichever channel it will show. Free Play comes from the badge, the client-visible
 * half of {@code RunIntegrity}; when it flips, the old status is dropped and re-polled at once.</p>
 */
public final class LiveStatusPoller {

    private static final long INTERVAL_MS = 15_000;
    /** While a dead streamer's stream is on offer ({@link LiveTakeoverOffer}), so the race is seen quickly. */
    private static final long OFFER_INTERVAL_MS = 3_000;
    private static final long INTEREST_MS = 15_000;

    private static volatile Status current = Status.OFFLINE;
    private static volatile Channel channel = Channel.LIVE;
    private static volatile boolean freePlay;
    private static volatile long lastInterestMs;
    /** A TV block (not the head viewer or a hand-held TV) last asked for the feed. */
    private static volatile long lastTvInterestMs;
    private static volatile long lastPollMs;
    private static final AtomicBoolean inFlight = new AtomicBoolean();

    private LiveStatusPoller() {}

    /** {@link #wanted()} for a TV block: also marks the feed as wanted at full size. */
    public static Status tvWanted() {
        lastTvInterestMs = System.currentTimeMillis();
        return wanted();
    }

    /** Call from a render path that wants the feed; returns the last known status. */
    public static Status wanted() {
        long now = System.currentTimeMillis();
        lastInterestMs = now;
        Minecraft mc = Minecraft.getInstance();
        boolean fp = mc.player != null && mc.player.hasEffect(ModMobEffects.FREE_PLAY);
        if (fp != freePlay) {
            freePlay = fp;
            reset();
        }
        long interval = LiveTakeoverOffer.open() ? OFFER_INTERVAL_MS : INTERVAL_MS;
        if (now - lastPollMs >= interval && inFlight.compareAndSet(false, true)) {
            lastPollMs = now;
            UUID me = mc.getUser() != null ? mc.getUser().getProfileId() : null;
            CompletableFuture<Status> devLive = fp
                ? poll(null, Channel.DEV, false)
                : CompletableFuture.completedFuture(Status.OFFLINE);
            devLive.thenCompose(dev -> {
                    Channel ch = LiveFeedClient.viewerChannel(fp, dev.live());
                    return poll(me, ch, true).thenApply(status -> new Polled(status, ch));
                })
                .whenComplete((polled, t) -> {
                    // A reply begun before Free Play flipped must not land on the other side.
                    if (polled != null && fp == freePlay) {
                        current = polled.status();
                        channel = polled.channel();
                    }
                    noteWatched(polled != null && polled.status().live(), System.currentTimeMillis());
                    inFlight.set(false);
                });
        }
        return current;
    }

    /** When the last ranked poll found the feed live; 0 after a dark one. */
    private static long lastLiveMs;

    /**
     * Tell the server how long this client has watched since its last live poll — the same gap the
     * relay credits to "Longest Viewer" — for this life's stat-book figure. Only polls the relay
     * ranks count (consent on, and not the streamer watching themselves).
     */
    private static void noteWatched(boolean live, long now) {
        boolean ranked = live && DiscordPresenceClientConfig.isGranted() && !LiveStreamController.get().streaming();
        long previous = lastLiveMs;
        lastLiveMs = ranked ? now : 0L;
        if (!ranked || previous == 0L) return;
        long seconds = (now - previous) / 1000;
        if (seconds <= 0 || seconds > LiveWatchTally.MAX_REPORT_SECONDS) return;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.getConnection() != null) PacketDistributor.sendToServer(new LiveWatchedPacket((int) seconds));
        });
    }

    /**
     * One status poll; a failed request reads as offline. {@code offers}: this is the channel the
     * world's TVs show, so a dead streamer's takeover offer in it goes to {@link LiveTakeoverOffer}.
     */
    private static CompletableFuture<Status> poll(@Nullable UUID viewer, Channel channel, boolean offers) {
        // Watching counts toward the "Longest Viewer" boards only behind the master internet consent.
        LiveFeedClient.Life ranked = DiscordPresenceClientConfig.isGranted()
            ? new LiveFeedClient.Life(LiveLife.current(), freePlay, VersionInfo.VERSION) : null;
        return LiveFeedClient.status(viewer, channel, ranked)
            .handle((r, t) -> {
                if (r == null) return Status.OFFLINE;
                if (offers) {
                    LiveFeedClient.Takeover offer = LiveFeedClient.parseTakeover(r);
                    Minecraft.getInstance().execute(() -> LiveTakeoverOffer.polled(offer, channel));
                }
                return LiveFeedClient.parseStatus(r);
            });
    }

    public static Status current() {
        return current;
    }

    /** The channel {@link #current()} describes — what a TV in this world shows (and whose chat it reads). */
    public static Channel channel() {
        return channel;
    }

    private record Polled(Status status, Channel channel) {}

    /** True while a TV has asked for the feed within the last 15 s. */
    public static boolean interested() {
        return System.currentTimeMillis() - lastInterestMs < INTEREST_MS;
    }

    /** True while a TV block has asked for the feed within the last 15 s. */
    public static boolean tvInterested() {
        return System.currentTimeMillis() - lastTvInterestMs < INTEREST_MS;
    }

    /** Tests / world change: forget what we knew. */
    public static void reset() {
        current = Status.OFFLINE;
        channel = Channel.LIVE;
        lastTvInterestMs = 0;
        lastPollMs = 0;
    }
}
