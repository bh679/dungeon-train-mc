package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Auto-extract companion for the dtpacks workflow. On every server start,
 * walks {@code <gameDir>/dtpacks/*.zip} and extracts each archive into a
 * sibling working folder {@code dtpacks/<name>/} when one doesn't already
 * exist. Zips stay in place after extraction — they're the saved snapshot
 * of the corresponding working folder and the player keeps them around
 * for sharing or rollback.
 *
 * <p>Lifecycle per launch:
 * <ol>
 *   <li>Ensure {@code <gameDir>/dtpacks/} exists (created by
 *       {@link DtpacksMigration} on the very first launch; created with a
 *       README on every launch otherwise).</li>
 *   <li>For each {@code *.zip} at the top level of {@code dtpacks/}:
 *       <ol type="a">
 *         <li>If the sibling {@code dtpacks/<name>/} folder already
 *             exists, skip — the working copy is authoritative.</li>
 *         <li>Otherwise open the zip read-only and extract every entry
 *             into {@code dtpacks/<name>/<entry-path>}. Entries are
 *             zip-slip guarded; the {@code manifest.json} top-level
 *             metadata entry is skipped.</li>
 *       </ol>
 *   </li>
 *   <li>Invalidate {@link PackageRegistry} so the next read picks up the
 *       newly-extracted package folders.</li>
 * </ol>
 *
 * <p>Fires on {@link ServerStartingEvent} at {@link EventPriority#HIGH} so
 * it runs <i>after</i> {@link UserContentMigration} (HIGHEST, legacy file
 * moves) and {@link DtpacksMigration} (HIGH, layout migration), and
 * <i>before</i> the per-store {@code reload()} handlers (NORMAL) so any
 * newly-extracted templates are visible to the registry scan that
 * follows.</p>
 *
 * <p>Conflict policy is "skip if working folder exists, don't overwrite".
 * The working folder is truth during play; the zip is only a snapshot
 * (rewritten by an explicit Save). When a player wants to revert to the
 * zip, they delete the working folder first; the next launch (or a
 * Reload) re-extracts.</p>
 */
public final class UserContentImporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Filename for the README written into a freshly-created dtpacks folder. */
    private static final String README_FILENAME = "README.txt";

    private static final String README_BODY =
        "Dungeon Train packages folder.\n"
            + "\n"
            + "Each .zip in here is a saved package snapshot. Its sibling folder\n"
            + "(same basename, no .zip) is the working copy that gameplay reads from.\n"
            + "\n"
            + "Drop a .zip in here from another player and the next server start (or\n"
            + "an in-game Reload) will extract it into a folder of the same name. The\n"
            + "zip stays put — the folder is the live, editable copy.\n"
            + "\n"
            + "To revert a package to its saved snapshot, delete the folder and\n"
            + "Reload; the zip re-extracts to a fresh folder.\n";

    /** Skipped on import — it's metadata about the package, not a template payload. */
    private static final String MANIFEST_ENTRY = "manifest.json";

    private UserContentImporter() {}

    /** Result of a single zip import — caller uses this for logging + chat surfacing. */
    public record ZipResult(Path zipFile, int imported, int skipped, int rejected,
                            List<String> warnings) {}

        public static void onServerStarting(net.minecraft.server.MinecraftServer server) {
        try {
            importAll();
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Import scan failed: {}", e.toString());
        }
    }

    /**
     * Public entry point so tests + manual {@code /dt} commands can drive
     * the scan without waiting for a server restart. Walks
     * {@code <gameDir>/dtpacks/*.zip} and extracts any zip whose sibling
     * working folder is missing, returning one {@link ZipResult} per
     * extraction performed. Empty result when every zip already has a
     * companion folder (the common case).
     *
     * <p>Side effect: invalidates {@link PackageRegistry} when at least
     * one extraction happens, so the next registry read re-scans
     * {@code dtpacks/} and picks up the new package(s).</p>
     */
    public static synchronized List<ZipResult> importAll() {
        Path dtpacks = PackageRegistry.dtpacksRoot();
        ensureReadme(dtpacks);

        if (!Files.isDirectory(dtpacks)) return List.of();

        List<Path> zips = listZipsAtTopLevel(dtpacks);
        if (zips.isEmpty()) return List.of();
        zips.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));

        List<ZipResult> results = new ArrayList<>();
        for (Path zip : zips) {
            String packageName = packageNameFor(zip);
            Path workingDir = dtpacks.resolve(packageName);
            if (Files.isDirectory(workingDir)) {
                // Working folder already present — it's authoritative during
                // play. Don't re-extract; that would shadow user edits with
                // the stale snapshot.
                continue;
            }
            results.add(importOne(zip, dtpacks));
        }
        if (!results.isEmpty()) {
            // Brand-new package folders appeared — make the registry pick them
            // up on the next read.
            PackageRegistry.invalidate();
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
     * Create the dtpacks directory + README on first run. Quiet no-op
     * when the README already exists, so we don't keep stomping a
     * player's customised note. Failures here don't block the rest of
     * import — the folder might already exist but be unwritable, in
     * which case the normal scan will skip it gracefully.
     */
    private static void ensureReadme(Path dtpacks) {
        try {
            Files.createDirectories(dtpacks);
            Path readme = dtpacks.resolve(README_FILENAME);
            if (!Files.exists(readme)) {
                Files.writeString(readme, README_BODY, StandardCharsets.UTF_8);
                LOGGER.info("[DungeonTrain] Created dtpacks folder at {}", dtpacks);
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Couldn't initialise dtpacks dir {}: {}", dtpacks, e.toString());
        }
    }

    /**
     * Path accessor for menu "Open Folder" entries. Returns the unified
     * {@code <gameDir>/dtpacks/} folder — the single source of truth for
     * both archived snapshots and working copies.
     */
    public static Path directory() {
        return PackageRegistry.dtpacksRoot();
    }

    /**
     * Run a full import + registry-refresh cycle now, without waiting for a
     * server restart. Used by the Package menu's Reload button and the
     * {@code /dungeontrain editor import} slash command.
     *
     * <p>Delegates to
     * {@link games.brennan.dungeontrain.template.TemplateStores#reloadAll(boolean)}
     * which owns the full pipeline — importer, cache clears, registry
     * reloads, weights, and editor-plot-snapshot reset. Kept here as a
     * legacy entry point so existing call sites
     * ({@code dungeontrain editor import} command, Reload button) don't
     * need to change.</p>
     */
    public static synchronized Summary reloadAll() {
        games.brennan.dungeontrain.template.TemplateStores.Summary s =
            games.brennan.dungeontrain.template.TemplateStores.reloadAll(true);
        return new Summary(s.packagesProcessed(), s.filesImported(), s.filesSkipped(), s.filesRejected());
    }

    /** Aggregate counts returned by {@link #reloadAll()}. */
    public record Summary(int packagesProcessed, int filesImported, int filesSkipped, int filesRejected) {}
}
