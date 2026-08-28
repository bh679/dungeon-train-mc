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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /** Separates the timestamp from the mod version in an archive's name. */
    static final String VERSION_MARKER = "-v";

    /** Archives kept per mod version when no other figure is supplied. */
    public static final int DEFAULT_PER_VERSION = 5;

    /**
     * Ceiling on the backups folder, applied after the per-version cap.
     *
     * <p>A backstop, not the rule: "N per version" is unbounded across many versions — twenty
     * versions at five each is a hundred archives — so a long-lived install would otherwise fill a
     * disk one release at a time. Enforced oldest-first, and the very oldest archive (the
     * pre-migration snapshot) is never dropped.</p>
     */
    static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;

    /**
     * One tree to back up. {@code label} becomes the top-level folder inside the archive.
     *
     * @param excludeTopLevel names of direct children of {@code dir} to skip — this is how the
     *                        backups folder stays out of its own archives
     */
    public record Source(String label, Path dir, Set<String> excludeTopLevel) {

        public Source(String label, Path dir) {
            this(label, dir, Set.of());
        }

        boolean excludes(Path dir, Path file) {
            Path relative = dir.relativize(file);
            return relative.getNameCount() > 0
                && excludeTopLevel.contains(relative.getName(0).toString());
        }
    }

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
        return create(backupsRoot, sources, reason, modVersion, DEFAULT_PER_VERSION);
    }

    /** As above, keeping at most {@code perVersion} archives per mod version. */
    public static synchronized Result create(
            Path backupsRoot, List<Source> sources, String reason, String modVersion, int perVersion)
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
        Path archive = uniquePath(backupsRoot, LocalDateTime.now().format(STAMP), modVersion);
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
        prune(backupsRoot, perVersion);
        return new Result(Optional.of(archive), entries.size(), bytes, digest);
    }

    /**
     * Copy {@code archive} into {@code externalRoot} and thin that folder with the same ladder.
     *
     * <p>Best-effort by design: the in-instance archive has already been written and verified by
     * the time this runs, so a read-only home directory, a full disk or a sandbox that forbids the
     * path must degrade to a warning rather than turn a successful backup into a failed one.</p>
     *
     * @return true when the copy landed
     */
    public static synchronized boolean mirror(Path archive, Path externalRoot) {
        return mirror(archive, externalRoot, DEFAULT_PER_VERSION);
    }

    /** As above, thinning the external folder with the same per-version cap. */
    public static synchronized boolean mirror(Path archive, Path externalRoot, int perVersion) {
        if (archive == null || externalRoot == null || !Files.isRegularFile(archive)) return false;
        try {
            Files.createDirectories(externalRoot);
            Path target = externalRoot.resolve(archive.getFileName().toString());
            if (Files.exists(target)) return true; // already mirrored; nothing to redo
            Path tmp = target.resolveSibling(target.getFileName() + TMP_SUFFIX);
            try {
                Files.copy(archive, tmp, StandardCopyOption.REPLACE_EXISTING);
                replace(tmp, target);
            } finally {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
            LOGGER.info("[DungeonTrain] Backup: mirrored {} outside the instance to {}",
                archive.getFileName(), externalRoot);
            prune(externalRoot, perVersion);
            return true;
        } catch (IOException | SecurityException e) {
            LOGGER.warn("[DungeonTrain] Backup: couldn't mirror to {} ({}). The in-instance backup "
                + "is unaffected.", externalRoot, e.toString());
            return false;
        }
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
                // Sort on the parsed timestamp, not the whole filename: the name also carries the
                // mod version, and version numbers do not sort chronologically. Unlike mtime, the
                // stamp survives a file copy — which the out-of-instance mirror depends on.
                .sorted(Comparator.comparing(PlayerDataBackup::stampOf)
                    .thenComparing((Path p) -> p.getFileName().toString()).reversed())
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
                    if (source.excludes(dir, file)) return FileVisitResult.CONTINUE;
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

    /**
     * {@code dungeontrain-backup-<stamp>-v<version>.zip}.
     *
     * <p>The version is a SUFFIX, deliberately. {@link #listArchives} and {@link #stampOf} read the
     * timestamp at a fixed offset after the prefix, and version numbers do not sort
     * lexicographically — {@code 0.10.0} sorts before {@code 0.9.0}. Putting the version first
     * would have silently scrambled chronological order the first time a minor rolled over ten.
     * As a suffix it is inert to both, and archives written before versioned names still parse.</p>
     */
    private static Path uniquePath(Path backupsRoot, String stamp, String modVersion) {
        String version = sanitiseVersion(modVersion);
        String tail = version.isEmpty() ? "" : VERSION_MARKER + version;
        Path candidate = backupsRoot.resolve(PREFIX + stamp + tail + SUFFIX);
        for (int n = 2; Files.exists(candidate); n++) {
            candidate = backupsRoot.resolve(PREFIX + stamp + "-" + n + tail + SUFFIX);
        }
        return candidate;
    }

    /** Strip anything that would break a filename or the name grammar above. */
    private static String sanitiseVersion(String modVersion) {
        if (modVersion == null) return "";
        String cleaned = modVersion.trim().replaceAll("[^A-Za-z0-9._]", "");
        return cleaned.equals("?") ? "" : cleaned;
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
     * Thin the archive set: at most {@code perVersion} archives per mod version, then the byte cap.
     *
     * <p>Version-scoped rather than time-scoped so the rule is one a player can predict — "five per
     * version" is inspectable from the filenames, where the previous count-plus-day ladder left no
     * way to tell why any particular archive had survived.</p>
     *
     * <p>The single oldest archive is kept whatever the rules say. It is the snapshot taken before
     * the very first migration, and the only record of what the install looked like before Dungeon
     * Train touched it.</p>
     */
    static void prune(Path backupsRoot, int perVersion) {
        int cap = Math.max(1, perVersion);
        List<Path> archives = listArchives(backupsRoot); // newest first
        if (archives.size() <= 1) return;
        Path oldest = archives.get(archives.size() - 1);

        // Newest-first within each version, so the survivors of a group are its most recent.
        Map<String, Integer> keptPerVersion = new LinkedHashMap<>();
        List<Path> keep = new ArrayList<>();
        for (Path archive : archives) {
            String version = versionOf(archive);
            int kept = keptPerVersion.getOrDefault(version, 0);
            if (kept < cap) {
                keep.add(archive);
                keptPerVersion.put(version, kept + 1);
            }
        }
        if (!keep.contains(oldest)) keep.add(oldest);

        // Then the size backstop, dropping the oldest kept archive first but never the very oldest.
        long total = 0;
        List<Path> byNewest = new ArrayList<>(keep);
        byNewest.sort(Comparator.comparing(PlayerDataBackup::stampOf)
            .thenComparing((Path p) -> p.getFileName().toString()).reversed());
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
            delete(archive, "pruned");
        }
    }

    /** Total bytes of every archive in {@code backupsRoot}. Zero for a folder that isn't there. */
    public static long totalSize(Path root) {
        long total = 0;
        for (Path archive : listArchives(root)) total += sizeOf(archive);
        return total;
    }

    /** What {@link #clear} did. {@code failures} names archives that could not be removed. */
    public record ClearResult(int deleted, long bytesFreed, List<String> failures) {
        public boolean clean() { return failures.isEmpty(); }
    }

    /**
     * Delete every archive in {@code root}.
     *
     * <p>Only files matching the backup naming are touched — {@link #listArchives} is the same
     * filter used everywhere else — so pointing this at a folder holding anything of the player's
     * cannot take it with them. A file that will not delete is collected and the loop continues,
     * because stopping halfway would leave the player with neither the space nor a clear report.</p>
     */
    public static synchronized ClearResult clear(Path root) {
        int deleted = 0;
        long freed = 0;
        List<String> failures = new ArrayList<>();
        for (Path archive : listArchives(root)) {
            long size = sizeOf(archive);
            if (delete(archive, "cleared")) {
                deleted++;
                freed += size;
            } else {
                failures.add(archive.getFileName().toString());
            }
        }
        return new ClearResult(deleted, freed, List.copyOf(failures));
    }

    /** Remove one archive, logging either way. {@code why} appears in the log line. */
    private static boolean delete(Path archive, String why) {
        try {
            Files.deleteIfExists(archive);
            LOGGER.info("[DungeonTrain] Backup: {} {}", why, archive.getFileName());
            return true;
        } catch (IOException | SecurityException e) {
            LOGGER.warn("[DungeonTrain] Backup: couldn't remove {}: {}",
                archive.getFileName(), e.toString());
            return false;
        }
    }

    /**
     * A byte count as the player should read it — {@code "1.2 GB"}, {@code "512 KB"}, {@code "0 B"}.
     *
     * <p>Decimal MB/GB rather than the KiB/MiB used in the logs: this goes on a button, and the
     * figure a player compares against is the one their file manager shows.</p>
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * The {@code yyyyMMdd-HHmmss} stamp in an archive's name, or {@code ""} when it has none.
     *
     * <p>Sorting keys off this rather than the whole filename so that anything appended after the
     * stamp — the mod version, a de-duplication counter — cannot reorder history.</p>
     */
    static String stampOf(Path archive) {
        String name = archive.getFileName().toString();
        if (!name.startsWith(PREFIX)) return "";
        String rest = name.substring(PREFIX.length());
        return rest.length() >= 15 ? rest.substring(0, 15) : "";
    }

    /**
     * The mod version an archive was written by, read from its name. {@code ""} for archives from
     * before versioned names, which is a real group of its own rather than an error.
     */
    static String versionOf(Path archive) {
        String name = archive.getFileName().toString();
        if (!name.endsWith(SUFFIX)) return "";
        int marker = name.lastIndexOf(VERSION_MARKER);
        if (marker < PREFIX.length()) return "";
        return name.substring(marker + VERSION_MARKER.length(), name.length() - SUFFIX.length());
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
