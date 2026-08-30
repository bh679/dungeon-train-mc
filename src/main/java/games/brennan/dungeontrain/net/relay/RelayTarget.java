package games.brennan.dungeontrain.net.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Which relay a Train Builder profile call goes to.
 *
 * <p>Ordinarily there is nothing to choose: a build talks to the cap its branch routes to, and that
 * is {@link #dev()}. The exception is My Builds' live toggle — a DEV-BUILD affordance for looking at
 * production builds, where the point is precisely to read the pool this build does not write to.</p>
 *
 * <p>Two of the three profile calls need nothing but the live cap, which every jar already carries:
 * {@code /carriages/mine} and {@code /carriages/fetch} are authorised by the owner uuid. The creator
 * SEARCH is different — the relay answers it on the dev cap alone, because a name and a uuid for
 * every builder is not something a shipped cap should be able to enumerate. Live search therefore
 * goes through the admin route, and the admin base URL is read from the machine rather than compiled
 * in: {@code DUNGEONTRAIN_RELAY_ADMIN_URL}, or the first non-blank, non-comment line of
 * {@code <gamedir>/relay-admin-url.txt}. Absent is an ordinary state — live listing and downloading
 * still work, and the search says it cannot search.</p>
 *
 * <p>The admin URL contains a secret and is never logged. Whether one was found is worth a line;
 * what it is, never.</p>
 */
public final class RelayTarget {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Overrides the file, so a run can be pointed elsewhere without editing the game directory. */
    private static final String ENV = "DUNGEONTRAIN_RELAY_ADMIN_URL";

    /** Gitignored: {@code run/} is already ignored, and this is where a dev client's game dir is. */
    private static final String FILE = "relay-admin-url.txt";

    /** Resolved once. A dev poking at the file mid-session is not worth a filesystem hit per search. */
    private static volatile String adminBase = null;

    private RelayTarget() {}

    /** Where this build's own calls go — the branch-routed cap, honouring the local-relay override. */
    public static String dev() {
        return DungeonTrain.relayBaseUrl();
    }

    /** The live cap, whatever branch this is. */
    public static String live() {
        return DungeonTrain.liveRelayBaseUrl();
    }

    /** {@link #live()} or {@link #dev()}, as the caller asked. */
    public static String of(boolean useLive) {
        return useLive ? live() : dev();
    }

    /**
     * The admin base URL for a live creator search, or {@code ""} when this machine has none
     * configured — which the search reports as "cannot search" rather than as an empty pool.
     */
    public static String adminSearchBase() {
        String cached = adminBase;
        if (cached != null) return cached;
        String resolved = resolveAdminBase();
        adminBase = resolved;
        LOGGER.info("[DungeonTrain] Relay admin base for live builder search: {}",
                resolved.isEmpty() ? "not configured" : "configured");
        return resolved;
    }

    private static String resolveAdminBase() {
        String env = System.getenv(ENV);
        if (env != null && !env.isBlank()) return trimTrailingSlash(env.trim());
        try {
            Path path = FMLPaths.GAMEDIR.get().resolve(FILE);
            if (!Files.isRegularFile(path)) return "";
            List<String> lines = Files.readAllLines(path);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                // Only something shaped like a URL counts. A file where the paste landed on the wrong
                // line — or never landed — would otherwise be reported as configured and then fail
                // every call with a DNS error, which reads like an outage rather than a typo.
                if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                    LOGGER.warn("[DungeonTrain] {} holds a line that is not an http(s) URL — "
                            + "live builder search stays off until it does", FILE);
                    return "";
                }
                return trimTrailingSlash(trimmed);
            }
        } catch (Exception e) {
            // Deliberately without the exception's message: a path or a URL could ride along in it.
            LOGGER.warn("[DungeonTrain] Could not read {} — live builder search stays off", FILE);
        }
        return "";
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
