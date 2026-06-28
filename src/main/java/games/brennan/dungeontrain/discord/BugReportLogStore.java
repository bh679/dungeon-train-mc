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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Server-side archive of bug-report logs at
 * {@code <gameDir>/logs/<version>/<user>/<timestamp>/<filename>}
 * (e.g. {@code logs/0.373.0/Steve-1a2b3c4d/2026-06-27_16.05.43/latest.log.gz}). Mirrors
 * {@link games.brennan.dungeontrain.advancement.GlobalAchievementStore}'s atomic-write pattern.
 *
 * <p>Each submission lands in its own timestamped subfolder under the player's directory, so a
 * player resending logs from the same version no longer overwrites the previous report (the
 * fixed-name {@code latest.log.gz}/{@code debug.log.gz} tails used to clobber each other).</p>
 *
 * <p>Every path segment is sanitized to filesystem-safe characters, so a hostile player name or
 * filename can't escape the archive root (no traversal). On a dedicated server this gives the host a
 * durable copy of each report; in singleplayer it lands in the player's own game dir (harmless — the
 * Discord attachment is the channel that actually reaches the maintainer).</p>
 */
public final class BugReportLogStore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ROOT_DIR = "logs";
    /** Per-submission subfolder name, matching vanilla crash-file timestamp style. */
    private static final DateTimeFormatter SUBMISSION_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

    private BugReportLogStore() {}

    /** Resolve the per-player folder {@code <gameDir>/logs/<version>/<user>/} (all segments sanitized). */
    public static Path directory(UUID uuid, String playerName) {
        String version = sanitize(modVersion());
        String user = sanitize(playerName) + "-" + shortId(uuid);
        return FMLPaths.GAMEDIR.get().resolve(ROOT_DIR).resolve(version).resolve(user);
    }

    /**
     * Write each blob into a fresh per-submission subfolder ({@code <user>/<timestamp>/}, created as
     * needed). Returns that submission directory, or {@code null} if it couldn't be created. Per-file
     * failures are logged and skipped.
     */
    public static synchronized Path archive(UUID uuid, String playerName, List<LogBlob> files) {
        Path userDir = directory(uuid, playerName);
        Path dir = uniqueChild(userDir, sanitize(LocalDateTime.now().format(SUBMISSION_STAMP)));
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

    /**
     * Resolve a child of {@code parent} named {@code base}, or the first free {@code base_2},
     * {@code base_3}, … if a sibling of that name already exists. Guards against two submissions
     * landing in the same one-second timestamp window (callers hold this class's monitor, so the
     * exists-check is race-free within the process).
     */
    static Path uniqueChild(Path parent, String base) {
        Path candidate = parent.resolve(base);
        for (int n = 2; Files.exists(candidate); n++) {
            candidate = parent.resolve(base + "_" + n);
        }
        return candidate;
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
