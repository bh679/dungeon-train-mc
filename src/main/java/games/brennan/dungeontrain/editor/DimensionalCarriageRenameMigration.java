package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One-shot upgrade migration for the <b>portal room → dimensional carriage</b> rename.
 *
 * <p>The concept kept its shape and its file formats; only its name changed. Two on-disk
 * surfaces spell that name out, and both are moved here so an existing install keeps its
 * authored content:</p>
 *
 * <ul>
 *   <li>{@code portals/room/} → {@code portals/dimensional_carriage/} — the templates
 *       themselves plus every sidecar that lives beside them ({@code .variants.json},
 *       {@code .group.json}, {@code .contents-allow.json}, {@code weights.json}). The whole
 *       directory moves, so sidecars travel with their template and nothing has to know
 *       which suffixes exist.</li>
 *   <li>{@code containers/track_portal_room_<name>.contents.json} →
 *       {@code containers/track_dimensional_carriage_<name>.contents.json} — the container
 *       pool filenames, which encode the {@link games.brennan.dungeontrain.track.variant.TrackKind}
 *       id. Only the prefix changes; {@code <name>} is the author's variant name and is left
 *       exactly as it is, underscores and all.</li>
 * </ul>
 *
 * <p><b>The {@code portals/} parent deliberately stays.</b> It is the editor category's
 * directory ({@code EditorCategory.PORTALS}), and that category kept its id through the
 * rename, so every command token and snapshot key that names it is still valid. Only the leaf
 * moves.</p>
 *
 * <h2>Which roots get swept</h2>
 *
 * <p>User content is no longer a single folder: the active package, every imported package,
 * and the legacy {@code user/} root can each hold a copy of these paths. All of them are
 * swept, because a package the player has not activated yet is still content they authored
 * and expect to find intact when they switch to it.</p>
 *
 * <p>The {@code dtpacks/} and {@code imported/} roots are walked with a plain directory
 * stream rather than through {@link PackageRegistry}. Asking the registry for its package
 * list here would build and cache that list before {@link DtpacksMigration} has finished
 * populating {@code dtpacks/}, and the cached-too-early list would then be wrong for the rest
 * of the run.</p>
 *
 * <h2>Ordering</h2>
 *
 * <p>Driven from the tail of {@link UserContentMigration#runOnce()} rather than by its own
 * {@code ServerStartingEvent} subscriber — the same explicit-call pattern that file already
 * uses for {@code TrackVariantStore.migrateLegacyPaths()}. That places this pass after the
 * pre-0.125 files have been pulled into {@code user/} (so the legacy layout is already folded
 * in and needs no separate case) and still ahead of every store's {@code reload()}, which
 * runs at {@code NORMAL}.</p>
 *
 * <p>Running before {@link DtpacksMigration} is not a problem: on a first upgrade the content
 * is still in {@code user/} / {@code imported/}, gets renamed here, and is then moved into
 * {@code dtpacks/} already carrying the new names. On every later start the content is in
 * {@code dtpacks/} and is renamed there directly.</p>
 *
 * <h2>Failure behaviour</h2>
 *
 * <p>Idempotent and restartable, matching {@link UserContentMigration}'s contract: an entry
 * whose destination already exists is skipped rather than clobbered, and a move that fails
 * leaves the source where it is so the next start retries it. No partial-progress state can
 * corrupt the player's content.</p>
 */
public final class DimensionalCarriageRenameMigration {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Pre-rename template subdirectory, relative to a content root. */
    static final String LEGACY_SUBDIR = "portals/room";

    /** Post-rename template subdirectory, relative to a content root. */
    static final String NEW_SUBDIR = "portals/dimensional_carriage";

    /** Subdirectory holding container pools, whose filenames encode the kind id. */
    static final String CONTAINERS_SUBDIR = "containers";

    /** Pre-rename container-pool filename prefix ({@code track_<kindId>_}). */
    static final String LEGACY_CONTAINER_PREFIX = "track_portal_room_";

    /** Post-rename container-pool filename prefix. */
    static final String NEW_CONTAINER_PREFIX = "track_dimensional_carriage_";

    private DimensionalCarriageRenameMigration() {}

    /**
     * Sweep every content root. Safe to call repeatedly — once a root has been migrated its
     * legacy paths are absent and each per-root pass is a no-op.
     */
    public static synchronized void runOnce() {
        for (Path root : contentRoots()) {
            migrateRoot(root);
        }
    }

    /**
     * Every directory that can hold a copy of the renamed paths: the legacy {@code user/}
     * root, each imported package, and each {@code dtpacks/} working folder.
     *
     * <p>Deliberately includes packages that are disabled or not active — their content is
     * still the player's and must survive the rename.</p>
     */
    private static List<Path> contentRoots() {
        List<Path> roots = new ArrayList<>();
        roots.add(UserContentPaths.root());
        roots.addAll(UserContentPaths.importedPackageDirs());
        collectChildDirs(PackageRegistry.dtpacksRoot(), roots);
        return roots;
    }

    /** Add each immediate subdirectory of {@code parent} to {@code out}; no-op if absent. */
    private static void collectChildDirs(Path parent, List<Path> out) {
        if (!Files.isDirectory(parent)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) out.add(entry);
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Couldn't enumerate package dirs under {}: {}",
                parent, e.toString());
        }
    }

    /** Both renames for one content root. */
    private static void migrateRoot(Path root) {
        migrateTemplateDir(root);
        migrateContainerFiles(root);
    }

    /**
     * Move {@code <root>/portals/room/} to {@code <root>/portals/dimensional_carriage/},
     * merging into an existing destination rather than replacing it.
     */
    private static void migrateTemplateDir(Path root) {
        Path legacy = root.resolve(LEGACY_SUBDIR);
        if (!Files.isDirectory(legacy)) return;
        Path dest = root.resolve(NEW_SUBDIR);
        int moved = UserContentMigration.walkAndMove(legacy, dest, NEW_SUBDIR);
        if (moved > 0) {
            LOGGER.info("[DungeonTrain] Dimensional-carriage rename: moved {} file(s) {} -> {}",
                moved, legacy, dest);
        }
        UserContentMigration.deleteIfEmpty(legacy);
        // The portals/ parent is shared with nothing else today, so it is left in place
        // whether or not it is now empty — EditorCategory.PORTALS still writes into it.
    }

    /**
     * Rename {@code containers/track_portal_room_*.contents.json} in place, keeping the
     * author's variant name untouched.
     */
    private static void migrateContainerFiles(Path root) {
        Path containers = root.resolve(CONTAINERS_SUBDIR);
        if (!Files.isDirectory(containers)) return;
        int moved = 0;
        try (DirectoryStream<Path> stream =
                 Files.newDirectoryStream(containers, LEGACY_CONTAINER_PREFIX + "*")) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry)) continue;
                String name = entry.getFileName().toString();
                if (!name.startsWith(LEGACY_CONTAINER_PREFIX)) continue;
                Path target = containers.resolve(
                    NEW_CONTAINER_PREFIX + name.substring(LEGACY_CONTAINER_PREFIX.length()));
                if (Files.exists(target)) {
                    LOGGER.info("[DungeonTrain] Skipping container rename {}: {} already exists.",
                        entry, target);
                    continue;
                }
                try {
                    Files.move(entry, target);
                    moved++;
                } catch (IOException e) {
                    LOGGER.error("[DungeonTrain] Failed to rename container pool {} -> {}: {}",
                        entry, target, e.toString());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Couldn't scan {} for container renames: {}",
                containers, e.toString());
        }
        if (moved > 0) {
            LOGGER.info("[DungeonTrain] Dimensional-carriage rename: renamed {} container pool(s) in {}",
                moved, containers);
        }
    }
}
