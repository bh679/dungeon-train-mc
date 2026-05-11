package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Auto-import companion to {@link UserContentExporter}. On every server
 * start, walks {@code <game>/imports/*.zip} and extracts each archive into
 * {@link UserContentPaths#root()} so a player can share content just by
 * dropping the zip into a watched folder.
 *
 * <p>Lifecycle per launch:
 * <ol>
 *   <li>Ensure the {@code <game>/imports/} drop-zone exists (creates it
 *       with a {@code README.txt} on first run so the player can find
 *       it).</li>
 *   <li>For each {@code *.zip} at the top level of the folder:
 *       <ol type="a">
 *         <li>Open the zip read-only.</li>
 *         <li>For every entry:
 *           <ul>
 *             <li>Skip the top-level {@code manifest.json} (metadata, not
 *                 content).</li>
 *             <li>Reject entries whose normalised path escapes the
 *                 destination root (zip-slip guard).</li>
 *             <li>Skip entries whose destination file already exists —
 *                 the importer never overwrites existing user content. The
 *                 player can move the conflicting file aside and re-import
 *                 if they want the package's version.</li>
 *             <li>Otherwise copy the bytes to
 *                 {@code <config>/dungeontrain/user/<entry-path>},
 *                 creating intermediate directories as needed.</li>
 *           </ul>
 *         </li>
 *         <li>On success, move the zip to {@code <game>/imports/installed/}
 *             so the next launch doesn't re-import it.</li>
 *         <li>On failure (corrupt zip, IO error mid-extract), leave the
 *             zip in {@code imports/} and log the cause — the player can
 *             inspect or retry.</li>
 *       </ol>
 *   </li>
 * </ol>
 *
 * <p>Fires on {@link ServerStartingEvent} at {@link EventPriority#HIGH} so
 * it runs <i>after</i> {@link UserContentMigration} (HIGHEST) — pre-0.125
 * legacy files have already been moved into the user-content root by the
 * time we extract — and <i>before</i> the per-store {@code reload()}
 * handlers (NORMAL) so the newly-imported templates are visible to the
 * registry scan that follows.</p>
 *
 * <p>Conflict policy is "skip, don't overwrite" because the importer can't
 * tell whether the existing file represents the player's own edits or a
 * stale leftover. Erring on the side of preserving in-place work means a
 * player who has been authoring locally never has their changes silently
 * clobbered by an imported package with overlapping ids.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class UserContentImporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Drop-zone for zips that should be auto-extracted into the user folder on launch. */
    static final String IMPORTS_SUBDIR = "imports";

    /** Sub-folder of {@link #IMPORTS_SUBDIR} where successfully-imported zips are moved. */
    static final String INSTALLED_SUBDIR = "installed";

    /** Filename for the README written into a freshly-created imports folder. */
    private static final String README_FILENAME = "README.txt";

    private static final String README_BODY =
        "Drop Dungeon Train package zips (exports/dungeontrain-export-*.zip) into\n"
            + "this folder. On the next server start the mod will:\n"
            + "  1. Extract every template inside the zip into\n"
            + "     <minecraft>/config/dungeontrain/user/\n"
            + "  2. Move the zip into the 'installed/' sub-folder so it isn't\n"
            + "     re-imported on subsequent launches.\n"
            + "\n"
            + "Existing files under config/dungeontrain/user/ are NEVER overwritten —\n"
            + "if the import skips a file, the on-disk copy you already had wins.\n";

    /** Skipped on import — it's metadata about the package, not a template payload. */
    private static final String MANIFEST_ENTRY = "manifest.json";

    private UserContentImporter() {}

    /** Result of a single zip import — caller uses this for logging + chat surfacing. */
    public record ZipResult(Path zipFile, int imported, int skipped, int rejected,
                            List<String> warnings) {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onServerStarting(ServerStartingEvent event) {
        try {
            importAll();
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Import scan failed: {}", e.toString());
        }
    }

    /**
     * Public entry point so tests + manual {@code /dt} commands can drive
     * the scan without waiting for a server restart. Iterates the import
     * folder and returns one {@link ZipResult} per zip processed (empty
     * list when the folder is empty or absent).
     */
    public static synchronized List<ZipResult> importAll() {
        Path importsDir = FMLPaths.GAMEDIR.get().resolve(IMPORTS_SUBDIR);
        ensureReadme(importsDir);

        if (!Files.isDirectory(importsDir)) return List.of();

        Path installedDir = importsDir.resolve(INSTALLED_SUBDIR);
        Path importedRoot = UserContentPaths.importedRoot();
        try {
            Files.createDirectories(importedRoot);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to ensure imported-content root {}: {}",
                importedRoot, e.toString());
            return List.of();
        }

        // Process zips from BOTH the top-level drop-zone AND the installed/
        // subfolder. Previously-installed zips get reprocessed when their
        // imported/<package>/ folder is missing (auto-recovery path: e.g.
        // the player deleted the package dir to revert, or the layout
        // changed in a mod upgrade and the old install needs re-extraction).
        // Per-file skip-if-exists in importOne() keeps it cheap when the
        // package is already extracted — every file is a no-op skip.
        List<Path> inboxZips = listZipsAtTopLevel(importsDir);
        List<Path> archivedZips = listZipsAtTopLevel(installedDir);
        if (inboxZips.isEmpty() && archivedZips.isEmpty()) return List.of();

        java.util.Comparator<Path> byName = (a, b) -> a.getFileName().toString()
            .compareToIgnoreCase(b.getFileName().toString());
        inboxZips.sort(byName);
        archivedZips.sort(byName);

        List<ZipResult> results = new ArrayList<>();
        // Inbox zips first — they're the canonical "new arrivals", and on
        // success they move into installed/. Archived zips next — those
        // already live in installed/, so we only re-extract when their
        // imported/<package>/ folder is missing.
        for (Path zip : inboxZips) {
            ZipResult r = importOne(zip, importedRoot);
            results.add(r);
            if (r.imported() > 0 || r.skipped() > 0) {
                moveToInstalled(zip, installedDir);
            }
        }
        for (Path zip : archivedZips) {
            String packageName = packageNameFor(zip);
            Path packageDir = importedRoot.resolve(packageName);
            if (Files.isDirectory(packageDir)) {
                // Already extracted; nothing to recover. Don't reprocess —
                // the player intentionally moved it here.
                continue;
            }
            LOGGER.info("[DungeonTrain] Recovering archived package {} — extracted folder missing",
                zip.getFileName());
            results.add(importOne(zip, importedRoot));
        }
        return results;
    }

    private static List<Path> listZipsAtTopLevel(Path dir) {
        if (!Files.isDirectory(dir)) return new ArrayList<>();
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.zip")) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) out.add(entry);
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to scan zip dir {}: {}", dir, e.toString());
        }
        return out;
    }

    /**
     * Extract a single zip into its own subdirectory under
     * {@code importedRoot}: every package gets its own
     * {@code imported/<packageName>/...} tree so a player can compare
     * versions side by side and (eventually) prune individual packages
     * without affecting the rest. The package name is derived from the
     * zip's filename minus the {@code .zip} suffix and sanitised against
     * filesystem-illegal characters.
     *
     * <p>Files that already exist <i>inside this package's directory</i>
     * are skipped — that lets the same zip be reprocessed safely (e.g.
     * after the player moves a copy back from {@code installed/} for
     * testing). Files that exist in {@code user/} are <b>not</b> the
     * concern of this method; the load-order in
     * {@link UserContentPaths#findFile} already prefers user/ over
     * imports, so a player-edited file naturally shadows the imported
     * copy without needing a per-file conflict check here.</p>
     *
     * <p>Open-on-read with {@link ZipFile} so we don't materialise the
     * whole archive in memory — useful for the rare large package and
     * required for the zip-slip canonicalisation check, which needs the
     * destination path before any bytes are streamed.</p>
     */
    private static ZipResult importOne(Path zip, Path importedRoot) {
        String packageName = packageNameFor(zip);
        Path packageRoot = importedRoot.resolve(packageName);
        Path packageRootNorm = packageRoot.toAbsolutePath().normalize();
        int imported = 0;
        int skipped = 0;
        int rejected = 0;
        List<String> warnings = new ArrayList<>();

        try {
            Files.createDirectories(packageRoot);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to create package dir {}: {}",
                packageRoot, e.toString());
            return new ZipResult(zip, 0, 0, 0, List.of("mkdir-failed: " + e.getMessage()));
        }

        try (ZipFile zf = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                String name = entry.getName();
                if (MANIFEST_ENTRY.equalsIgnoreCase(name)) continue;

                Path dest = packageRootNorm.resolve(name).normalize();
                if (!dest.startsWith(packageRootNorm)) {
                    rejected++;
                    warnings.add("path-escape: " + name);
                    LOGGER.warn("[DungeonTrain] Rejected zip entry '{}' from {} — path escapes package root",
                        name, zip.getFileName());
                    continue;
                }

                if (Files.exists(dest)) {
                    skipped++;
                    warnings.add("skipped existing: " + name);
                    continue;
                }

                try {
                    Files.createDirectories(dest.getParent());
                    try (OutputStream out = Files.newOutputStream(dest)) {
                        zf.getInputStream(entry).transferTo(out);
                    }
                    imported++;
                } catch (IOException io) {
                    rejected++;
                    warnings.add("write-failed: " + name + " (" + io.getMessage() + ")");
                    LOGGER.error("[DungeonTrain] Failed to extract '{}' from {}: {}",
                        name, zip.getFileName(), io.toString());
                }
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Couldn't open zip {}: {}", zip, e.toString());
            return new ZipResult(zip, 0, 0, 0, List.of("open-failed: " + e.getMessage()));
        }

        LOGGER.info("[DungeonTrain] Imported package {} as '{}' — {} new file(s), {} skipped, {} rejected",
            zip.getFileName(), packageName, imported, skipped, rejected);
        return new ZipResult(zip, imported, skipped, rejected, warnings);
    }

    /**
     * Derive a safe package directory name from the zip's filename.
     * Strips the {@code .zip} extension and replaces filesystem-unfriendly
     * characters with underscores so we don't end up with a directory
     * Windows refuses to open.
     *
     * <p>Empty result after sanitising (very unlikely — the zip would
     * have to be named something like just {@code "?"}) falls back to a
     * timestamp-based name so we still get a unique directory.</p>
     */
    static String packageNameFor(Path zip) {
        String filename = zip.getFileName().toString();
        int dot = filename.toLowerCase(Locale.ROOT).lastIndexOf(".zip");
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        // Replace whitespace + filesystem-unsafe characters with underscores.
        String sanitised = stem.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        // Collapse repeats and strip leading/trailing underscores for tidiness.
        sanitised = sanitised.replaceAll("_{2,}", "_")
            .replaceAll("^_+", "").replaceAll("_+$", "");
        if (sanitised.isEmpty()) {
            sanitised = "package-" + System.currentTimeMillis();
        }
        return sanitised;
    }

    /**
     * Move {@code zip} into {@code installedDir} so the next launch's scan
     * doesn't re-process it. On collision (an earlier import of the same
     * filename), append a numeric suffix until the destination is free —
     * preserves the original name as the primary so the player can still
     * find their packages.
     */
    private static void moveToInstalled(Path zip, Path installedDir) {
        try {
            Files.createDirectories(installedDir);
            Path target = installedDir.resolve(zip.getFileName().toString());
            int suffix = 2;
            while (Files.exists(target)) {
                String name = zip.getFileName().toString();
                int dot = name.lastIndexOf('.');
                String stem = dot > 0 ? name.substring(0, dot) : name;
                String ext = dot > 0 ? name.substring(dot) : "";
                target = installedDir.resolve(stem + "-" + suffix + ext);
                suffix++;
            }
            Files.move(zip, target, StandardCopyOption.ATOMIC_MOVE);
            LOGGER.info("[DungeonTrain] Moved imported zip {} -> {}", zip.getFileName(), target);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Couldn't move imported zip {} to installed/: {} (next launch will reprocess)",
                zip.getFileName(), e.toString());
        }
    }

    /**
     * Create the imports directory + README on first run. Quiet no-op when
     * the README already exists, so we don't keep stomping a player's
     * customised note. Failures here don't block the rest of import — the
     * folder might already exist but be unwritable, in which case the
     * normal scan will skip it gracefully.
     */
    private static void ensureReadme(Path importsDir) {
        try {
            Files.createDirectories(importsDir);
            Path readme = importsDir.resolve(README_FILENAME);
            if (!Files.exists(readme)) {
                Files.writeString(readme, README_BODY, StandardCharsets.UTF_8);
                LOGGER.info("[DungeonTrain] Created imports drop-zone at {}", importsDir);
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Couldn't initialise imports dir {}: {}", importsDir, e.toString());
        }
    }

    /** Path accessor for the menu's "Open Import Folder" entry. */
    public static Path directory() {
        return FMLPaths.GAMEDIR.get().resolve(IMPORTS_SUBDIR);
    }

    /**
     * Run a full import + registry-refresh cycle now, without waiting for a
     * server restart. Used by the Package menu's Reload button and the
     * {@code /dungeontrain editor import} slash command.
     *
     * <p>Order mirrors the per-handler {@code ServerStartingEvent} sequence:
     * <ol>
     *   <li>Run the importer — extracts any new zips in
     *       {@code <game>/imports/} into the user-content root.</li>
     *   <li>Clear every editor template / sidecar cache so the next get/load
     *       re-reads from disk.</li>
     *   <li>Reload every variant registry so newly-extracted templates
     *       become addressable by id.</li>
     *   <li>Reload weights last, since weight lookups index on registry
     *       ids that the previous step just rebuilt.</li>
     * </ol>
     *
     * <p>Synchronous. Caller surfaces the {@link Summary} in chat or logs.</p>
     */
    public static synchronized Summary reloadAll() {
        List<ZipResult> imports = importAll();

        // Clear caches first so the registry reloads don't read through to
        // stale unpacked-cell or sidecar state.
        games.brennan.dungeontrain.editor.CarriageTemplateStore.clearCache();
        games.brennan.dungeontrain.editor.CarriageContentsStore.clearCache();
        games.brennan.dungeontrain.editor.CarriageVariantBlocks.clearCache();
        games.brennan.dungeontrain.editor.CarriageVariantPartsStore.clearCache();
        games.brennan.dungeontrain.editor.CarriageVariantContentsAllowStore.clearCache();
        games.brennan.dungeontrain.editor.CarriagePartTemplateStore.clearCache();
        games.brennan.dungeontrain.editor.CarriagePartVariantBlocks.clearCache();
        games.brennan.dungeontrain.editor.CarriageContentsVariantBlocks.clearCache();
        games.brennan.dungeontrain.editor.PillarTemplateStore.clearCache();
        games.brennan.dungeontrain.editor.ContainerContentsStore.clearCache();
        games.brennan.dungeontrain.track.variant.TrackVariantStore.clearCache();
        games.brennan.dungeontrain.track.variant.TrackVariantBlocks.clearCache();

        // Registries — order matches the order they fire on ServerStartingEvent.
        games.brennan.dungeontrain.track.variant.TrackVariantRegistry.reload();
        games.brennan.dungeontrain.train.CarriageVariantRegistry.reload();
        games.brennan.dungeontrain.train.CarriageContentsRegistry.reload();
        games.brennan.dungeontrain.editor.CarriagePartRegistry.reload();
        games.brennan.dungeontrain.editor.CarriageTemplateStore.reload();
        games.brennan.dungeontrain.editor.CarriageContentsStore.reload();
        games.brennan.dungeontrain.editor.LootPrefabStore.reload();
        games.brennan.dungeontrain.editor.BlockVariantPrefabStore.reload();

        // Weights last — they look up registered ids to decide whether to
        // log warnings about unknown entries.
        games.brennan.dungeontrain.train.CarriageWeights.reload();
        games.brennan.dungeontrain.train.CarriageContentsWeights.reload();
        games.brennan.dungeontrain.track.variant.TrackVariantWeights.reload();

        int imported = 0;
        int skipped = 0;
        int rejected = 0;
        for (ZipResult r : imports) {
            imported += r.imported();
            skipped += r.skipped();
            rejected += r.rejected();
        }
        LOGGER.info(
            "[DungeonTrain] Reload complete — {} package(s) processed, {} new file(s), {} skipped, {} rejected.",
            imports.size(), imported, skipped, rejected);
        return new Summary(imports.size(), imported, skipped, rejected);
    }

    /** Aggregate counts returned by {@link #reloadAll()}. */
    public record Summary(int packagesProcessed, int filesImported, int filesSkipped, int filesRejected) {}

    /** Lowercase the locale-insensitive helper a couple of call sites need. */
    @SuppressWarnings("unused")
    private static String lc(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
