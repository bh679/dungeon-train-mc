package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Channel;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Status;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.client.Minecraft;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asks the relay who is live, every 15 s, but only while something in this world is actually
 * showing the feed (a TV rendered the antenna's source recently). The poll also counts this client
 * as a viewer, which is how the relay's viewer cap works — there is no lease to hold.
 *
 * <p>Which channel: a Free Play run's TVs show the dev channel (the one dev builds stream to, and
 * brennan.games/dungeontrain/livedev), every other run's the public feed — on every build. The
 * answer comes from the Free Play badge, the client-visible half of {@code RunIntegrity}; when it
 * flips, the old status is dropped and the new channel is polled at once.</p>
 */
public final class LiveStatusPoller {

    private static final long INTERVAL_MS = 15_000;
    private static final long INTEREST_MS = 15_000;

    private static volatile Status current = Status.OFFLINE;
    private static volatile Channel channel = Channel.LIVE;
    private static volatile long lastInterestMs;
    private static volatile long lastPollMs;
    private static final AtomicBoolean inFlight = new AtomicBoolean();

    private LiveStatusPoller() {}

    /** Call from a render path that wants the feed; returns the last known status. */
    public static Status wanted() {
        long now = System.currentTimeMillis();
        lastInterestMs = now;
        Minecraft mc = Minecraft.getInstance();
        Channel want = LiveFeedClient.viewerChannel(mc.player != null && mc.player.hasEffect(ModMobEffects.FREE_PLAY));
        if (want != channel) {
            channel = want;
            reset();
        }
        if (now - lastPollMs >= INTERVAL_MS && inFlight.compareAndSet(false, true)) {
            lastPollMs = now;
            UUID me = mc.getUser() != null ? mc.getUser().getProfileId() : null;
            Channel polled = want;
            LiveFeedClient.status(me, polled).whenComplete((r, t) -> {
                // A reply from the channel we just left must not land on the new one.
                if (r != null && polled == channel) current = LiveFeedClient.parseStatus(r);
                inFlight.set(false);
            });
        }
        return current;
    }

    public static Status current() {
        return current;
    }

    /** True while a TV has asked for the feed within the last 15 s. */
    public static boolean interested() {
        return System.currentTimeMillis() - lastInterestMs < INTEREST_MS;
    }

    /** Tests / world change: forget what we knew. */
    public static void reset() {
        current = Status.OFFLINE;
        lastPollMs = 0;
    }
}
