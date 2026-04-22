package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.Properties;

/**
 * Shared holder for the mod version + git branch baked into the jar at build
 * time via {@code processResources} (see build.gradle). Loaded once on class
 * init and reused by every overlay/screen that needs to show build info.
 */
public final class VersionInfo {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PROPERTIES_PATH = "/dungeontrain_version.properties";
    private static final String UNKNOWN = "?";

    public static final String VERSION;
    public static final String BRANCH;
    public static final String DISPLAY;

    static {
        String version = UNKNOWN;
        String branch = UNKNOWN;
        try (InputStream in = VersionInfo.class.getResourceAsStream(PROPERTIES_PATH)) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                version = props.getProperty("version", UNKNOWN);
                branch = props.getProperty("branch", UNKNOWN);
            } else {
                LOGGER.warn("VersionInfo: resource {} not found — using fallback", PROPERTIES_PATH);
            }
        } catch (Exception e) {
            LOGGER.warn("VersionInfo: failed to load {} — using fallback", PROPERTIES_PATH, e);
        }
        VERSION = version;
        BRANCH = branch;
        DISPLAY = "Dungeon Train v" + VERSION + " (" + BRANCH + ")";
    }

    private VersionInfo() {}
}
