package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.Properties;

/**
 * Shared holder for the mod version + git branch baked into the jar at build
 * time via {@code processResources} (see build.gradle). Loaded once on class
 * init and reused by every overlay/screen that needs build info, plus
 * common/server code that needs the dev-vs-release signal (e.g. dev-vs-live
 * Discord relay routing in {@code DungeonTrain.commonSetup}).
 *
 * <p><b>Do not relocate this class.</b> It is pure (a classpath resource read,
 * no client-only references) so it loads on dedicated servers too — and the
 * bundled sibling mod <b>PlayerMob</b> references it by this exact FQN
 * ({@code games.brennan.dungeontrain.client.VersionInfo}) from its
 * {@code DungeonTrainHud} compat. Moving the class is a binary-breaking change
 * for that bundled jar (NoClassDefFoundError at client setup). It lives in
 * {@code client} for historical reasons; common code referencing it is fine
 * because the class itself has no client-only dependencies.</p>
 */
public final class VersionInfo {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PROPERTIES_PATH = "/dungeontrain_version.properties";
    private static final String UNKNOWN = "?";

    public static final String VERSION;
    public static final String BRANCH;
    public static final String DISPLAY;
    /**
     * Clock hours in which any Dungeon Train repo saw a commit, de-duplicated across repos —
     * the development-hours figure the Contribute page shows. Baked by the {@code devHours}
     * closure in build.gradle; {@code 0} when the build could read no history at all, which
     * callers must treat as "unknown" and show nothing. See
     * {@link games.brennan.dungeontrain.client.support.DevHours}.
     */
    public static final int DEV_HOURS;

    static {
        String version = UNKNOWN;
        String branch = UNKNOWN;
        int devHours = 0;
        try (InputStream in = VersionInfo.class.getResourceAsStream(PROPERTIES_PATH)) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                version = props.getProperty("version", UNKNOWN);
                branch = props.getProperty("branch", UNKNOWN);
                devHours = parseHours(props.getProperty("dev_hours"));
            } else {
                LOGGER.warn("VersionInfo: resource {} not found — using fallback", PROPERTIES_PATH);
            }
        } catch (Exception e) {
            LOGGER.warn("VersionInfo: failed to load {} — using fallback", PROPERTIES_PATH, e);
        }
        VERSION = version;
        BRANCH = branch;
        DEV_HOURS = devHours;
        DISPLAY = "Dungeon Train v" + VERSION + " (" + BRANCH + ")";
    }

    /** Absent, blank or non-numeric all mean "unknown" — never a crash in front of a player. */
    private static int parseHours(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            LOGGER.warn("VersionInfo: unparseable dev_hours '{}' — treating as unknown", raw);
            return 0;
        }
    }

    private VersionInfo() {}
}
