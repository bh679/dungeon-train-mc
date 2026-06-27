package games.brennan.dungeontrain.discord;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.BugReportLogsPacket.LogBlob;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/**
 * Server-side archive of bug-report logs at {@code <gameDir>/logs/<version>/<user>/<filename>}
 * (e.g. {@code logs/0.373.0/Steve-1a2b3c4d/latest.log.gz}). Mirrors
 * {@link games.brennan.dungeontrain.advancement.GlobalAchievementStore}'s atomic-write pattern.
 *
 * <p>Every path segment is sanitized to filesystem-safe characters, so a hostile player name or
 * filename can't escape the archive root (no traversal). On a dedicated server this gives the host a
 * durable copy of each report; in singleplayer it lands in the player's own game dir (harmless — the
 * Discord attachment is the channel that actually reaches the maintainer).</p>
 */
public final class BugReportLogStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROOT_DIR = "logs";

    private BugReportLogStore() {}

    /** Resolve {@code <gameDir>/logs/<version>/<user>/} for this player (all segments sanitized). */
    public static Path directory(UUID uuid, String playerName) {
        String version = sanitize(modVersion());
        String user = sanitize(playerName) + "-" + shortId(uuid);
        return FMLPaths.GAMEDIR.get().resolve(ROOT_DIR).resolve(version).resolve(user);
    }

    /**
     * Write each blob into the player's archive directory (created if needed). Returns the directory,
     * or {@code null} if it couldn't be created. Per-file failures are logged and skipped.
     */
    public static synchronized Path archive(UUID uuid, String playerName, List<LogBlob> files) {
        Path dir = directory(uuid, playerName);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] BugReportLogStore: failed to create {}: {}", dir, e.getMessage());
            return null;
        }
        for (LogBlob b : files) {
            Path target = dir.resolve(sanitize(b.filename()));
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try {
                Files.write(tmp, b.bytes());
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFail) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] BugReportLogStore: failed to write {}: {}", target, e.getMessage());
            }
        }
        return dir;
    }

    /** A short {@code ./logs/<version>/<user>} display path (from the game dir down) for messages. */
    public static String displayPath(Path dir) {
        try {
            Path rel = FMLPaths.GAMEDIR.get().relativize(dir);
            return "./" + rel.toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return dir.toString();
        }
    }

    private static String modVersion() {
        return ModList.get().getModContainerById(DungeonTrain.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    private static String shortId(UUID uuid) {
        return uuid.toString().substring(0, 8);
    }

    /** Keep only filesystem-safe characters; collapse everything else to {@code _} (prevents traversal). */
    static String sanitize(String s) {
        if (s == null || s.isBlank()) {
            return "unknown";
        }
        String cleaned = s.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.equals(".") || cleaned.equals("..")) {
            return "_";
        }
        return cleaned;
    }
}
