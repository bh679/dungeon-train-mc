package games.brennan.dungeontrain.data;

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Rolling restore points for the player's data.
 *
 * <p>Relocating the data out of {@code config/} ({@link PlayerDataPaths}) stops the known way of
 * losing it. Backups are the belt to that pair of braces: launchers have deleted worlds and whole
 * instance trees before, and a player who loses a build library to <em>any</em> cause should have
 * something to go back to. One archive per launch, at most, and only when something changed.</p>
 *
 * <p>Archives land in {@code <gameDir>/dungeontrain/backups/} as
 * {@code dungeontrain-backup-<yyyyMMdd-HHmmss>.zip}. Each holds a top-level {@code manifest.json}
 * — schema version, mod version, timestamp, reason, file count and a content digest — and then
 * every backed-up file under a per-source prefix, so one archive can span
 * {@code <gameDir>/dungeontrain/} and {@code <gameDir>/dtpacks/} without their names colliding.</p>
 *
 * <p>Writes are atomic: the archive is built on {@code .tmp} and moved onto its real name only
 * once complete, the rule {@code PackageSaveOps.writeZipAtomically} established. A failure
 * therefore leaves the previous backups byte-identical rather than replacing a good archive with a
 * truncated one.</p>
 *
 * <p>Free of {@code FMLPaths} and Minecraft types on purpose — every entry point takes explicit
 * roots so the whole class is drivable from JUnit with {@code @TempDir}.</p>
 */
public final class PlayerDataBackup {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    static final String PREFIX = "dungeontrain-backup-";
    static final String SUFFIX = ".zip";
    static final String TMP_SUFFIX = ".tmp";
    static final String MANIFEST_ENTRY = "manifest.json";

    /** Schema of the manifest written into each archive. Bump on an incompatible layout change. */
    static final int SCHEMA_VERSION = 1;

    /** How many recent archives to keep. The oldest is always kept on top of these. */
    static final int KEEP_NEWEST = 10;

    /**
     * Ceiling on the backups folder. A player with a large build library would otherwise fill a
     * disk one launch at a time. Enforced after pruning by count, oldest-but-one first — the very
     * oldest archive is the pre-migration snapshot and is never dropped.
     */
    static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;

    /** One tree to back up. {@code label} becomes the top-level folder inside the archive. */
    public record Source(String label, Path dir) {}

    /** What {@link #create} did. {@code archive} is empty when nothing needed backing up. */
    public record Result(Optional<Path> archive, int fileCount, long totalBytes, String digest) {
        public boolean wrote() { return archive.isPresent(); }
    }

    private PlayerDataBackup() {}

    /**
     * Write a backup of {@code sources} into {@code backupsRoot}, unless nothing has changed since
     * the newest existing archive.
     *
     * <p>The change check is a digest over every file's archive path, size and last-modified time —
     * cheap (a directory walk, no file reads) and enough to tell an idle launch from one where the
     * player built something. An idle launch therefore costs a walk, not a zip.</p>
     *
     * @param reason recorded in the manifest, e.g. {@code "pre-migration"} or {@code "launch"}
     * @param modVersion recorded in the manifest so a restore can tell which build wrote it
     */
    public static synchronized Result create(
            Path backupsRoot, List<Source> sources, String reason, String modVersion)
            throws IOException {

        List<Entry> entries = collect(sources);
        if (entries.isEmpty()) {
            LOGGER.debug("[DungeonTrain] Backup: nothing to back up.");
            return new Result(Optional.empty(), 0, 0L, "");
        }

        String digest = digestOf(entries);
        String newestDigest = newestDigest(backupsRoot);
        if (digest.equals(newestDigest)) {
            LOGGER.debug("[DungeonTrain] Backup: unchanged since the newest archive — skipping.");
            return new Result(Optional.empty(), entries.size(), totalBytes(entries), digest);
        }

        Files.createDirectories(backupsRoot);
        Path archive = uniquePath(backupsRoot, LocalDateTime.now().format(STAMP));
        Path tmp = archive.resolveSibling(archive.getFileName() + TMP_SUFFIX);
        try {
            writeZip(tmp, entries, digest, reason, modVersion);
            replace(tmp, archive);
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }

        long bytes = totalBytes(entries);
        LOGGER.info("[DungeonTrain] Backup: wrote {} ({} file(s), {} KiB, reason={})",
            archive.getFileName(), entries.size(), bytes / 1024, reason);
        prune(backupsRoot);
        return new Result(Optional.of(archive), entries.size(), bytes, digest);
    }

