package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * One-shot upgrade migration that moves pre-0.125 user-authored files out of
 * {@code <config>/dungeontrain/<subdir>/} into the new
 * {@code <config>/dungeontrain/user/<subdir>/} root.
 *
 * <p>Fires on {@link ServerStartingEvent} at {@link EventPriority#HIGHEST} so
 * it runs before any store's own {@code reload()} hook scans for files. After
 * the move, the per-store reloads see the templates at the new location and
 * everything else stays the same.
 *
 * <p>Files are moved one at a time. If a single move fails, the source file
 * stays in the legacy location and the next server start retries it — no
 * partial-progress state can corrupt the user's content. Each subdirectory is
 * walked recursively so nested kinds like {@code parts/cab/} and
 * {@code pillars/top/} migrate intact.
 *
 * <p>Idempotent: once a legacy subdir is empty (or absent) the per-kind pass
 * is a no-op. Stops touching a kind entirely once the new dir already exists
 * non-empty — that means the user has saved fresh content in the new layout
 * and a half-finished migration shouldn't overwrite it.</p>
 */
public final class UserContentMigration {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Subdirs that previously lived directly under {@code dungeontrain/} and
     * now live under {@code dungeontrain/user/}. Order doesn't matter — each
     * is independent.
     */
    private static final List<String> SUBDIRS = List.of(
        "templates",
        "parts",
        "contents",
        "containers",
        "pillars",
        "tunnels",
        "tracks",
        "prefabs/loot",
        "prefabs/block_variants"
    );

    private UserContentMigration() {}

        public static void onServerStarting(net.minecraft.server.MinecraftServer server) {
        runOnce();
    }

    /**
     * Public entry point so tests + the existing
     * {@link games.brennan.dungeontrain.train.CarriageVariantRegistry#reload}
     * server-start hook can drive the move explicitly. Safe to call multiple
     * times.
     *
     * <p>Runs the older per-kind legacy renames first
     * ({@code tracks/track.nbt} → {@code tracks/default.nbt}, etc.) so by the
     * time the user-folder move walks each legacy subdir, every remaining
     * file is already at the new naming convention and the destination paths
     * line up. The same rename helpers also fire from the existing per-store
     * server-start hooks, where they're idempotent no-ops.</p>
     */
    public static synchronized void runOnce() {
        // Run in-kind legacy renames first so the user-folder move below sees
        // files at their final naming convention.
        try {
            games.brennan.dungeontrain.editor.PillarTemplateStore.migrateFromLegacyDirectory();
            games.brennan.dungeontrain.track.variant.TrackVariantStore.migrateLegacyPaths();
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] In-kind legacy migration failed: {}", e.toString());
        }

        for (String slug : SUBDIRS) {
            migrateSubdir(slug);
        }
        migrateLooseFile("weights.json");
    }

    private static void migrateSubdir(String slug) {
        Path legacy = UserContentPaths.legacyDir(slug);
        if (!Files.isDirectory(legacy)) return;
        Path dest = UserContentPaths.dir(slug);
        int moved = walkAndMove(legacy, dest, slug);
        if (moved > 0) {
            LOGGER.info("[DungeonTrain] Migrated {} file(s) from {} -> {}", moved, legacy, dest);
        }
        deleteIfEmpty(legacy);
    }

    private static void migrateLooseFile(String filename) {
        Path legacy = UserContentPaths.legacyRoot().resolve(filename);
        if (!Files.isRegularFile(legacy)) return;
        Path dest = UserContentPaths.root().resolve(filename);
        if (Files.exists(dest)) {
            LOGGER.info("[DungeonTrain] Legacy {} found at {} but destination {} already exists — skipping.",
                filename, legacy, dest);
            return;
        }
        try {
            Files.createDirectories(dest.getParent());
            Files.move(legacy, dest);
            LOGGER.info("[DungeonTrain] Migrated {} from {} -> {}", filename, legacy, dest);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to migrate {} from {} to {}: {}",
                filename, legacy, dest, e.toString());
        }
    }

    /**
     * Recursive copy-and-delete walk from {@code legacy} to {@code dest}.
     * Returns the number of files successfully moved. Skips any entry whose
     * destination already exists — the user has authored something new in the
     * post-migration layout and we mustn't clobber it.
     */
    private static int walkAndMove(Path legacy, Path dest, String label) {
        int moved = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(legacy)) {
            for (Path entry : stream) {
                Path target = dest.resolve(legacy.relativize(entry).toString());
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(target);
                    moved += walkAndMove(entry, target, label);
                    deleteIfEmpty(entry);
                } else if (Files.isRegularFile(entry)) {
                    if (Files.exists(target)) {
                        LOGGER.info(
                            "[DungeonTrain] Skipping migration of {} for {}: destination {} already exists.",
                            entry, label, target);
                        continue;
                    }
                    try {
                        Files.createDirectories(target.getParent());
                        Files.move(entry, target, StandardCopyOption.ATOMIC_MOVE);
                        moved++;
                    } catch (IOException atomicFailed) {
                        try {
                            Files.move(entry, target);
                            moved++;
                        } catch (IOException e) {
                            LOGGER.error(
                                "[DungeonTrain] Failed to migrate {} -> {}: {}",
                                entry, target, e.toString());
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to scan legacy dir {} for {}: {}",
                legacy, label, e.toString());
        }
        return moved;
    }

    /**
     * Quietly remove {@code dir} if it has no entries left after migration.
     * Leaves directories that still contain anything (e.g. weights.json files
     * that aren't subject to this migration) untouched.
     */
    private static void deleteIfEmpty(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            if (stream.iterator().hasNext()) return;
        } catch (IOException e) {
            return;
        }
        try {
            Files.delete(dir);
        } catch (IOException ignored) {
            // Best-effort; not worth surfacing to the user.
        }
    }
}
