package games.brennan.dungeontrain.net.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

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
 * <p>Three places are searched, in this order: the environment variable, the game directory, then
 * {@code ~/.config/dungeontrain/}. The last is the durable one — a dev client's game directory is
 * inside a git worktree, so a URL kept only there dies with the worktree and the next session starts
 * with live search dark for no visible reason. The per-worktree file still wins over it, so pointing
 * ONE checkout at a different relay is not silently overridden by the machine default.</p>
 *
 * <p>The admin URL contains a secret and is never logged. Whether one was found, and which of the
 * three it came from, is worth a line; what it is, never.</p>
 */
public final class RelayTarget {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Overrides the file, so a run can be pointed elsewhere without editing the game directory. */
    private static final String ENV = "DUNGEONTRAIN_RELAY_ADMIN_URL";

    /** Gitignored: {@code run/} is already ignored, and this is where a dev client's game dir is. */
    private static final String FILE = "relay-admin-url.txt";

    /** Outside every checkout, so the value survives a worktree being removed. */
    private static final String HOME_DIR = ".config/dungeontrain";

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
        return resolved;
    }

    private static String resolveAdminBase() {
        String env = System.getenv(ENV);
        if (env != null && !env.isBlank()) {
            log("the " + ENV + " environment variable");
            return trimTrailingSlash(env.trim());
        }
        // The game directory first: a checkout deliberately pointed at another relay must not be
        // quietly overridden by the machine-wide value.
        Path local = FMLPaths.GAMEDIR.get().resolve(FILE);
        String fromLocal = readUrlFrom(local);
        if (fromLocal != null) {
            if (!fromLocal.isEmpty()) log("the game directory");
            // A file that exists and holds something unusable stops the search HERE. Falling through
            // would answer with a stale machine default and make the typo in front of you invisible.
            return fromLocal;
        }
        Path home = Path.of(System.getProperty("user.home", ""), HOME_DIR, FILE);
        String fromHome = readUrlFrom(home);
        if (fromHome != null) {
            if (!fromHome.isEmpty()) log("~/" + HOME_DIR);
            return fromHome;
        }
        log("");
        return "";
    }

    /**
     * The URL in {@code path}, or {@code null} when this place has nothing to say.
     *
     * <p>Three answers, not two, and the distinction is what keeps a typo diagnosable: {@code null}
     * means "nothing here, try the next place"; {@code ""} means "something was here and it was not a
     * URL", which is a refusal the caller must not fall through. Blank lines and {@code #} comments
     * are skipped, so the file can explain itself.</p>
     */
    static String readUrlFrom(Path path) {   // package-private: the unit test drives this seam
        try {
            if (!Files.isRegularFile(path)) return null;
            for (String line : Files.readAllLines(path)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                // Only something shaped like a URL counts. A file where the paste landed on the wrong
                // line — or never landed — would otherwise be reported as configured and then fail
                // every call with a DNS error, which reads like an outage rather than a typo.
                if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                    LOGGER.warn("[DungeonTrain] {} holds a line that is not an http(s) URL — "
                            + "live builder search stays off until it does", path.getFileName());
                    return "";
                }
                return trimTrailingSlash(trimmed);
            }
            return null;   // comments only: the file is a placeholder, not an answer
        } catch (Exception e) {
            // Deliberately without the exception's message: a path or a URL could ride along in it.
            LOGGER.warn("[DungeonTrain] Could not read {} — trying the next place", path.getFileName());
            return null;
        }
    }

    /** Say whether a URL was found and where from — never what it is. */
    private static void log(String where) {
        LOGGER.info("[DungeonTrain] Relay admin base for live builder search: {}",
                where.isEmpty() ? "not configured" : "configured from " + where);
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
