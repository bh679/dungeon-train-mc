package games.brennan.dungeontrain.data;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.data.PlayerDataPaths.Kind;
import games.brennan.dungeontrain.data.PlayerDataPaths.Relocation;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Moves the player's data out of {@code config/} and into {@code <gameDir>/dungeontrain/}.
 *
 * <p>The move itself is the fix for the pack-update data loss described on
 * {@link PlayerDataPaths}. This class is the one-shot that gets existing installs there.</p>
 *
 * <p><b>It cannot lose data, by construction.</b> Files move one at a time; a file whose
 * destination already exists is left where it is rather than overwriting newer content; a move that
 * fails leaves the source untouched so the next launch retries it; and directories are only deleted
 * once demonstrably empty. On top of that, {@link PlayerDataBackup} takes a snapshot of the legacy
 * tree <em>before</em> the first file moves — the rule
 * {@code AisDataIntegrity.restoreDefaults} set: no backup, no write.</p>
 *
 * <p>Idempotent, so it is safe to call on every server start, and it is: a second run finds nothing
 * left in {@code config/} and returns immediately.</p>
 *
 * <p><b>Ordering.</b> {@code UserContentMigration} (pre-0.125:
 * {@code config/dungeontrain/<kind>/} → the user-content root) must finish first, or its source
 * files would still be sitting in {@code config/} when this pass walks past them. Rather than rely
 * on two {@code HIGHEST} subscribers resolving in a particular order, {@link #runOnce()} calls it
 * directly — it is {@code synchronized} and idempotent, so the duplicate call costs nothing.
 * {@code DtpacksMigration} runs afterwards at {@code HIGH}, and reads its sentinel through
 * {@code PackageRegistry.stateFileExists()}, which checks both locations.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlayerDataMigration {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Set once a run has completed in this JVM, so repeated server starts don't re-walk. */
    private static volatile boolean ranThisSession = false;

    /** What one migration run did. */
    public record Result(int movedFiles, int skippedExisting, int failed) {
        public boolean movedAnything() { return movedFiles > 0; }
        public boolean clean() { return failed == 0; }
    }

    private PlayerDataMigration() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onServerStarting(ServerStartingEvent event) {
        runOnce();
    }

    /**
     * Migrate this install, at most once per game session. Never throws: player data must not be
     * able to take the game down, and an install that fails to migrate still works — every
     * relocated store falls back to reading its {@code config/} copy
     * ({@link PlayerDataPaths#readPath}).
     */
    public static synchronized void runOnce() {
        if (ranThisSession) return;
        ranThisSession = true;

        // Pre-0.125 layout first, so its files are at their final names before this pass walks.
        try {
            games.brennan.dungeontrain.editor.UserContentMigration.runOnce();
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Pre-0.125 user-content migration failed: {}", e.toString());
        }

        Path configDir = PlayerDataPaths.configRoot();
        Path dataRoot = PlayerDataPaths.root();
        try {
            if (!hasLegacyData(configDir)) {
                LOGGER.debug("[DungeonTrain] Player-data migration: nothing left in config/.");
                return;
            }
            backupBeforeMoving(configDir);
            Result result = migrate(configDir, dataRoot);
            LOGGER.info("[DungeonTrain] Player-data migration: moved {} file(s) out of config/ "
                + "into {} ({} already present, {} failed and left in place)",
                result.movedFiles(), dataRoot, result.skippedExisting(), result.failed());
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Player-data migration failed — data left in config/, "
                + "reading from there and retrying next launch", e);
        }
    }

    /** Is there anything still in the old {@code config/} locations? */
    public static boolean hasLegacyData(Path configDir) {
        for (Relocation relocation : PlayerDataPaths.RELOCATIONS) {
            Path legacy = relocation.legacyPath(configDir);
            if (relocation.kind() == Kind.FILE) {
                if (Files.isRegularFile(legacy)) return true;
            } else if (containsAnyFile(legacy)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Move every relocated entry from {@code configDir} into {@code dataRoot}.
     *
     * <p>Takes both roots explicitly rather than reading {@code FMLPaths}, so the whole migration
     * is drivable from a unit test against {@code @TempDir}.</p>
     */
    public static Result migrate(Path configDir, Path dataRoot) throws IOException {
        int moved = 0;
        int skipped = 0;
        int failed = 0;
        for (Relocation relocation : PlayerDataPaths.RELOCATIONS) {
            Path from = relocation.legacyPath(configDir);
            Path to = relocation.newPath(dataRoot);
            if (relocation.kind() == Kind.FILE) {
                switch (moveFile(from, to)) {
                    case MOVED -> moved++;
                    case SKIPPED -> skipped++;
                    case FAILED -> failed++;
                    case ABSENT -> { }
                }
                continue;
            }
            if (!Files.isDirectory(from)) continue;
            for (Path file : filesUnder(from)) {
                Path target = to.resolve(from.relativize(file).toString());
                switch (moveFile(file, target)) {
                    case MOVED -> moved++;
                    case SKIPPED -> skipped++;
                    case FAILED -> failed++;
                    case ABSENT -> { }
                }
            }
            deleteEmptyDirs(from);
        }
        return new Result(moved, skipped, failed);
    }

    // ---- Internals ----

    private enum MoveOutcome { MOVED, SKIPPED, FAILED, ABSENT }

    /**
     * Move one file. A destination that already exists wins — it is either newer content the player
     * made after the migration, or a previous run's work — so the source is left alone rather than
     * overwriting it. Nothing here deletes.
     */
    private static MoveOutcome moveFile(Path from, Path to) {
        if (!Files.isRegularFile(from)) return MoveOutcome.ABSENT;
        if (Files.exists(to)) {
            LOGGER.debug("[DungeonTrain] Player-data migration: {} already exists — leaving {} alone.",
                to, from);
            return MoveOutcome.SKIPPED;
        }
        try {
            Files.createDirectories(to.getParent());
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | UnsupportedOperationException atomicUnsupported) {
                // config/ and the instance root can be on different filesystems.
                Files.move(from, to);
            }
            return MoveOutcome.MOVED;
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Player-data migration: couldn't move {} -> {} ({}). "
                + "Left in place; will retry next launch.", from, to, e.toString());
            return MoveOutcome.FAILED;
        }
    }

    /** Every regular file under {@code dir}, collected up front so the walk isn't mutated mid-flight. */
    private static List<Path> filesUnder(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) files.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                LOGGER.warn("[DungeonTrain] Player-data migration: couldn't read {}: {}",
                    file, exc.toString());
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /** Any regular file anywhere beneath {@code dir}? */
    static boolean containsAnyFile(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return false;
        try (var walk = Files.walk(dir)) {
            return walk.anyMatch(Files::isRegularFile);
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /**
     * Remove {@code dir} and its subdirectories, but only the ones that are empty — a directory
     * still holding a file we failed to move stays, and so does everything above it.
     */
    private static void deleteEmptyDirs(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            for (Path child : children) {
                if (Files.isDirectory(child)) deleteEmptyDirs(child);
            }
        } catch (IOException e) {
            return;
        }
        try (DirectoryStream<Path> remaining = Files.newDirectoryStream(dir)) {
            if (remaining.iterator().hasNext()) return;
        } catch (IOException e) {
            return;
        }
        try {
            Files.delete(dir);
        } catch (IOException ignored) {
            // An empty directory we can't remove is harmless — it just sits there.
        }
    }

    /**
     * Snapshot the legacy tree before anything moves. A failure here <b>aborts the migration</b>:
     * data that can't be backed up doesn't get touched, and the install keeps working off its
     * {@code config/} copies until the next launch.
     */
    private static void backupBeforeMoving(Path configDir) throws IOException {
        List<PlayerDataBackup.Source> sources = new ArrayList<>();
        for (Relocation relocation : PlayerDataPaths.RELOCATIONS) {
            if (relocation.kind() != Kind.DIRECTORY) continue;
            Path legacy = relocation.legacyPath(configDir);
            if (Files.isDirectory(legacy)) {
                sources.add(new PlayerDataBackup.Source(relocation.newRelative(), legacy));
            }
        }
        if (sources.isEmpty()) return;
        PlayerDataBackup.create(PlayerDataPaths.backupsRoot(), sources,
            "pre-migration", games.brennan.dungeontrain.client.VersionInfo.VERSION);
    }
}
