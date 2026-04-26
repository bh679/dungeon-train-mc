package games.brennan.dungeontrain.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shared classpath-scanning utility for the four template registries
 * (carriages, contents, parts, track-side). Replaces hand-maintained
 * {@code manifest.json} / {@code customs.json} files: dropping a new
 * {@code .nbt} into {@code src/main/resources/data/dungeontrain/...} now
 * auto-registers it on the next server start.
 *
 * <p>Why a shared helper rather than per-registry inline code: enumerating
 * resources inside a directory on the classpath is non-trivial — the URL
 * returned by {@link Class#getResource(String)} on a folder uses a
 * {@code file:} scheme in dev mode (exploded resources under
 * {@code build/resources/main/}) and {@code jar:} when the mod is shipped as
 * a packaged jar. The {@code jar:} branch needs an open
 * {@link FileSystem} for the duration of the walk and must close it after, or
 * subsequent scans on the same URI throw. Concentrating that quirk here means
 * each registry is a one-liner.</p>
 *
 * <p>All public methods are pure and stateless — safe to call from any
 * registry's {@code reload()} on {@code ServerStartingEvent}.</p>
 */
public final class BundledNbtScanner {

    /** {@code .nbt} extension — used for both glob match and basename strip. */
    private static final String NBT_EXT = ".nbt";

    private BundledNbtScanner() {}

    /**
     * Enumerate every {@code .nbt} basename at {@code resourcePrefix} on the
     * classpath, returning a sorted, lowercased set. The prefix should start
     * with a slash and end with a slash, e.g. {@code "/data/dungeontrain/tracks/"}.
     *
     * <p>Returns an empty set when the prefix doesn't exist, no FileSystem
     * provider can resolve the URL, or any IO error occurs (logged). Empty
     * is a valid result — it means "no bundled variants for this kind" and
     * lets callers degrade gracefully to the synthetic default.</p>
     *
     * <p>Resolution strategy:
     * <ol>
     *   <li>{@link Paths#get(URI)} for any scheme whose FileSystem provider
     *       is already registered. Covers {@code file:} (dev mode, exploded
     *       resources) and {@code union:} (Forge's UnionFileSystem, used
     *       when the mod is loaded from a jar inside ModLauncher's
     *       transformer pipeline) without special-casing.</li>
     *   <li>{@link FileSystems#newFileSystem(URI, Map)} for {@code jar:}
     *       URIs, which require explicit FileSystem opening before
     *       {@code Paths.get} works. Closed in try-with-resources to release
     *       the jar handle for subsequent scans.</li>
     * </ol>
     *
     * @param anchor         class used as the {@code getResource} anchor —
     *                       any class on the same classloader works; pass the
     *                       calling registry's class for clarity.
     * @param resourcePrefix classpath path with leading and trailing slashes
     * @param logger         registry-specific logger so warnings appear under
     *                       the registry's prefix rather than this utility's
     */
    public static Set<String> scanBasenames(Class<?> anchor, String resourcePrefix, Logger logger) {
        URL url = anchor.getResource(resourcePrefix);
        if (url == null) return Collections.emptySet();

        URI uri;
        try {
            uri = url.toURI();
        } catch (URISyntaxException e) {
            logger.warn("[DungeonTrain] Bundled scan: bad URI for {}: {}", resourcePrefix, e.toString());
            return Collections.emptySet();
        }

        // Prefer the direct Paths.get(URI) path — works for any FileSystem
        // provider already registered by the JVM or by Forge (file:, union:,
        // and similar). jar: URIs are the only mainstream scheme that
        // requires an explicit FileSystems.newFileSystem call before
        // Paths.get(uri) succeeds; we handle that as a fallback.
        try {
            return scanFileSystemDir(Paths.get(uri), resourcePrefix, logger);
        } catch (FileSystemNotFoundException directMiss) {
            if ("jar".equals(uri.getScheme())) {
                return scanJarDir(uri, resourcePrefix, logger);
            }
            logger.warn("[DungeonTrain] Bundled scan: no FileSystem provider for scheme '{}' at {} — degrading to no bundled variants",
                uri.getScheme(), resourcePrefix);
            return Collections.emptySet();
        } catch (IllegalArgumentException badUri) {
            // Paths.get(uri) throws IAE when the URI is missing components
            // the registered provider needs. Treat as a soft miss with a
            // diagnostic so the registry still loads.
            logger.warn("[DungeonTrain] Bundled scan: cannot resolve URI '{}' for {}: {}",
                uri, resourcePrefix, badUri.toString());
            return Collections.emptySet();
        }
    }

    /**
     * Read a bundled JSON array of strings at
     * {@code resourcePrefix + manifestFilename}, returning a sorted,
     * lowercased set. Returns empty when the manifest is missing or
     * malformed (logged). Used as a transitional cross-check for registries
     * that previously relied on hand-maintained manifests.
     */
    public static Set<String> readManifestBasenames(
        Class<?> anchor, String resourcePrefix, String manifestFilename, Logger logger
    ) {
        String resource = resourcePrefix + manifestFilename;
        try (InputStream in = anchor.getResourceAsStream(resource)) {
            if (in == null) return Collections.emptySet();
            JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (!root.isJsonArray()) {
                logger.warn("[DungeonTrain] Bundled manifest {} is not a JSON array — ignoring", resource);
                return Collections.emptySet();
            }
            JsonArray arr = root.getAsJsonArray();
            TreeSet<String> out = new TreeSet<>();
            for (JsonElement el : arr) {
                if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) continue;
                out.add(el.getAsString().toLowerCase(Locale.ROOT));
            }
            return out;
        } catch (Exception e) {
            logger.error("[DungeonTrain] Failed to read bundled manifest {}: {}", resource, e.toString());
            return Collections.emptySet();
        }
    }

    /**
     * Log WARN entries for any drift between the classpath scan and the
     * legacy manifest. Symmetric: an entry only in {@code scanned} means the
     * manifest is missing it (silently auto-discovered now), an entry only in
     * {@code manifest} means the manifest references a file that's no longer
     * on the classpath. Both are real bugs worth surfacing before the
     * follow-up commit deletes the manifests.
     *
     * <p>No-op when {@code manifest} is empty — registries that never had a
     * manifest (e.g. track-side) skip the cross-check entirely without
     * special-casing in the call site.</p>
     */
    public static void warnDrift(String label, Set<String> scanned, Set<String> manifest, Logger logger) {
        if (manifest.isEmpty()) return;
        for (String name : scanned) {
            if (!manifest.contains(name)) {
                logger.warn("[DungeonTrain] Bundled drift in {}: '{}' scanned but not in manifest", label, name);
            }
        }
        for (String name : manifest) {
            if (!scanned.contains(name)) {
                logger.warn("[DungeonTrain] Bundled drift in {}: '{}' in manifest but no .nbt on classpath", label, name);
            }
        }
    }

    private static Set<String> scanFileSystemDir(Path dir, String resourcePrefix, Logger logger) {
        if (!Files.isDirectory(dir)) return Collections.emptySet();
        TreeSet<String> out = new TreeSet<>();
        // No-glob form: Forge's UnionFileSystem (used when the mod ships in
        // a jar inside ModLauncher) throws UnsupportedOperationException from
        // getPathMatcher, which the glob form ("*.nbt") routes through.
        // Filtering by suffix here keeps us off that codepath while staying
        // identical for the file:/jar:/dev-mode cases.
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                String fn = file.getFileName().toString();
                if (!fn.endsWith(NBT_EXT)) continue;
                out.add(stripExt(fn).toLowerCase(Locale.ROOT));
            }
        } catch (IOException e) {
            logger.error("[DungeonTrain] Bundled scan IO error at {}: {}", resourcePrefix, e.toString());
        }
        return out;
    }

    private static Set<String> scanJarDir(URI jarUri, String resourcePrefix, Logger logger) {
        // Try-with-resources closes the FileSystem so subsequent scans on the
        // same jar URI succeed. Re-opening a closed jar FileSystem is fine;
        // re-opening one that's still open throws FileSystemAlreadyExistsException.
        try (FileSystem fs = FileSystems.newFileSystem(jarUri, Map.of())) {
            Path dir = fs.getPath(resourcePrefix);
            if (!Files.isDirectory(dir)) return Collections.emptySet();
            TreeSet<String> out = new TreeSet<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path file : stream) {
                    String fn = file.getFileName().toString();
                    if (!fn.endsWith(NBT_EXT)) continue;
                    out.add(stripExt(fn).toLowerCase(Locale.ROOT));
                }
            }
            return out;
        } catch (IOException e) {
            logger.error("[DungeonTrain] Bundled scan IO error at {} (jar): {}", resourcePrefix, e.toString());
            return Collections.emptySet();
        }
    }

    private static String stripExt(String filename) {
        return filename.substring(0, filename.length() - NBT_EXT.length());
    }

    /**
     * Helper for callers wanting to preserve insertion order while still
     * de-duping — used by {@link games.brennan.dungeontrain.editor.CarriagePartRegistry}
     * whose grid X-slot is index-derived and so needs deterministic ordering.
     * The scanner already returns alphabetical via {@link TreeSet}; this
     * helper just adapts to {@link LinkedHashSet} where the caller wants it.
     */
    public static LinkedHashSet<String> asInsertionOrdered(Set<String> sorted) {
        return new LinkedHashSet<>(sorted);
    }
}
