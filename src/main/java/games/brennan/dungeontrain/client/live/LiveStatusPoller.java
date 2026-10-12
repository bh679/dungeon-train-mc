package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Channel;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Status;
import games.brennan.dungeontrain.registry.ModMobEffects;
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
        if (now - lastPollMs >= INTERVAL_MS && inFlight.compareAndSet(false, true)) {
            lastPollMs = now;
            UUID me = mc.getUser() != null ? mc.getUser().getProfileId() : null;
            CompletableFuture<Status> devLive = fp
                ? poll(null, Channel.DEV)
                : CompletableFuture.completedFuture(Status.OFFLINE);
            devLive.thenCompose(dev -> {
                    Channel ch = LiveFeedClient.viewerChannel(fp, dev.live());
                    return poll(me, ch).thenApply(status -> new Polled(status, ch));
                })
                .whenComplete((polled, t) -> {
                    // A reply begun before Free Play flipped must not land on the other side.
                    if (polled != null && fp == freePlay) {
                        current = polled.status();
                        channel = polled.channel();
                    }
                    inFlight.set(false);
                });
        }
        return current;
    }

    /** One status poll; a failed request reads as offline. */
    private static CompletableFuture<Status> poll(@Nullable UUID viewer, Channel channel) {
        return LiveFeedClient.status(viewer, channel)
            .handle((r, t) -> r == null ? Status.OFFLINE : LiveFeedClient.parseStatus(r));
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
