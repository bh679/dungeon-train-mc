package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.Set;

/**
 * Single source of truth for the per-install user-content root.
 *
 * <p>All player-authored content (carriages, parts, contents, container loot,
 * track-side, prefabs, weights overrides) is stored under
 * {@code <minecraft>/config/dungeontrain/user/<subdir>/}. The {@code user/}
 * segment exists solely so the player can see at a glance which files came
 * with the mod jar (none — the jar's bundled data lives on the classpath)
 * and which they themselves authored. It also makes the
 * {@link UserContentExporter} a one-line directory walk.</p>
 *
 * <p>Keeping the literal {@code "user"} segment in one place means there is
 * exactly one spot to update if the layout ever shifts again — every store
 * goes through {@link #dir(String)} rather than concatenating its own path.</p>
 *
 * <p>Out of scope: per-world ship persistence ({@code carriage-persist/}),
 * engine TOML configs ({@code dungeontrain-client.toml},
 * {@code dungeontrain-server.toml}), bundled classpath data
 * ({@code /data/dungeontrain/...}), and dev-mode source-tree writes
 * ({@code src/main/resources/data/dungeontrain/...}). None of these are user
 * content — they belong outside this folder.</p>
 */
public final class UserContentPaths {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String DUNGEONTRAIN = "dungeontrain";
    private static final String USER = "user";
    private static final String IMPORTED = "imported";

    private UserContentPaths() {}

    /**
     * {@code <config>/dungeontrain/user/} — the root the exporter walks and
     * the migration writes into.
     */
    public static Path root() {
        return FMLPaths.CONFIGDIR.get().resolve(DUNGEONTRAIN).resolve(USER);
    }

    /**
     * {@code <config>/dungeontrain/user/<subSlug>} where {@code subSlug} is
     * a kind-specific sub-path like {@code "templates"},
     * {@code "prefabs/loot"}, or {@code "parts/cab"}.
     */
    public static Path dir(String subSlug) {
        return root().resolve(subSlug);
    }

    /**
     * {@code <config>/dungeontrain/} — the legacy root used pre-0.125 and
     * still home to engine config / per-world data. Exposed only so the
     * {@link UserContentMigration} helper can locate the legacy layout when
     * moving files; stores should not write here.
     */
    public static Path legacyRoot() {
        return FMLPaths.CONFIGDIR.get().resolve(DUNGEONTRAIN);
    }

    /**
     * Pre-0.125 location for {@code subSlug}. Used by
     * {@link UserContentMigration} to find files to move; not a write target.
     */
    public static Path legacyDir(String subSlug) {
        return legacyRoot().resolve(subSlug);
    }

    // ---- Imported-content layout (parallel to user/) ----

    /**
     * Root of the imported-content tree:
     * {@code <config>/dungeontrain/imported/}. Each subdirectory is one
     * package — its name comes from the source zip's filename (minus the
     * {@code .zip} suffix, sanitised). The package's contents mirror the
     * user-folder layout one-for-one, so a player can compare their saved
     * version to the imported version side by side.
     */
    public static Path importedRoot() {
        return FMLPaths.CONFIGDIR.get().resolve(DUNGEONTRAIN).resolve(IMPORTED);
    }

    /**
     * Directory for a specific imported package — extracted zip lives at
     * {@code <config>/dungeontrain/imported/<packageName>/<subSlug>/...}.
     * Used by {@link UserContentImporter} when laying down extracted
     * files and by the search-path helpers when resolving loads.
     */
    public static Path importedPackageDir(String packageName, String subSlug) {
        return importedRoot().resolve(packageName).resolve(subSlug);
    }

