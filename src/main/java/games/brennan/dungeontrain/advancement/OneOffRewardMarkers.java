package games.brennan.dungeontrain.advancement;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

/**
 * Which one-off rewards a player has already been handed, kept OUTSIDE any world save at
 * {@code <gameDir>/dungeontrain/user/rewards/<uuid>.properties}.
 *
 * <p>Outside the world on purpose: advancements replay from the cross-world profile into every new
 * world, so a per-world flag (or an advancement's own {@code rewards}) would hand the reward out again
 * in each one. Per installation, like {@code PlayerPlayedMarker}.</p>
 */
public final class OneOffRewardMarkers {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String REWARDS_DIR = "rewards";
    private static final String EXT = ".properties";

    private final Path dir;

    public OneOffRewardMarkers(Path dir) {
        this.dir = dir;
    }

    /** The live store under the player-data root. */
    public static OneOffRewardMarkers live() {
        return new OneOffRewardMarkers(PlayerDataPaths.dir(PlayerDataPaths.USER).resolve(REWARDS_DIR));
    }

    /** True when {@code key} was already given to {@code uuid}. An unreadable file reads as given (never double-gift). */
    public boolean isGiven(UUID uuid, String key) {
        Path path = path(uuid);
        if (!Files.exists(path)) return false;
        try {
            return Boolean.parseBoolean(load(path).getProperty(key, "false"));
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Reward markers: read failed for {}, treating '{}' as given: {}", uuid, key, e.toString());
            return true;
        }
    }

    /** Record {@code key} as given. Returns false (and logs) if it could not be written. */
    public boolean markGiven(UUID uuid, String key) {
        Path path = path(uuid);
        try {
            Properties props = Files.exists(path) ? load(path) : new Properties();
            props.setProperty(key, "true");
            Files.createDirectories(dir);
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "Dungeon Train one-off rewards");
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Reward markers: write failed for {} '{}': {}", uuid, key, e.toString());
            return false;
        }
    }

    Path path(UUID uuid) {
        return dir.resolve(uuid + EXT);
    }

    private static Properties load(Path path) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        }
        return props;
    }
}
