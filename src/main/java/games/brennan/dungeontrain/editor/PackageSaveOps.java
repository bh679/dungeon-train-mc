package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Filesystem primitives behind {@link PackageRegistry#saveCurrent(String)}.
 *
 * <p>Every operation here is <b>additive</b>. Nothing in this class moves,
 * overwrites, or deletes a player's content: a save that can't complete
 * leaves the disk exactly as it found it. That's the whole point of the
 * class — the previous implementation saved by moving folders and deleting
 * zips, which is how "Save" ended up destroying packages people had spent
 * hours building.</p>
 *
 * <p>Deliberately free of {@code FMLPaths} / Minecraft types: every entry
 * point takes explicit {@link Path}s, so the logic is directly drivable
 * from JUnit with {@code @TempDir} rather than needing a Forge bootstrap.
 * {@link PackageRegistry} supplies the real roots.</p>
 */
public final class PackageSaveOps {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Suffix for the in-progress zip. Replaced onto the real name only once complete. */
    private static final String TMP_SUFFIX = ".tmp";

    /** Package metadata entry written at the top level of every saved zip. */
    private static final String MANIFEST_ENTRY = "manifest.json";

    private PackageSaveOps() {}

    // ---- Name availability ----

    /**
     * Whether {@code name} is already claimed under {@code dtpacksRoot} —
     * by a working folder, by a {@code .zip} snapshot, or by both.
     *
     * <p>The zip half matters: {@link PackageRegistry} deliberately skips
     * a zip that has no extracted sibling folder, so a registry lookup
     * alone reports such a name as free. Saving over it used to delete a
     * shared pack the player had dropped in but not yet reloaded.</p>
     */
    public static boolean nameTaken(Path dtpacksRoot, String name) {
        return Files.exists(dtpacksRoot.resolve(name))
            || Files.exists(dtpacksRoot.resolve(name + ".zip"));
    }

    // ---- Copying ----

    /** Outcome of a {@link #copyTree} call. {@code failures} is empty on a clean copy. */
    public record CopyReport(int copied, int skippedExisting, List<String> failures) {
        public boolean clean() { return failures.isEmpty(); }
    }

    /**
     * Recursively copy every regular file under {@code from} into
     * {@code to}, preserving relative layout and last-modified times.
     *
     * <p>The source is never modified. A destination file that already
     * exists is left alone and counted in {@code skippedExisting} — we
     * never overwrite content that's already in the target package.
     * Per-file failures are collected rather than thrown so one unreadable
     * file can't abort the whole save; the caller decides what a non-empty
     * failure list means.</p>
     */
    public static CopyReport copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) return new CopyReport(0, 0, List.of());
        Files.createDirectories(to);
        int[] counts = {0, 0};
        List<String> failures = new ArrayList<>();
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                Path target = to.resolve(from.relativize(file).toString());
                try {
                    if (Files.exists(target)) {
                        counts[1]++;
                        return FileVisitResult.CONTINUE;
                    }
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                    counts[0]++;
                } catch (IOException e) {
                    failures.add(from.relativize(file) + " (" + e.getMessage() + ")");
                    LOGGER.warn("[DungeonTrain] save: couldn't copy {} -> {}: {}",
                        file, target, e.toString());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                failures.add(from.relativize(file) + " (" + exc.getMessage() + ")");
                LOGGER.warn("[DungeonTrain] save: couldn't read {}: {}", file, exc.toString());
                return FileVisitResult.CONTINUE;
            }
        });
        return new CopyReport(counts[0], counts[1], List.copyOf(failures));
    }

    /**
     * Whether every regular file under {@code from} has a counterpart of
     * the same size under {@code to}. Cheap post-condition on
     * {@link #copyTree} — a save only reports success once the destination
     * demonstrably holds the content, so the player is never told their
     * work is safe when it isn't.
     */
    public static boolean verifyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) return true;
        boolean[] ok = {true};
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                Path target = to.resolve(from.relativize(file).toString());
                try {
                    if (!Files.isRegularFile(target) || Files.size(target) != attrs.size()) {
                        ok[0] = false;
                        LOGGER.warn("[DungeonTrain] save: verify failed for {}", target);
                        return FileVisitResult.TERMINATE;
                    }
                } catch (IOException e) {
                    ok[0] = false;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return ok[0];
    }

    /**
     * Best-effort recursive delete, used only to clean up a destination
     * folder this save just created and then failed to fill. Never call it
     * on a directory that existed beforehand — an orphaned half-copy would
     * otherwise register as a package and start shadowing real templates.
     */
    static void deleteRecursivelyQuietly(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try { Files.deleteIfExists(file); } catch (IOException ignored) { }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) {
                    try { Files.deleteIfExists(d); } catch (IOException ignored) { }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] save: couldn't clean up partial copy at {}: {}",
                dir, e.toString());
        }
    }

    // ---- Zipping ----

    /**
     * Write {@code source} to {@code zipPath} as a zip, via a temp file in
     * the same directory that is only moved onto the real name once the
     * archive is complete.
     *
     * <p>The previous snapshot therefore survives every failure mode: a
     * half-written archive lands on {@code <name>.zip.tmp} and is deleted,
     * leaving the old {@code <name>.zip} byte-identical. The old code
     * deleted the target first and wrote in place, so an IO error left the
     * player with no snapshot at all.</p>
     *
     * <p>Archive layout is unchanged from the previous implementation —
     * a top-level {@code manifest.json} matching what
     * {@link UserContentExporter} writes, then every file at its relative
     * path, sorted — so older clients still import these zips.</p>
     *
     * @return the number of content files written (excludes the manifest)
     */
    public static int writeZipAtomically(Path source, Path zipPath) throws IOException {
        Files.createDirectories(zipPath.getParent());
        Path tmp = zipPath.resolveSibling(zipPath.getFileName() + TMP_SUFFIX);
        try {
            int written = writeZip(source, tmp);
            replace(tmp, zipPath);
            return written;
        } finally {
            // No-op on the success path (the move consumed it); on failure this
            // is what keeps a truncated archive from being mistaken for a pack.
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    private static void replace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int writeZip(Path source, Path zipPath) throws IOException {
        List<Path> files = collectFiles(source);
        List<String> entryNames = new ArrayList<>(files.size());
        for (Path file : files) {
            entryNames.add(source.relativize(file).toString().replace('\\', '/'));
        }
        try (OutputStream raw = Files.newOutputStream(zipPath);
             ZipOutputStream zip = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
            writeManifest(zip, entryNames);
            for (int i = 0; i < files.size(); i++) {
                Path file = files.get(i);
                ZipEntry entry = new ZipEntry(entryNames.get(i));
                try { entry.setTime(Files.getLastModifiedTime(file).toMillis()); }
                catch (IOException ignored) { /* fall back to epoch */ }
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
        return files.size();
    }

    /** Every regular file under {@code source}, sorted for a deterministic archive. */
    private static List<Path> collectFiles(Path source) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(source)) return files;
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort((a, b) -> a.toString().compareToIgnoreCase(b.toString()));
        return files;
    }

    private static void writeManifest(ZipOutputStream zip, List<String> entryNames) throws IOException {
        zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schemaVersion\": 1,\n");
        sb.append("  \"savedAt\": \"")
            .append(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            .append("\",\n");
        sb.append("  \"files\": [");
        for (int i = 0; i < entryNames.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\n    \"").append(escape(entryNames.get(i))).append("\"");
        }
        if (!entryNames.isEmpty()) sb.append("\n  ");
        sb.append("]\n");
        sb.append("}\n");
        zip.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
