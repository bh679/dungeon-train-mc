package games.brennan.dungeontrain.editor;
import games.brennan.dungeontrain.platform.DtPlatform;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Walks {@link UserContentPaths#root()} and zips its contents into
 * {@code <game>/exports/dungeontrain-export-<yyyyMMdd-HHmmss>.zip} for
 * sharing. Entry paths inside the zip are relative to the user-content root,
 * so a receiver can extract the archive straight into their own
 * {@code <config>/dungeontrain/user/} and have every template/prefab/sidecar
 * load on next server start.
 *
 * <p>Includes a top-level {@code manifest.json} with mod version, export
 * timestamp, file list and total byte count. Lets a future import tool sanity
 * check the archive before extracting.</p>
 *
 * <p>By construction the user-content root only ever contains files the
 * player saved through the editor in this install — bundled mod data lives on
 * the classpath (in the jar) and is never copied here. So the exporter
 * needs no "skip bundled" filter; everything under the root is by
 * definition user content.</p>
 *
 * <p>Stateless and pure — no Minecraft objects beyond {@link FMLPaths} and
 * {@link ModList} for resolving paths and the running mod version. Safe to
 * call from a command handler thread.</p>
 */
public final class UserContentExporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final DateTimeFormatter FILENAME_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final DateTimeFormatter MANIFEST_TIMESTAMP =
        DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static final String MANIFEST_ENTRY = "manifest.json";

    /** Result of a successful export — caller surfaces this in chat. */
    public record Result(Path zipFile, int fileCount, long totalBytes) {}

    private UserContentExporter() {}

    /**
     * Zip every file under {@link UserContentPaths#root()} into a fresh zip
     * under {@code <game>/exports/}. Returns the resulting file metadata.
     *
     * <p>Throws if the user-content root is missing or empty — there's
     * nothing to share, and producing an empty zip would confuse the player
     * more than the error does. Caller maps this to a friendly chat message.</p>
     */
    public static synchronized Result export() throws IOException {
        Path userRoot = UserContentPaths.root();
        if (!Files.isDirectory(userRoot)) {
            throw new IOException("No user content to export — " + userRoot + " does not exist yet. "
                + "Save a template via the editor first.");
        }

        List<Path> files = collectFiles(userRoot);
        if (files.isEmpty()) {
            throw new IOException("No user content to export — " + userRoot + " is empty. "
                + "Save a template via the editor first.");
        }

        Path exportsDir = DtPlatform.get().gameDir().resolve("exports");
        Files.createDirectories(exportsDir);
        Path zipFile = uniqueZipPath(exportsDir);

        long totalBytes = 0;
        int fileCount = 0;
        try (OutputStream raw = Files.newOutputStream(zipFile);
             ZipOutputStream zip = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {

            writeManifest(zip, files, userRoot);

            for (Path file : files) {
                String entryName = userRoot.relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(entryName);
                try {
                    entry.setTime(Files.getLastModifiedTime(file).toMillis());
                } catch (IOException ignored) {
                    // Fall back to epoch — the zip still works, just no mtime fidelity.
                }
                zip.putNextEntry(entry);
                long copied = Files.copy(file, zip);
                zip.closeEntry();
                totalBytes += copied;
                fileCount++;
            }
        }

        LOGGER.info("[DungeonTrain] Exported {} file(s) ({} bytes) -> {}", fileCount, totalBytes, zipFile);
        return new Result(zipFile, fileCount, totalBytes);
    }

    private static List<Path> collectFiles(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) out.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                LOGGER.warn("[DungeonTrain] Skipping unreadable file during export: {} ({})", file, exc.toString());
                return FileVisitResult.CONTINUE;
            }
        });
        out.sort((a, b) -> a.toString().compareToIgnoreCase(b.toString()));
        return out;
    }

    /**
     * Resolve a non-colliding zip name under {@code dir}. Format is
     * {@code dungeontrain-export-yyyyMMdd-HHmmss[-N].zip}; the suffix only
     * appears when the second-precision timestamp already exists (e.g. two
     * exports in the same second).
     */
    private static Path uniqueZipPath(Path dir) {
        String stamp = LocalDateTime.now().format(FILENAME_TIMESTAMP);
        Path candidate = dir.resolve("dungeontrain-export-" + stamp + ".zip");
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = dir.resolve("dungeontrain-export-" + stamp + "-" + suffix + ".zip");
            suffix++;
        }
        return candidate;
    }

    private static void writeManifest(ZipOutputStream zip, List<Path> files, Path userRoot) throws IOException {
        zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
        try (Writer w = new OutputStreamWriter(new NonClosingStream(zip), StandardCharsets.UTF_8)) {
            w.write("{\n");
            w.write("  \"schemaVersion\": 1,\n");
            w.write("  \"modVersion\": \"" + escape(modVersion()) + "\",\n");
            w.write("  \"exportedAt\": \"" + LocalDateTime.now().format(MANIFEST_TIMESTAMP) + "\",\n");
            w.write("  \"files\": [");
            for (int i = 0; i < files.size(); i++) {
                String rel = userRoot.relativize(files.get(i)).toString().replace('\\', '/');
                if (i > 0) w.write(",");
                w.write("\n    \"" + escape(rel) + "\"");
            }
            w.write("\n  ]\n");
            w.write("}\n");
        }
        zip.closeEntry();
    }

    private static String modVersion() {
        return games.brennan.dungeontrain.platform.DtPlatform.get()
            .getModVersion("dungeontrain")
            .orElse("unknown");
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
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

    /**
     * Wraps a {@link ZipOutputStream} so a per-entry {@link Writer} can be
     * closed without also closing the underlying zip. Vanilla
     * {@code OutputStreamWriter.close()} would close the zip stream too,
     * which kills the rest of the archive.
     */
    private static final class NonClosingStream extends OutputStream {
        private final OutputStream delegate;

        NonClosingStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override public void write(int b) throws IOException { delegate.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { delegate.write(b, off, len); }
        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() { /* no-op: the ZipOutputStream owns lifecycle */ }
    }
}
