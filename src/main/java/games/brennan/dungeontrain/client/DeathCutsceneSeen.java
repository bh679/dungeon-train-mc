package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether this player has ever watched the death cinematic to its end ({@link DeathMomentScreen}).
 * Until they have, it can't be skipped. One marker file under the player-data root, kept on the
 * client because the question is about this player's own eyes, not any one world or server; its
 * presence is the whole answer, so a damaged file still reads as seen.
 *
 * <p>Never throws: a failed write is logged and the flag stays set in memory for the session.</p>
 */
public final class DeathCutsceneSeen {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String FILE_NAME = "death-cutscene-seen.json";

    private static DeathCutsceneSeen instance;

    private final Path file;
    private boolean seenThisSession;

    DeathCutsceneSeen(Path file) {
        this.file = file;
    }

    /** The shared marker in {@code <gameDir>/dungeontrain/}. */
    public static DeathCutsceneSeen get() {
        if (instance == null) instance = new DeathCutsceneSeen(PlayerDataPaths.root().resolve(FILE_NAME));
        return instance;
    }

    /** True until the cinematic has once played to its end. */
    public boolean isFirstEver() {
        if (seenThisSession) return false;
        try {
            return !Files.exists(file);
        } catch (RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Couldn't check {}; treating the death cinematic as seen: {}", file, e.toString());
            return false;
        }
    }

    /** Remember that the cinematic has played to its end. */
    public void markSeen() {
        seenThisSession = true;
        if (Files.exists(file)) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"seen\":true}", StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Couldn't save {}; the next death cinematic may be unskippable again: {}",
                    file, e.toString());
        }
    }
}
