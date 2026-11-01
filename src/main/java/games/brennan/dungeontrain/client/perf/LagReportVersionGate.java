package games.brennan.dungeontrain.client.perf;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.client.version.SemverCompare;
import games.brennan.dungeontrain.client.version.UpdateFeed;
import net.minecraft.SharedConstants;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether this build is new enough for {@link ClientStallWatchdog} and {@link LagReportPrompt} to
 * run: the newest release, a build ahead of it (dev), or at most {@link #MAX_MINORS_BEHIND} MINOR
 * versions behind. A freeze on an older build has most likely been fixed since, so neither its
 * stack dump nor its report is worth the log space.
 *
 * <p>"Newest" is {@code promos["<mc>-latest"]} in the repo's {@code update.json} ({@link UpdateFeed}),
 * which {@code release.yml} rewrites on every release. Not the GitHub "latest release" API behind the
 * title-screen badge: releases are published as pre-releases, so that one still names v0.73.0 and
 * every build would read as ahead. Asked once per session (again every {@link #RETRY_MS} while it
 * fails). Until it answers, offline included, the build is not known to be recent and lag detection
 * stays off.</p>
 */
public final class LagReportVersionGate {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** How many MINOR releases behind the newest a build may be and still run lag detection. */
    static final int MAX_MINORS_BEHIND = 20;
    /** Ask the feed again this often while it has not answered. */
    private static final long RETRY_MS = 10 * 60_000;

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static volatile long lastAttemptAt;
    /** The newest release; {@code null} until the feed answers. */
    private static volatile String latest;

    private LagReportVersionGate() {}

    /** Whether lag detection may run now. Any thread; asks the feed the first time. */
    static boolean enabled() {
        String newest = latest;
        if (newest == null) {
            maybeFetch();
            return false;
        }
        return isRecent(VersionInfo.VERSION, newest);
    }

    /**
     * True once the feed has answered and this build is too old: final for the session, since the
     * installed jar cannot change while it runs.
     */
    static boolean ruledOut() {
        String newest = latest;
        return newest != null && !isRecent(VersionInfo.VERSION, newest);
    }

    private static void maybeFetch() {
        long now = System.currentTimeMillis();
        if (lastAttemptAt != 0 && now - lastAttemptAt < RETRY_MS) return;
        if (!IN_FLIGHT.compareAndSet(false, true)) return;
        lastAttemptAt = now;
        UpdateFeed.fetchLatest(SharedConstants.getCurrentVersion().getName(), VersionInfo.VERSION)
            .whenComplete((version, error) -> {
                IN_FLIGHT.set(false);
                if (version == null) return;
                latest = version;
                LOGGER.info("[DungeonTrain] Lag detection version gate: installed {}, latest {} -> {}",
                    VersionInfo.VERSION, version, isRecent(VersionInfo.VERSION, version) ? "on" : "off");
            });
    }

    /**
     * Whether {@code installed} is at or ahead of {@code latest}, or the same MAJOR and at most
     * {@link #MAX_MINORS_BEHIND} MINORs behind. PATCH, a leading {@code v}, {@code -pre} and
     * {@code +build} are ignored. False when either cannot be read. Pure, unit-tested.
     */
    static boolean isRecent(String installed, String latest) {
        int[] have = majorMinor(installed);
        int[] newest = majorMinor(latest);
        if (have == null || newest == null) return false;
        if (have[0] != newest[0]) return have[0] > newest[0];
        return newest[1] - have[1] <= MAX_MINORS_BEHIND;
    }

    /** {@code {major, minor}}, or {@code null} when the text is not {@code digits.digits[...]}. */
    private static int[] majorMinor(String version) {
        if (version == null) return null;
        String s = SemverCompare.stripV(version.strip());
        int cut = indexOfAny(s, '-', '+');
        if (cut >= 0) s = s.substring(0, cut);
        String[] parts = s.split("\\.");
        if (parts.length < 2) return null;
        try {
            return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int indexOfAny(String s, char a, char b) {
        int ia = s.indexOf(a);
        int ib = s.indexOf(b);
        if (ia < 0) return ib;
        if (ib < 0) return ia;
        return Math.min(ia, ib);
    }
}