    /** Every archive in {@code backupsRoot}, newest first. Never throws — an unreadable
     *  folder yields an empty list, because "no backups" must not take the game down. */
    public static List<Path> listArchives(Path backupsRoot) {
        if (!Files.isDirectory(backupsRoot)) return List.of();
        try (var stream = Files.list(backupsRoot)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith(PREFIX) && n.endsWith(SUFFIX);
                })
                // The timestamp is fixed-width and in the name, so lexicographic order IS
                // chronological order — and unlike mtime it survives a file copy.
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                .toList();
        } catch (IOException | SecurityException e) {
            LOGGER.warn("[DungeonTrain] Backup: couldn't list {}: {}", backupsRoot, e.toString());
            return List.of();
        }
    }

    /**
     * Restore an archive into {@code targets}, keyed by the source labels it was written with.
     *
     * <p>Additive, like every other write in this area: a file that already exists on disk is left
     * alone. Restoring can therefore only ever put content back, never replace something the player
     * has since made. Entries whose label has no target, and any entry that tries to escape its
     * target directory, are skipped.</p>
     *
     * @return the number of files actually written
     */
    public static synchronized int restore(Path archive, List<Source> targets) throws IOException {
        int written = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry entry = it.nextElement();
                if (entry.isDirectory() || MANIFEST_ENTRY.equals(entry.getName())) continue;
                Path target = targetFor(entry.getName(), targets);
                if (target == null || Files.exists(target)) continue;
                Files.createDirectories(target.getParent());
                try (var in = zip.getInputStream(entry)) {
                    Files.copy(in, target);
                }
                written++;
            }
        }
        LOGGER.info("[DungeonTrain] Backup: restored {} file(s) from {}",
            written, archive.getFileName());
        return written;
    }

    /**
     * Where one archive entry lands on disk, or {@code null} when it should be skipped.
     *
     * <p>Entry names come from a zip file, which is player-supplied data — a crafted archive could
     * carry {@code ../} segments or an absolute path (zip-slip). The resolved path is therefore
     * required to stay inside its target directory.</p>
     */
    static Path targetFor(String entryName, List<Source> targets) {
        int slash = entryName.indexOf('/');
        if (slash <= 0 || slash == entryName.length() - 1) return null;
        String label = entryName.substring(0, slash);
        String relative = entryName.substring(slash + 1);
        for (Source target : targets) {
            if (!target.label().equals(label)) continue;
            Path root = target.dir().toAbsolutePath().normalize();
            Path resolved = root.resolve(relative).normalize();
            return resolved.startsWith(root) ? resolved : null;
        }
        return null;
    }

    // ---- Internals ----

    /** One file to archive: where it is now, and the name it takes inside the zip. */
    private record Entry(Path file, String entryName, long size, long modified) {}

    private static List<Entry> collect(List<Source> sources) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (Source source : sources) {
            Path dir = source.dir();
            if (dir == null || !Files.isDirectory(dir)) continue;
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    String relative = dir.relativize(file).toString().replace('\\', '/');
                    entries.add(new Entry(file, source.label() + "/" + relative,
                        attrs.size(), attrs.lastModifiedTime().toMillis()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // An unreadable file must not abort the backup — the rest is still worth saving.
                    LOGGER.warn("[DungeonTrain] Backup: skipping unreadable {}: {}", file, exc.toString());
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        entries.sort(Comparator.comparing(Entry::entryName));
        return entries;
    }

    private static long totalBytes(List<Entry> entries) {
        long total = 0;
        for (Entry e : entries) total += e.size();
        return total;
    }

    /** SHA-256 over each entry's name, size and mtime — a fingerprint of the tree, without reads. */
    static String digestOf(List<Entry> entries) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JRE ships SHA-256; if it ever isn't there, degrade to "always back up".
            return "";
        }
        for (Entry e : entries) {
            md.update(e.entryName().getBytes(StandardCharsets.UTF_8));
            md.update((e.size() + ":" + e.modified()).getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : md.digest()) hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                                     .append(Character.forDigit(b & 0xF, 16));
        return hex.toString();
    }

    /** The {@code digest} recorded in the newest archive's manifest, or {@code ""} if unreadable. */
    static String newestDigest(Path backupsRoot) {
        List<Path> archives = listArchives(backupsRoot);
        if (archives.isEmpty()) return "";
        Path newest = archives.get(0);
        try (ZipFile zip = new ZipFile(newest.toFile())) {
            ZipEntry manifest = zip.getEntry(MANIFEST_ENTRY);
            if (manifest == null) return "";
            String json = new String(zip.getInputStream(manifest).readAllBytes(), StandardCharsets.UTF_8);
            return jsonString(json, "digest");
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Backup: couldn't read {}: {}", newest.getFileName(), e.toString());
            return "";
        }
    }

    /**
     * Pull one string value out of the manifest. The manifest is written by this class in a fixed
     * shape, so a two-line reader beats pulling in a JSON parse for one field.
     */
    static String jsonString(String json, String key) {
        String needle = "\"" + key + "\": \"";
        int start = json.indexOf(needle);
        if (start < 0) return "";
        start += needle.length();
        int end = json.indexOf('"', start);
        return end < 0 ? "" : json.substring(start, end);
    }

    private static Path uniquePath(Path backupsRoot, String stamp) {
        Path candidate = backupsRoot.resolve(PREFIX + stamp + SUFFIX);
        for (int n = 2; Files.exists(candidate); n++) {
            candidate = backupsRoot.resolve(PREFIX + stamp + "-" + n + SUFFIX);
        }
        return candidate;
    }

    private static void replace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeZip(Path zipPath, List<Entry> entries,
                                 String digest, String reason, String modVersion) throws IOException {
        try (OutputStream raw = Files.newOutputStream(zipPath);
             ZipOutputStream zip = new ZipOutputStream(raw, StandardCharsets.UTF_8)) {
            writeManifest(zip, entries, digest, reason, modVersion);
            for (Entry e : entries) {
                ZipEntry entry = new ZipEntry(e.entryName());
                entry.setTime(e.modified());
                zip.putNextEntry(entry);
                try {
                    Files.copy(e.file(), zip);
                } catch (IOException io) {
                    // The file vanished or turned unreadable between the walk and the copy. Better
                    // an archive missing one file than no archive at all.
                    LOGGER.warn("[DungeonTrain] Backup: couldn't archive {}: {}", e.file(), io.toString());
                }
                zip.closeEntry();
            }
        }
    }

    private static void writeManifest(ZipOutputStream zip, List<Entry> entries,
                                      String digest, String reason, String modVersion) throws IOException {
        zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append(",\n");
        sb.append("  \"modVersion\": \"").append(escape(modVersion)).append("\",\n");
        sb.append("  \"createdAt\": \"")
            .append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\",\n");
        sb.append("  \"reason\": \"").append(escape(reason)).append("\",\n");
        sb.append("  \"digest\": \"").append(escape(digest)).append("\",\n");
        sb.append("  \"fileCount\": ").append(entries.size()).append(",\n");
        sb.append("  \"totalBytes\": ").append(totalBytes(entries)).append("\n");
        sb.append("}\n");
        zip.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /**
     * Drop archives beyond {@link #KEEP_NEWEST}, then beyond {@link #MAX_TOTAL_BYTES}, always
     * keeping the oldest — that one is the snapshot taken before the very first migration, the only
     * copy of what the install looked like before Dungeon Train touched it.
     */
    static void prune(Path backupsRoot) {
        List<Path> archives = listArchives(backupsRoot); // newest first
        if (archives.size() <= 1) return;
        Path oldest = archives.get(archives.size() - 1);

        List<Path> keep = new ArrayList<>(archives.subList(0, Math.min(KEEP_NEWEST, archives.size())));
        if (!keep.contains(oldest)) keep.add(oldest);

        // Then trim by size, dropping the oldest kept archive first but never the very oldest.
        long total = 0;
        List<Path> byNewest = new ArrayList<>(keep);
        List<Path> finalKeep = new ArrayList<>();
        for (Path archive : byNewest) {
            long size = sizeOf(archive);
            if (archive.equals(oldest) || total + size <= MAX_TOTAL_BYTES) {
                finalKeep.add(archive);
                total += size;
            }
        }

        for (Path archive : archives) {
            if (finalKeep.contains(archive)) continue;
            try {
                Files.deleteIfExists(archive);
                LOGGER.info("[DungeonTrain] Backup: pruned {}", archive.getFileName());
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Backup: couldn't prune {}: {}",
                    archive.getFileName(), e.toString());
            }
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
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
