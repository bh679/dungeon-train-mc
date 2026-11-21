package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client setup can run twice in one launch: when a mod fails during the first resource load,
 * vanilla rolls back the resource packs and reloads, and NeoForge runs mod setup again. Work that
 * fills a static registry which throws on repeats goes through here, so the second run is a no-op
 * and the crash report keeps naming the mod that actually failed.
 */
public final class SetupOnce {
    private static final Logger LOGGER = LogUtils.getLogger();

    private SetupOnce() {}

    /** True the first time for {@code flag}; afterwards false, with one log line naming {@code what}. */
    public static boolean first(AtomicBoolean flag, String what) {
        if (flag.compareAndSet(false, true)) return true;
        LOGGER.info("[DungeonTrain] {} already ran; client setup ran again, skipping", what);
        return false;
    }
}