    /**
     * Every package directory directly under {@link #importedRoot()}, sorted
     * alphabetically for deterministic load order. Empty list when no zips
     * have been imported yet, or when the import root itself doesn't
     * exist. Skips regular files (the player might drop a {@code README.txt}
     * inside the root by accident).
     */
    public static List<Path> importedPackageDirs() {
        Path root = importedRoot();
        if (!Files.isDirectory(root)) return Collections.emptyList();
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) out.add(entry);
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Couldn't enumerate imported packages at {}: {}",
                root, e.toString());
            return Collections.emptyList();
        }
        out.sort((a, b) -> a.getFileName().toString()
            .compareToIgnoreCase(b.getFileName().toString()));
        return out;
    }

    /**
     * Directories to search for {@code subSlug}, in priority order:
     * {@code user/<subSlug>/} first, then each
     * {@code imported/<pkg>/<subSlug>/} alphabetically. Used by per-store
     * loads (first existing wins) and registry scans (union of basenames).
     *
     * <p>The user folder is always element 0 even when it doesn't exist —
     * callers can iterate the result and ignore non-directories. Keeping
     * the slot stable means the caller doesn't have to special-case
     * "what if user/ is empty" separately from "what if no packages
     * exist".</p>
     */
    public static List<Path> searchDirs(String subSlug) {
        List<Path> out = new ArrayList<>();
        out.add(dir(subSlug));
        for (Path pkg : importedPackageDirs()) {
            out.add(pkg.resolve(subSlug));
        }
        return out;
    }

    /**
     * First existing regular file at {@code <searchDir>/<basenameWithExt>}
     * across {@link #searchDirs(String)}, or {@code null}. Encodes the
     * "user wins, then alphabetical packages" precedence in one call so
     * stores can replace a single-tier {@code Files.isRegularFile(fileFor(id))}
     * check with a one-liner.
     */
    public static Path findFile(String subSlug, String basenameWithExt) {
        for (Path d : searchDirs(subSlug)) {
            Path candidate = d.resolve(basenameWithExt);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    /**
     * Sorted union of basenames (extension stripped, lowercased) for every
     * file ending in {@code extension} across {@link #searchDirs(String)}.
     * Used by registry reloads so customs from any imported package show
     * up in the variant list alongside customs from {@code user/}.
     *
     * <p>Returns an empty set when nothing is found. Stable order
     * (alphabetical) lets the editor's grid/menu layout stay deterministic
     * across launches.</p>
     */
    public static Set<String> listBasenamesAcrossSearchDirs(String subSlug, String extension) {
        Set<String> out = new TreeSet<>();
        for (Path d : searchDirs(subSlug)) {
            collectBasenames(d, extension, out);
        }
        return out;
    }

    /**
     * Like {@link #listBasenamesAcrossSearchDirs} but for a single search
     * dir. Exposed for stores that only care about the user-side tier
     * (e.g. delete operations target user/ exclusively).
     */
    public static Set<String> listBasenames(Path dir, String extension) {
        Set<String> out = new TreeSet<>();
        collectBasenames(dir, extension, out);
        return out;
    }

    private static void collectBasenames(Path dir, String extension, Set<String> out) {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + extension)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) continue;
                String name = file.getFileName().toString();
                if (!name.endsWith(extension)) continue;
                out.add(name.substring(0, name.length() - extension.length())
                    .toLowerCase(Locale.ROOT));
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Couldn't scan {}: {}", dir, e.toString());
        }
    }

    /**
     * Provenance of the on-disk file currently backing a given variant.
     * Result is one of:
     * <ul>
     *   <li>{@link Provenance#USER} — a file exists at
     *       {@code user/<subSlug>/<basename>}. Takes precedence over
     *       imported because saves always land in {@code user/}.</li>
     *   <li>{@link Provenance#IMPORTED} — no user copy, but at least one
     *       imported package has the file. Imported variants are the
     *       only ones that get the orange UI tint.</li>
     *   <li>{@link Provenance#BUNDLED} — neither tier has the file (the
     *       variant must come from the jar classpath).</li>
     * </ul>
     */
    public static Provenance provenanceOf(String subSlug, String basenameWithExt) {
        if (Files.isRegularFile(dir(subSlug).resolve(basenameWithExt))) {
            return Provenance.USER;
        }
        for (Path pkg : importedPackageDirs()) {
            if (Files.isRegularFile(pkg.resolve(subSlug).resolve(basenameWithExt))) {
                return Provenance.IMPORTED;
            }
        }
        return Provenance.BUNDLED;
    }

    /** Three-way provenance classification for the variant menus + plot-label panel. */
    public enum Provenance {
        BUNDLED, USER, IMPORTED;

        public boolean isUser() { return this == USER; }
        public boolean isImported() { return this == IMPORTED; }
    }
}
