package games.brennan.dungeontrain.discord;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.version.LauncherDetector;
import org.slf4j.Logger;

/**
 * Best-effort one-line description of the launcher the game is running under, for the
 * {@link WorldJoinReport} world-info Discord block.
 *
 * <p>Two pieces of information are folded together:</p>
 * <ul>
 *   <li><strong>Launcher type</strong> — reused from {@link LauncherDetector#source()}, which
 *       infers {@code MODRINTH}/{@code CURSEFORGE}/{@code GitHub} from the game-directory path
 *       and signature files. (That detector uses only common classes, so it is safe on the
 *       server thread despite its {@code client.version} package.)</li>
 *   <li><strong>Launcher version</strong> — the standard JVM system properties
 *       {@code minecraft.launcher.brand} / {@code minecraft.launcher.version} that a launcher
 *       may inject. The vanilla Mojang launcher sets these; most third-party apps do not, so
 *       this is shown only when present and falls back to {@code brand n/a}.</li>
 * </ul>
 *
 * <p>A dedicated server has no launcher, so it short-circuits to {@code "Dedicated server"}.
 * Any failure yields {@code "unknown"} — this is debug metadata and must never disrupt the
 * player join.</p>
 */
public final class LauncherInfo {

    private static final Logger LOGGER = LogUtils.getLogger();

    private LauncherInfo() {}

    /**
     * @param dedicatedServer whether the report is being built on a dedicated server (no launcher)
     * @return a markdown-free descriptor, e.g. {@code "CurseForge · brand minecraft v2.4.28"},
     *         {@code "Modrinth · brand n/a"}, {@code "Dedicated server"}, or {@code "unknown"}.
     */
    public static String describe(boolean dedicatedServer) {
        try {
            if (dedicatedServer) {
                return "Dedicated server";
            }
            return friendlyName(LauncherDetector.source()) + " · " + brandAndVersion();
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] launcher info detection failed: {}", t.toString());
            return "unknown";
        }
    }

    private static String friendlyName(LauncherDetector.Source source) {
        return switch (source) {
            case MODRINTH   -> "Modrinth";
            case CURSEFORGE -> "CurseForge";
            case GITHUB     -> "GitHub";
        };
    }

    private static String brandAndVersion() {
        String brand = trimmedProperty("minecraft.launcher.brand");
        String version = trimmedProperty("minecraft.launcher.version");
        if (brand.isEmpty() && version.isEmpty()) {
            return "brand n/a";
        }
        String b = brand.isEmpty() ? "?" : brand;
        return version.isEmpty() ? "brand " + b : "brand " + b + " v" + version;
    }

    private static String trimmedProperty(String key) {
        String value = System.getProperty(key);
        return value == null ? "" : value.trim();
    }
}
