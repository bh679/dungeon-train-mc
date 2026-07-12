package games.brennan.dungeontrain.editor;
import games.brennan.dungeontrain.platform.DtPlatform;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One-shot migration from the pre-V2 package layout (separate
 * {@code imports/}, {@code imports/installed/}, {@code exports/}, and
 * {@code <config>/dungeontrain/imported/<pkg>/}) into the unified
 * {@code <gameDir>/dtpacks/} folder.
 *
 * <p>Sentinel: the migration runs iff
 * {@link PackageRegistry#stateFile()} does not yet exist. The state file
 * is written as the migration's final step, so deleting {@code dtpacks/}
 * alone won't re-trigger this pass (the state file lives under
 * {@code <config>/dungeontrain/}, not inside {@code dtpacks/}, so it
 * survives a player nuking the packages folder).
 *
 * <p>Fires on {@link ServerStartingEvent} at {@link EventPriority#HIGH}.
 * That's after {@link UserContentMigration} ({@code HIGHEST}, which
 * pulls pre-0.125 legacy files into {@code user/}) and before
 * {@link UserContentImporter} ({@code HIGH} too — relative ordering
 * within the same priority follows registration order; both subscribers
 * are auto-registered alphabetically, putting "Dtpacks…" before
 * "UserContentImporter").
 *
 * <p>Per-file moves rather than copies: the migration is intended to be
 * one-way. A file that fails to move stays in the legacy location and
 * the next launch retries — no partial-progress state can corrupt the
 * player's content. Collisions on the destination side (two zips with
 * the same basename across {@code exports/}, {@code imports/},
 * {@code imports/installed/}) are resolved by suffixing the second with
 * {@code -2}, {@code -3}, … so both end up reachable.</p>
 */
public final class DtpacksMigration {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DtpacksMigration() {}

        public static void onServerStarting(net.minecraft.server.MinecraftServer server) {
        runOnce();
    }

    /**
     * Public entry point so tests can drive the migration explicitly.
     * Safe to call multiple times — idempotent via the state-file
     * sentinel check.
     */
    public static synchronized void runOnce() {
        if (PackageRegistry.stateFileExists()) {
            // Already migrated. Nothing to do — the registry will scan
            // dtpacks/ lazily on first read.
            return;
        }

        LOGGER.info("[DungeonTrain] dtpacks migration starting — state file absent at {}",
            PackageRegistry.stateFile());

        Path dtpacks = PackageRegistry.dtpacksRoot();
        try {
            Files.createDirectories(dtpacks);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to create {} — aborting migration: {}",
                dtpacks, e.toString());
            return;
        }

        Path gameRoot = DtPlatform.get().gameDir();
        int zipsMoved = 0;
        zipsMoved += migrateZipFolder(gameRoot.resolve("imports"), dtpacks, "imports/");
        zipsMoved += migrateZipFolder(gameRoot.resolve("imports").resolve("installed"),
            dtpacks, "imports/installed/");
        zipsMoved += migrateZipFolder(gameRoot.resolve("exports"), dtpacks, "exports/");

        int dirsMoved = migrateImportedTree(UserContentPaths.importedRoot(), dtpacks);

        LOGGER.info("[DungeonTrain] dtpacks migration complete — {} zip(s) and {} extracted package(s) moved into {}",
            zipsMoved, dirsMoved, dtpacks);

        // Initialise the registry state file so future launches skip this pass.
        PackageRegistry.initFreshAndPersist();

        // Best-effort cleanup of emptied legacy dirs. Leaves them in place if
        // anything failed to move — the next launch can retry.
        deleteIfEmpty(gameRoot.resolve("imports").resolve("installed"));
        deleteIfEmpty(gameRoot.resolve("imports"));
        deleteIfEmpty(gameRoot.resolve("exports"));
    }

    /**
     * Move every {@code *.zip} at the top level of {@code source} into
     * {@code dtpacks}. Collisions are suffixed (foo.zip, foo-2.zip, …) so
     * a zip from {@code exports/} doesn't get clobbered by one from
     * {@code imports/installed/} with the same name. Returns the number
     * of zips successfully moved.
     */
    private static int migrateZipFolder(Path source, Path dtpacks, String labelForLogs) {
        if (!Files.isDirectory(source)) return 0;
        List<Path> zips = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(source, "*.zip")) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) zips.add(entry);
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] dtpacks migration: failed to list {}: {}", source, e.toString());
            return 0;
        }
        zips.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        int moved = 0;
        for (Path zip : zips) {
            Path target = uniqueTarget(dtpacks, zip.getFileName().toString());
            try {
                Files.move(zip, target);
                LOGGER.info("[DungeonTrain] dtpacks migration: moved {}{} -> {}",
                    labelForLogs, zip.getFileName(), target.getFileName());
                moved++;
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] dtpacks migration: failed to move {} -> {}: {}",
                    zip, target, e.toString());
            }
        }
        return moved;
    }

    /**
     * Move each {@code <importedRoot>/<pkg>/} folder into
     * {@code <dtpacks>/<pkg>/}. If the destination already exists (e.g.
     * an exports/foo.zip migrated first and got extracted into
     * dtpacks/foo/ by an earlier launch), suffix the directory name to
     * keep both.
     */
    private static int migrateImportedTree(Path importedRoot, Path dtpacks) {
        if (!Files.isDirectory(importedRoot)) return 0;
        int moved = 0;
        List<Path> pkgDirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(importedRoot)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) pkgDirs.add(entry);
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] dtpacks migration: failed to list {}: {}",
                importedRoot, e.toString());
            return 0;
        }
        pkgDirs.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        for (Path src : pkgDirs) {
            String stem = src.getFileName().toString();
            Path target = uniqueDirTarget(dtpacks, stem);
            try {
                Files.move(src, target);
                LOGGER.info("[DungeonTrain] dtpacks migration: moved imported/{}/ -> {}/",
                    stem, target.getFileName());
                moved++;
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] dtpacks migration: failed to move {} -> {}: {}",
                    src, target, e.toString());
            }
        }
        deleteIfEmpty(importedRoot);
        return moved;
    }

    /**
     * Resolve a non-colliding file name under {@code dir}. If
     * {@code basename} is free, return {@code dir/basename}; otherwise
     * insert {@code -2}, {@code -3}, … before the extension.
     */
    private static Path uniqueTarget(Path dir, String basename) {
        Path candidate = dir.resolve(basename);
        if (!Files.exists(candidate)) return candidate;
        int dot = basename.lastIndexOf('.');
        String stem = dot > 0 ? basename.substring(0, dot) : basename;
        String ext = dot > 0 ? basename.substring(dot) : "";
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = dir.resolve(stem + "-" + suffix + ext);
            suffix++;
        }
        return candidate;
    }

    /** Same as {@link #uniqueTarget} but for directories — no extension to split. */
    private static Path uniqueDirTarget(Path dir, String name) {
        Path candidate = dir.resolve(name);
        if (!Files.exists(candidate)) return candidate;
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = dir.resolve(name + "-" + suffix);
            suffix++;
        }
        return candidate;
    }

    /** Remove {@code dir} if it exists and contains no entries. Quiet on failure. */
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
            // Non-fatal — leftover empty dirs don't affect functionality.
        }
    }
}
