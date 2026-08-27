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
     * Outcome of one classpath scan.
     *
     * <p>Separates "the directory was read and held no matching files"
     * ({@code resolved=true}, empty {@code names}) from "the directory could
     * not be read at all" ({@code resolved=false}). That distinction is the
     * entire point of this type: every failure path below used to return a
     * bare empty set, which a caller could not tell apart from a legitimately
     * empty directory. A broken loader in a packaged build therefore looked
     * exactly like "this registry ships nothing" and degraded in silence —
     * which is how a whole world can generate every carriage empty with
     * nothing in the log to act on. See {@code CarriageContentsRegistry.reload()}.</p>
     *
     * @param names         basenames found — lowercased, alphabetical, immutable; empty on failure
     * @param resolved      whether the resource directory was successfully read
     * @param failureReason human-readable cause when {@code !resolved}, otherwise {@code null}
     */
    public record ScanResult(Set<String> names, boolean resolved, String failureReason) {

        // Defensive copy into a TreeSet rather than Set.copyOf: callers such as
        // CarriagePartRegistry derive a grid X-slot from iteration index and so
        // depend on the alphabetical order the scan produces. Set.copyOf leaves
        // iteration order unspecified.
        public ScanResult {
            names = Collections.unmodifiableSet(new TreeSet<>(names));
        }

        static ScanResult ok(Set<String> names) {
            return new ScanResult(names, true, null);
        }

        static ScanResult failed(String reason) {
            return new ScanResult(Collections.emptySet(), false, reason);
        }
    }

    /**
     * Enumerate every {@code .nbt} basename at {@code resourcePrefix} on the
     * classpath, returning a sorted, lowercased set. The prefix should start
     * with a slash and end with a slash, e.g. {@code "/data/dungeontrain/tracks/"}.
     *
     * <p>Returns an empty set when the prefix doesn't exist, no FileSystem
     * provider can resolve the URL, or any IO error occurs (logged) — the
     * same empty set a genuinely empty directory yields. Callers that would
     * silently degrade gameplay on an empty result must use {@link #scan}
     * instead and check {@link ScanResult#resolved()}; this overload cannot
     * tell the two apart.</p>
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
        return scanBasenames(anchor, resourcePrefix, logger, NBT_EXT);
    }

    /**
     * Extension-parameterised variant — same scan strategy, but matches an
     * arbitrary suffix (e.g. {@code ".json"}). Used by the JSON-backed prefab
     * stores; the {@code .nbt} overload preserves the original call sites.
     */
    public static Set<String> scanBasenames(Class<?> anchor, String resourcePrefix, Logger logger, String extension) {
        return scan(anchor, resourcePrefix, logger, extension).names();
    }

    /**
     * Scan reporting whether the resource directory could be read at all.
     *
     * <p>Prefer this over {@link #scanBasenames} in any registry where an
     * empty result would silently degrade gameplay — it is the only way to
     * tell "this prefix ships nothing" apart from "the loader is broken".</p>
     */
    public static ScanResult scan(Class<?> anchor, String resourcePrefix, Logger logger) {
        return scan(anchor, resourcePrefix, logger, NBT_EXT);
    }

    /** Extension-parameterised {@link #scan(Class, String, Logger)}. */
    public static ScanResult scan(Class<?> anchor, String resourcePrefix, Logger logger, String extension) {
        URL url = anchor.getResource(resourcePrefix);
        if (url == null) {
            // Previously a silent empty return, and the worst of the failure
            // paths: a resource root missing from a packaged build is
            // indistinguishable from an empty one without this line.
            logger.warn("[DungeonTrain] Bundled scan: {} is not on the classpath — degrading to no bundled variants",
                resourcePrefix);
            return ScanResult.failed("prefix " + resourcePrefix + " not on classpath");
        }

        URI uri;
        try {
            uri = url.toURI();
        } catch (URISyntaxException e) {
            logger.warn("[DungeonTrain] Bundled scan: bad URI for {}: {}", resourcePrefix, e.toString());
            return ScanResult.failed("bad URI for " + resourcePrefix + ": " + e);
        }

        // Prefer the direct Paths.get(URI) path — works for any FileSystem
        // provider already registered by the JVM or by Forge (file:, union:,
        // and similar). jar: URIs are the only mainstream scheme that
        // requires an explicit FileSystems.newFileSystem call before
        // Paths.get(uri) succeeds; we handle that as a fallback.
        try {
            return scanFileSystemDir(Paths.get(uri), resourcePrefix, logger, extension);
        } catch (FileSystemNotFoundException directMiss) {
            if ("jar".equals(uri.getScheme())) {
                return scanJarDir(uri, resourcePrefix, logger, extension);
            }
            logger.warn("[DungeonTrain] Bundled scan: no FileSystem provider for scheme '{}' at {} — degrading to no bundled variants",
                uri.getScheme(), resourcePrefix);
            return ScanResult.failed("no FileSystem provider for scheme '" + uri.getScheme() + "' at " + resourcePrefix);
        } catch (IllegalArgumentException badUri) {
            // Paths.get(uri) throws IAE when the URI is missing components
            // the registered provider needs.
            logger.warn("[DungeonTrain] Bundled scan: cannot resolve URI '{}' for {}: {}",
                uri, resourcePrefix, badUri.toString());
            return ScanResult.failed("cannot resolve URI " + uri + " for " + resourcePrefix + ": " + badUri);
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

    private static ScanResult scanFileSystemDir(Path dir, String resourcePrefix, Logger logger, String extension) {
        if (!Files.isDirectory(dir)) {
            logger.warn("[DungeonTrain] Bundled scan: {} resolved to {} which is not a directory — degrading to no bundled variants",
                resourcePrefix, dir);
            return ScanResult.failed("resolved path for " + resourcePrefix + " is not a directory");
        }
        TreeSet<String> out = new TreeSet<>();
        // No-glob form: Forge's UnionFileSystem (used when the mod ships in
        // a jar inside ModLauncher) throws UnsupportedOperationException from
        // getPathMatcher, which the glob form ("*.nbt") routes through.
        // Filtering by suffix here keeps us off that codepath while staying
        // identical for the file:/jar:/dev-mode cases.
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                String fn = file.getFileName().toString();
                if (!fn.endsWith(extension)) continue;
                out.add(stripExt(fn, extension).toLowerCase(Locale.ROOT));
            }
        } catch (IOException e) {
            // An IO error part-way through the walk means the listing is
            // incomplete. Reporting it as a failure rather than returning the
            // partial set keeps a half-loaded registry from looking healthy.
            logger.error("[DungeonTrain] Bundled scan IO error at {}: {}", resourcePrefix, e.toString());
            return ScanResult.failed("IO error at " + resourcePrefix + ": " + e);
        }
        return ScanResult.ok(out);
    }

    private static ScanResult scanJarDir(URI jarUri, String resourcePrefix, Logger logger, String extension) {
        // Try-with-resources closes the FileSystem so subsequent scans on the
        // same jar URI succeed. Re-opening a closed jar FileSystem is fine;
        // re-opening one that's still open throws FileSystemAlreadyExistsException.
        try (FileSystem fs = FileSystems.newFileSystem(jarUri, Map.of())) {
            Path dir = fs.getPath(resourcePrefix);
            if (!Files.isDirectory(dir)) {
                logger.warn("[DungeonTrain] Bundled scan: {} is not a directory inside the jar — degrading to no bundled variants",
                    resourcePrefix);
                return ScanResult.failed("path " + resourcePrefix + " is not a directory inside the jar");
            }
            TreeSet<String> out = new TreeSet<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path file : stream) {
                    String fn = file.getFileName().toString();
                    if (!fn.endsWith(extension)) continue;
                    out.add(stripExt(fn, extension).toLowerCase(Locale.ROOT));
                }
            }
            return ScanResult.ok(out);
        } catch (IOException e) {
            logger.error("[DungeonTrain] Bundled scan IO error at {} (jar): {}", resourcePrefix, e.toString());
            return ScanResult.failed("IO error at " + resourcePrefix + " (jar): " + e);
        }
    }

    private static String stripExt(String filename, String extension) {
        return filename.substring(0, filename.length() - extension.length());
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
