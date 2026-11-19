package games.brennan.dungeontrain.client.version;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.SharedConstants;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Looks up the newest released version for the title-screen badge and pushes the result into
 * {@link VersionCheckState}.
 *
 * <p>The newest version is {@code promos["<mc>-latest"]} in the repo's {@code update.json}
 * ({@link UpdateFeed}), which {@code release.yml} rewrites on every release. It used to be the GitHub
 * Releases API's {@code releases/latest}, but every release since v0.73.0 is published as a
 * pre-release, which that endpoint skips: it kept answering v0.73.0, so every build read as
 * {@link VersionCheckState.Status#AHEAD} and the badge never said an update was out.</p>
 *
 * <p>The result is handled on one shared single-thread daemon executor, which serialises accidental
 * concurrent triggers and never blocks JVM shutdown.</p>
 */
public final class LatestReleaseFetcher {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DungeonTrain-VersionCheck");
        t.setDaemon(true);
        return t;
    });

    private LatestReleaseFetcher() {}

    static void fetchAsync() {
        String current = currentModVersion();
        UpdateFeed.fetchLatest(SharedConstants.getCurrentVersion().getName(), current)
            .thenAcceptAsync(latest -> {
                if (latest == null) {
                    LOGGER.warn("Version check: update feed gave no latest version");
                    VersionCheckState.accept(VersionCheckState.Status.ERROR, null);
                    return;
                }
                VersionCheckState.Status next = classify(current, latest);
                VersionCheckState.accept(next, latest);
                LOGGER.info("Version check: current={} latest={} -> {}", current, latest, next);
            }, EXECUTOR);
    }

    /**
     * {@code current} against {@code latest} on major.minor ({@link SemverCompare}), so the
     * auto-release cascade's patch ticks never show as an update. Unreadable versions compare equal,
     * which reads as up to date rather than a false update. Pure, unit-tested.
     */
    static VersionCheckState.Status classify(String current, String latest) {
        int cmp = SemverCompare.compare(current, latest);
        if (cmp == 0) return VersionCheckState.Status.LATEST;
        return cmp < 0 ? VersionCheckState.Status.UPDATE_AVAILABLE : VersionCheckState.Status.AHEAD;
    }

    static String currentModVersion() {
        return ModList.get().getModContainerById(DungeonTrain.MOD_ID)
            .map(c -> c.getModInfo().getVersion().toString())
            .orElse("unknown");
    }
}
