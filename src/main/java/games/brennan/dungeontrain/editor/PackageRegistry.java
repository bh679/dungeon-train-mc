package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.data.PlayerDataPaths;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Server-side singleton tracking the set of known dtpacks, the active
 * package (where edits land), and the disabled set (excluded from
 * gameplay).
 *
 * <p>State is persisted to {@code <config>/dungeontrain/dtpacks-state.json}.
 * The state file's mere existence doubles as the migration sentinel —
 * {@link DtpacksMigration} only runs when the file is absent — so deleting
 * {@code dtpacks/} alone won't re-trigger the one-time legacy move.</p>
 *
 * <p>Reads are lazy: the first call to any accessor scans
 * {@code <gameDir>/dtpacks/} and reads the state file. The legacy
 * {@code <config>/dungeontrain/imported/} folder is also scanned so the
 * registry works correctly during the brief window before
 * {@link DtpacksMigration} has run for the first time, and so that even
 * after migration any forgotten leftover under {@code imported/} stays
 * discoverable.</p>
 */
public final class PackageRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final String DTPACKS_SUBDIR = "dtpacks";
    static final String STATE_FILENAME = "dtpacks-state.json";

    private static final List<PackageInfo> PACKAGES = new ArrayList<>();
    private static final Set<String> DISABLED = new HashSet<>();
    private static String activeName = PackageInfo.UNSAVED_NAME;
    private static boolean initialised = false;

    private PackageRegistry() {}

    // ---- Path helpers (always safe to call, no side effects) ----

    /** {@code <gameDir>/dtpacks/} — root of the unified packages folder. */
    public static Path dtpacksRoot() {
        return FMLPaths.GAMEDIR.get().resolve(DTPACKS_SUBDIR);
    }

    /** {@code <gameDir>/dtpacks/<name>/} — working folder for a saved package. */
    public static Path workingDirFor(String name) {
        return dtpacksRoot().resolve(name);
    }

    /** {@code <gameDir>/dtpacks/<name>.zip} — archived snapshot location. */
    public static Path zipPathFor(String name) {
        return dtpacksRoot().resolve(name + ".zip");
    }

    /** {@code <config>/dungeontrain/dtpacks-state.json} — persisted state. */
    /**
     * {@code <gameDir>/dungeontrain/dtpacks-state.json}, falling back to the pre-relocation
     * {@code config/dungeontrain/} copy while one still exists.
     *
     * <p>It moved out of {@code config/} with the rest of the player's data — see
     * {@link games.brennan.dungeontrain.data.PlayerDataPaths}. Losing this file doesn't lose
     * content (the packages themselves are under {@code dtpacks/}), but it does lose which package
     * was active and which were disabled, which reads to the player as their builds vanishing.</p>
     */
    public static Path stateFile() {
        return PlayerDataPaths.locateAtRoot(STATE_FILENAME, "dungeontrain").read();
    }

    /**
     * Whether the state file exists (the migration sentinel).
     *
     * <p>Checks <b>both</b> addresses. {@link DtpacksMigration} treats an absent state file as
     * "this install has never been migrated"; if this looked only at the new location, the
     * relocation would make every existing install look brand new and re-run a one-shot that had
     * already happened.</p>
     */
    public static boolean stateFileExists() {
        return PlayerDataPaths.locateAtRoot(STATE_FILENAME, "dungeontrain")
            .all().stream().anyMatch(Files::isRegularFile);
    }

    // ---- Read accessors ----

    /** All known packages including the synthetic unsaved entry. Sorted by name (unsaved first). */
    public static synchronized List<PackageInfo> all() {
        ensureInitialised();
        return List.copyOf(PACKAGES);
    }

    /** The active package (where edits land). Falls back to unsaved if the named active is gone. */
    public static synchronized PackageInfo active() {
        ensureInitialised();
        return findByName(activeName).orElseGet(() -> unsavedSingleton());
    }

    /** Look up a package by name. */
    public static synchronized Optional<PackageInfo> byName(String name) {
        ensureInitialised();
        return findByName(name);
    }

    /** Whether the given package is enabled (contributes to template stores). */
    public static synchronized boolean isEnabled(PackageInfo pkg) {
        ensureInitialised();
        return isEnabledByName(pkg.name());
    }

    /** Whether the named package is enabled. Unknown names default to true. */
    public static synchronized boolean isEnabledByName(String name) {
        ensureInitialised();
        // Unsaved is always enabled — it's the working folder and can't be excluded.
        if (PackageInfo.UNSAVED_NAME.equals(name)) return true;
        return !DISABLED.contains(name);
    }

    /** Just the enabled packages, in scan order (active first, then alphabetical). */
    public static synchronized List<PackageInfo> enabledPackages() {
        ensureInitialised();
        List<PackageInfo> out = new ArrayList<>();
        PackageInfo act = active();
        if (isEnabledByName(act.name())) out.add(act);
        for (PackageInfo p : PACKAGES) {
            if (p.name().equals(act.name())) continue;
            if (isEnabledByName(p.name())) out.add(p);
        }
        return out;
    }

    /** Working folder of the active package — where new files should be written. */
    public static Path activeWriteDir() {
        return active().workingDir();
    }

    /** Convenience: {@code activeWriteDir().resolve(subSlug)}. */
    public static Path activeSubDir(String subSlug) {
        return activeWriteDir().resolve(subSlug);
    }

    /** Force a re-scan from disk on the next access. Called by mutators and migration. */
    public static synchronized void invalidate() {
        initialised = false;
    }

    // ---- Internals ----

    /** Re-read state file and rescan dtpacks/. Safe to call repeatedly. */
    private static void ensureInitialised() {
        if (initialised) return;
        load();
        initialised = true;
    }

    private static void load() {
        PACKAGES.clear();
        DISABLED.clear();
        activeName = PackageInfo.UNSAVED_NAME;

        // 1. The unsaved pseudo-package is always first. workingDir = legacy user/ root.
        PackageInfo unsaved = PackageInfo.unsaved(UserContentPaths.root());
        PACKAGES.add(unsaved);

        // 2. Read state file (active name + disabled set). Absent → defaults are fine.
        readStateFile();

        // Backwards-compat: pre-rename state files persisted active="(unsaved)"
        // when that was the sentinel. Normalise to the new bare-word form so
        // the registry / slash commands don't trip over the parens.
        if ("(unsaved)".equalsIgnoreCase(activeName)) {
            activeName = PackageInfo.UNSAVED_NAME;
        }

        // 3. Scan dtpacks/ for working folders + zips. Pair them up.
        Map<String, ScanEntry> found = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        scanDtpacks(found);

        // 4. Scan legacy imported/ for packages that haven't been migrated yet.
        //    Each becomes an IMPORTED package with workingDir = imported/<name>/, no zip.
        scanLegacyImported(found);

        // 5. Build PackageInfo entries from the scan map.
        for (Map.Entry<String, ScanEntry> e : found.entrySet()) {
            ScanEntry s = e.getValue();
            if (s.workingDir == null) {
                // Zip-only (no extracted folder yet). The importer pass will materialise it.
                // Skip from the registry for now — without a working dir, the menu has
                // nothing useful to show and template stores can't load from it.
                LOGGER.info("[DungeonTrain] dtpacks: zip without working folder: {} (will be extracted on next reload)",
                    s.zipPath);
                continue;
            }
            PackageInfo.Provenance prov = s.zipPath != null
                ? PackageInfo.Provenance.SAVED
                : PackageInfo.Provenance.IMPORTED;
            PACKAGES.add(new PackageInfo(e.getKey(), s.workingDir, s.zipPath, prov));
        }
    }

    /** Sub-result of the dtpacks/ + imported/ scan: either side of the (folder, zip) pair may be absent. */
    private static final class ScanEntry {
        Path workingDir;
        Path zipPath;
    }

    private static void scanDtpacks(Map<String, ScanEntry> into) {
        Path root = dtpacksRoot();
        if (!Files.isDirectory(root)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                String filename = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    ScanEntry s = into.computeIfAbsent(filename, k -> new ScanEntry());
                    s.workingDir = entry;
                } else if (Files.isRegularFile(entry)
                        && filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    String stem = filename.substring(0, filename.length() - 4);
                    ScanEntry s = into.computeIfAbsent(stem, k -> new ScanEntry());
                    s.zipPath = entry;
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to scan dtpacks at {}: {}", root, e.toString());
        }
    }

    private static void scanLegacyImported(Map<String, ScanEntry> into) {
        Path legacy = UserContentPaths.importedRoot();
        if (!Files.isDirectory(legacy)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(legacy)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) continue;
                String name = entry.getFileName().toString();
                if (into.containsKey(name)) continue; // dtpacks/ already covers it
                ScanEntry s = new ScanEntry();
                s.workingDir = entry;
                into.put(name, s);
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Failed to scan legacy imported at {}: {}", legacy, e.toString());
        }
    }

    /**
     * Minimal JSON reader for the state file. The file has three top-level
     * keys: {@code "active"} (string), {@code "disabled"} (array of
     * strings), and {@code "schemaVersion"} (int, currently 1).
     *
     * <p>We hand-parse to avoid pulling in a JSON dependency for two fields.
     * The on-disk format is deliberately simple and only ever written by
     * {@link #writeStateFile()}.</p>
     */
    private static void readStateFile() {
        Path file = stateFile();
        if (!Files.isRegularFile(file)) return;
        try {
            String body = Files.readString(file, StandardCharsets.UTF_8);
            String active = readJsonString(body, "active");
            if (active != null && !active.isBlank()) {
                activeName = active;
            }
            List<String> disabled = readJsonStringArray(body, "disabled");
            DISABLED.addAll(disabled);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Couldn't read package state file {}: {}", file, e.toString());
        }
    }

    /** Public so mutators can persist after they've updated the in-memory state. */
    public static synchronized void writeStateFile() {
        Path file = stateFile();
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream raw = Files.newOutputStream(file);
                 Writer w = new OutputStreamWriter(raw, StandardCharsets.UTF_8)) {
                w.write("{\n");
                w.write("  \"schemaVersion\": 1,\n");
                w.write("  \"active\": \"" + escape(activeName) + "\",\n");
                w.write("  \"disabled\": [");
                List<String> sorted = new ArrayList<>(DISABLED);
                Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
                for (int i = 0; i < sorted.size(); i++) {
                    if (i > 0) w.write(",");
                    w.write("\n    \"" + escape(sorted.get(i)) + "\"");
                }
                if (!sorted.isEmpty()) w.write("\n  ");
                w.write("]\n");
                w.write("}\n");
            }
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Failed to write package state file {}: {}", file, e.toString());
        }
    }

    /**
     * Initialise the in-memory state for a fresh install. Called by
     * {@link DtpacksMigration} on first launch so the state file gets
     * written even when no migration sources exist (i.e. brand-new
     * installs land on active=unsaved with no disabled packages).
     */
    static synchronized void initFreshAndPersist() {
        activeName = PackageInfo.UNSAVED_NAME;
        DISABLED.clear();
        writeStateFile();
        invalidate();
    }

    private static Optional<PackageInfo> findByName(String name) {
        for (PackageInfo p : PACKAGES) {
            if (p.name().equals(name)) return Optional.of(p);
        }
        return Optional.empty();
    }

    private static PackageInfo unsavedSingleton() {
        for (PackageInfo p : PACKAGES) {
            if (p.isUnsaved()) return p;
        }
        // Fallback — defensive in case load() failed.
        return PackageInfo.unsaved(UserContentPaths.root());
    }

    // ---- Tiny JSON helpers (hand-rolled to avoid dragging in a parser) ----

    private static String readJsonString(String body, String key) {
        String needle = "\"" + key + "\"";
        int k = body.indexOf(needle);
        if (k < 0) return null;
        int colon = body.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int start = body.indexOf('"', colon + 1);
        if (start < 0) return null;
        StringBuilder out = new StringBuilder();
        for (int i = start + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char n = body.charAt(++i);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    default -> out.append(n);
                }
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
            }
        }
        return null;
    }

    private static List<String> readJsonStringArray(String body, String key) {
        String needle = "\"" + key + "\"";
        int k = body.indexOf(needle);
        if (k < 0) return List.of();
        int colon = body.indexOf(':', k + needle.length());
        if (colon < 0) return List.of();
        int lb = body.indexOf('[', colon + 1);
        int rb = body.indexOf(']', lb + 1);
        if (lb < 0 || rb < 0) return List.of();
        String inner = body.substring(lb + 1, rb);
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < inner.length()) {
            while (i < inner.length() && inner.charAt(i) != '"') i++;
            if (i >= inner.length()) break;
            int start = i + 1;
            StringBuilder sb = new StringBuilder();
            i = start;
            while (i < inner.length()) {
                char c = inner.charAt(i);
                if (c == '\\' && i + 1 < inner.length()) {
                    sb.append(inner.charAt(++i));
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
                i++;
            }
            out.add(sb.toString());
            i++;
        }
        return out;
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

    // ---- Mutators ----

    /**
     * Switch the active package. Persists the new state, invalidates the
     * registry, and triggers a cache-only reload so every template store
     * picks up the new {@code searchDirs} ordering on its next read.
     *
     * <p>No-op when {@code name} already matches the current active.</p>
     */
    public static synchronized MutateResult setActive(String name) {
        ensureInitialised();
        if (!PackageInfo.UNSAVED_NAME.equals(name) && findByName(name).isEmpty()) {
            return MutateResult.error("Unknown package: " + name);
        }
        if (activeName.equals(name)) {
            return MutateResult.success("Active is already '" + name + "'");
        }
        activeName = name;
        writeStateFile();
        invalidate();
        games.brennan.dungeontrain.template.TemplateStores.reloadCachesOnly();
        LOGGER.info("[DungeonTrain] Active package -> {}", name);
        return MutateResult.success("Active package: " + name);
    }

    /**
     * Toggle a package's enabled flag. Persists state, invalidates the
     * registry, and triggers a cache-only reload so the package's
     * contents stop / start contributing to template lookups.
     *
     * <p>The unsaved pseudo-package is always enabled and silently
     * ignored.</p>
     */
    public static synchronized MutateResult setEnabled(String name, boolean enabled) {
        ensureInitialised();
        if (PackageInfo.UNSAVED_NAME.equals(name)) {
            return MutateResult.success("(unsaved) is always enabled");
        }
        if (findByName(name).isEmpty()) {
            return MutateResult.error("Unknown package: " + name);
        }
        boolean wasEnabled = !DISABLED.contains(name);
        if (wasEnabled == enabled) {
            return MutateResult.success("'" + name + "' already " + (enabled ? "enabled" : "disabled"));
        }
        if (enabled) DISABLED.remove(name);
        else DISABLED.add(name);
        writeStateFile();
        invalidate();
        games.brennan.dungeontrain.template.TemplateStores.reloadCachesOnly();
        LOGGER.info("[DungeonTrain] Package '{}' {} — content stores reloaded", name, enabled ? "enabled" : "disabled");
        return MutateResult.success("'" + name + "' " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * Save the active package under {@code requestedName}.
     *
     * <p><b>This never deletes or moves player content.</b> Two cases:</p>
     *
     * <ul>
     *   <li><b>Same name as the active package</b>: rewrites the zip from
     *       the current working folder. The folder is untouched.</li>
     *   <li><b>Any other name</b> (including the first save of the unsaved
     *       package): <i>copies</i> the active working folder into
     *       {@code dtpacks/<name>/}, writes the zip, and switches active
     *       to the new package. The source package — the previous folder
     *       and its zip, or {@code user/} on a first save — is left
     *       exactly as it was, so a Save can never cost the player work.</li>
     * </ul>
     *
     * <p>The copy is verified before the save reports success, and a copy
     * that fails removes the destination folder this call created.
     * Every error path invalidates the registry so a failed save can't
     * leave on-disk content hidden until the next restart.</p>
     *
     * <p>Synchronized; the entire flow is atomic with respect to other
     * registry reads. Template stores re-read {@code searchDirs} after
     * the reload barrier completes, so no partial state is visible.</p>
     */
    public static synchronized SaveResult saveCurrent(String requestedName) {
        ensureInitialised();
        String name = requestedName == null ? "" : requestedName.trim();
        if (!isValidName(name)) {
            return SaveResult.error("Invalid name '" + name + "' \u2014 use a-z, 0-9, underscore, 1-32 chars.");
        }
        if (PackageInfo.UNSAVED_NAME.equals(name) || "(unsaved)".equalsIgnoreCase(name)) {
            return SaveResult.error("'" + name + "' is reserved.");
        }

        PackageInfo current = active();
        boolean firstSave = current.isUnsaved();
        boolean sameName = !firstSave && current.name().equalsIgnoreCase(name);

        Path dtpacks = dtpacksRoot();
        try {
            Files.createDirectories(dtpacks);
        } catch (IOException e) {
            return SaveResult.error("Couldn't create dtpacks/: " + e.getMessage());
        }

        Path newWorkingDir = workingDirFor(name);
        Path newZipPath = zipPathFor(name);

        if (!sameName) {
            // Checks the zip as well as the folder: a dropped-in <name>.zip that
            // hasn't been extracted yet is absent from PACKAGES, and saving over
            // it used to silently destroy someone else's pack.
            if (PackageSaveOps.nameTaken(dtpacks, name)) {
                return SaveResult.error("A package named '" + name + "' already exists. "
                    + "Pick a different name, or activate that package and save over it.");
            }
            // nameTaken() ruled out a pre-existing folder above, so anything under
            // newWorkingDir from here on was written by this call — safe to clean up.
            try {
                PackageSaveOps.CopyReport report = PackageSaveOps.copyTree(current.workingDir(), newWorkingDir);
                if (!report.clean() || !PackageSaveOps.verifyTree(current.workingDir(), newWorkingDir)) {
                    PackageSaveOps.deleteRecursivelyQuietly(newWorkingDir);
                    invalidate();
                    return SaveResult.error("Couldn't copy content into '" + name + "' \u2014 "
                        + report.failures().size() + " file(s) failed. Your content is untouched at "
                        + current.workingDir() + ".");
                }
                LOGGER.info("[DungeonTrain] save: copied {} file(s) from {} -> dtpacks/{}/ (source kept)",
                    report.copied(), current.workingDir(), name);
            } catch (IOException e) {
                PackageSaveOps.deleteRecursivelyQuietly(newWorkingDir);
                invalidate();
                return SaveResult.error("Couldn't copy content into new package: " + e.getMessage()
                    + " \u2014 your content is untouched at " + current.workingDir() + ".");
            }
        }

        try {
            int zipped = PackageSaveOps.writeZipAtomically(newWorkingDir, newZipPath);
            LOGGER.info("[DungeonTrain] save: wrote {} ({} file(s))", newZipPath, zipped);
        } catch (IOException e) {
            invalidate();
            return SaveResult.error("Couldn't write zip: " + e.getMessage()
                + " \u2014 any previous snapshot is unchanged.");
        }

        activeName = name;
        writeStateFile();
        invalidate();
        games.brennan.dungeontrain.template.TemplateStores.reloadCachesOnly();
        return SaveResult.success(name, !sameName, firstSave);
    }

    /** Outcome of a {@link #saveCurrent} call. */
    public record SaveResult(boolean success, String packageName, boolean switchedActive, String message) {
        public static SaveResult success(String name, boolean switched, boolean firstSave) {
            String msg;
            if (firstSave) {
                // Say so explicitly: the copy left behind in user/ is a deliberate
                // safety net, and a player who isn't told about it will read the
                // duplicate as a bug the next time they activate (unsaved).
                msg = "Saved as '" + name + "' (now active). Your unsaved folder was copied, not moved \u2014 "
                    + "the originals are still there as a backup.";
            } else if (switched) {
                msg = "Saved a copy as '" + name + "' (now active). The package you copied from is untouched.";
            } else {
                msg = "Resaved '" + name + "'";
            }
            return new SaveResult(true, name, switched, msg);
        }
        public static SaveResult error(String message) {
            return new SaveResult(false, "", false, message);
        }
    }

    /** Outcome of a non-save mutator (setActive / setEnabled). */
    public record MutateResult(boolean success, String message) {
        public static MutateResult success(String message) { return new MutateResult(true, message); }
        public static MutateResult error(String message) { return new MutateResult(false, message); }
    }

    // ---- Validation + filesystem helpers ----

    /** Allowed package names: 1-32 chars, lowercase a-z, digits, underscore. */
    private static final java.util.regex.Pattern NAME_PATTERN =
        java.util.regex.Pattern.compile("^[a-z0-9_]{1,32}$");

    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    /** Read-only access to disabled name set for callers that need a copy. */
    public static synchronized Set<String> disabledNames() {
        ensureInitialised();
        return Set.copyOf(DISABLED);
    }
}
