package games.brennan.dungeontrain.client.version.compare;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.VersionInfo;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * The Dungeon Train version the Versions page compares against — the build-baked
 * {@link VersionInfo#VERSION}, unless a dev run asks to pretend otherwise.
 *
 * <p>{@code ./gradlew runClient -PmockInstalledVersion=0.800.0} sets the
 * {@code dungeontrain.mockInstalledVersion} system property so the behind state — counts,
 * cumulative notes, the boot prompt, the header — can be seen against real listings on a dev
 * build that is otherwise ahead of everything published. Nothing else in the mod reads it: the
 * title label, the GitHub check and bug reports keep the real version.</p>
 */
public final class InstalledVersion {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String MOCK_PROPERTY = "dungeontrain.mockInstalledVersion";

    private InstalledVersion() {}

    /** The version string as shown: mocked when the dev property is set, else the real one. */
    public static String display() {
        String mock = System.getProperty(MOCK_PROPERTY);
        return mock == null || mock.isBlank() ? VersionInfo.VERSION : mock.strip();
    }

    public static Optional<FullSemver> get() {
        Optional<FullSemver> parsed = FullSemver.parse(display());
        if (parsed.isEmpty()) {
            LOGGER.warn("Installed version '{}' is not X.Y.Z; the Versions page cannot count against it", display());
        }
        return parsed;
    }

    public static boolean isMocked() {
        return System.getProperty(MOCK_PROPERTY) != null;
    }
}
