package games.brennan.dungeontrain.net.relay;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The newest public release, as the server last heard it from the relay — what lets a feedback
 * answer say whether its author's build is current (see
 * {@link games.brennan.dungeontrain.discord.VersionFreshness}).
 *
 * <p>The client has its own update checkers under {@code client/version}; none of them load on a
 * dedicated server, and the feedback post is built server-side. This reads the same
 * {@code /donations/summary} the death screen already fetches (its {@code updates.latestVersion} is
 * GitHub's latest release tag), sending no player name.</p>
 *
 * <p>Callers decide when asking is allowed: {@link #refreshIfStale()} is only reached from Discord
 * Presence seams that already sit behind its network-consent gate.</p>
 */
public final class LatestReleaseCache {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Releases are hours apart at their most frequent; asking twice an hour keeps up with them. */
    private static final long REFRESH_INTERVAL_MS = 30L * 60L * 1000L;

    private static final AtomicLong LAST_ATTEMPT_MS = new AtomicLong(0L);
    private static volatile String latestVersion = "";

    private LatestReleaseCache() {}

    /** The newest release version, or {@code ""} when the relay has not answered yet. */
    public static String latestVersion() {
        return latestVersion;
    }

    /** Ask the relay again unless it was asked recently. Returns at once; the reply lands later. */
    public static void refreshIfStale() {
        refreshIfStale(System.currentTimeMillis());
    }

    static void refreshIfStale(long nowMs) {
        long last = LAST_ATTEMPT_MS.get();
        if (last != 0L && nowMs - last < REFRESH_INTERVAL_MS) return;
        if (!LAST_ATTEMPT_MS.compareAndSet(last, nowMs)) return;
        try {
            DonationSummaryClient.fetch(null, summary -> accept(
                    summary == null || summary.updates() == null ? "" : summary.updates().latestVersion()));
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] latest-release lookup failed: {}", e.toString());
        }
    }

    /** Keep a reply only when it names a version — a blank one must not erase what is known. */
    static void accept(String version) {
        if (version != null && !version.isBlank()) {
            latestVersion = version.strip();
        }
    }
}
