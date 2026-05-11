package games.brennan.dungeontrain.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent record of which files under {@link UserContentPaths#root()}
 * arrived via an import package versus which the player authored directly.
 * Drives the orange-tinted UI marker so a player can see at a glance
 * which templates came from {@code <game>/imports/} vs their own saves.
 *
 * <p>Storage path:
 * {@code <config>/dungeontrain/.imports-state.json}. Deliberately outside
 * the user-content root so {@link UserContentExporter} doesn't roll the
 * provenance index into the next package — the index is install-local
 * state about <i>where this install's files came from</i>, not portable
 * package metadata.</p>
 *
 * <p>Records:
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "imported": {
 *     "templates/standard.nbt": 1717000000000,
 *     "contents/default.nbt": 1717000000000
 *   }
 * }
 * </pre>
 * Keys are forward-slash relative paths under the user-content root
 * (always forward slashes — Windows backslashes are normalised so the
 * format round-trips between operating systems). Values are the file's
 * {@link FileTime#toMillis()} epoch milliseconds captured at import time.</p>
 *
 * <p>{@link #isImported(String)} returns true only when:
 * <ol>
 *   <li>the index has an entry for the relative path, and</li>
 *   <li>the file's current modification time matches the recorded one.</li>
 * </ol>
 * The mtime check is what gives "edit promotes to user" semantics for
 * free — saving a template through the editor naturally updates its
 * mtime, so the next render sees a mismatch and the variant flips from
 * orange (imported) to blue (user-authored). No save-time hook required.</p>
 *
 * <p>Thread-safe via {@code synchronized} on every mutator + reader. The
 * index is small (one entry per imported file) so contention is
 * negligible.</p>
 */
public final class ImportedContentIndex {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String INDEX_FILENAME = ".imports-state.json";
    private static final int CURRENT_SCHEMA_VERSION = 1;

    private static final Object LOCK = new Object();
    private static Map<String, Long> entries; // lazy-init on first access

    private ImportedContentIndex() {}

    /**
     * Path to the on-disk index file. Lives alongside {@code user/} rather
     * than inside it so the exporter doesn't sweep the index into shared
     * packages.
     */
    public static Path indexPath() {
        return FMLPaths.CONFIGDIR.get().resolve("dungeontrain").resolve(INDEX_FILENAME);
    }

    /**
     * True when {@code relPath} is recorded as imported AND the file's
     * current mtime still matches what we captured at import time. Once
     * the file is edited (mtime advances), this returns false and the
     * variant promotes to "user-authored" in the UI.
     *
     * <p>{@code relPath} must be a forward-slash path relative to
     * {@link UserContentPaths#root()}, e.g. {@code "templates/foo.nbt"}.</p>
     */
    public static boolean isImported(String relPath) {
        synchronized (LOCK) {
            ensureLoaded();
            Long recordedMillis = entries.get(normalise(relPath));
            if (recordedMillis == null) return false;
            Path file = UserContentPaths.root().resolve(relPath);
            try {
                FileTime current = Files.getLastModifiedTime(file);
                return current.toMillis() == recordedMillis;
            } catch (NoSuchFileException e) {
                // File was deleted out from under us — drop the index entry so
                // a future re-import doesn't keep spuriously flagging the
                // (now-missing) path as imported.
                entries.remove(normalise(relPath));
                persistQuietly();
                return false;
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Couldn't stat {} for import-state check: {}",
                    file, e.toString());
                return false;
            }
        }
    }

    /**
     * Record {@code relPath} as imported with mtime {@code mtime}. Persists
     * immediately so a crash between import and registry reload doesn't
     * lose the provenance link.
     *
     * <p>Call once per file extracted by
     * {@link UserContentImporter#importOne}. Overwrites the previous
     * entry on collision (a second import of the same path replaces the
     * older record's mtime).</p>
     */
    public static void recordImported(String relPath, FileTime mtime) {
        synchronized (LOCK) {
            ensureLoaded();
            entries.put(normalise(relPath), mtime.toMillis());
            persistQuietly();
        }
    }

    /**
     * Clear the in-memory cache so the next read reloads from disk. Used
     * by tests + the editor's full-reload flow; not normally needed
     * because every mutator persists in-line.
     */
    public static void invalidateCache() {
        synchronized (LOCK) {
            entries = null;
        }
    }

    private static void ensureLoaded() {
        if (entries != null) return;
        entries = readFromDisk();
    }

    private static Map<String, Long> readFromDisk() {
        Path file = indexPath();
        if (!Files.isRegularFile(file)) return new LinkedHashMap<>();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (!root.isJsonObject()) return new LinkedHashMap<>();
            JsonObject obj = root.getAsJsonObject();
            if (!obj.has("imported") || !obj.get("imported").isJsonObject()) {
                return new LinkedHashMap<>();
            }
            JsonObject imported = obj.getAsJsonObject("imported");
            Map<String, Long> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : imported.entrySet()) {
                if (!e.getValue().isJsonPrimitive()) continue;
                try {
                    out.put(normalise(e.getKey()), e.getValue().getAsLong());
                } catch (NumberFormatException nfe) {
                    LOGGER.warn("[DungeonTrain] Skipping bad import-state entry {} = {}",
                        e.getKey(), e.getValue());
                }
            }
            return out;
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Couldn't read import-state {}: {}", file, e.toString());
            return new LinkedHashMap<>();
        }
    }

    private static void persistQuietly() {
        try {
            persist();
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Couldn't write import-state {}: {}",
                indexPath(), e.toString());
        }
    }

    private static void persist() throws IOException {
        Path file = indexPath();
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("{\n");
            w.write("  \"schemaVersion\": ");
            w.write(Integer.toString(CURRENT_SCHEMA_VERSION));
            w.write(",\n");
            w.write("  \"imported\": {");
            boolean first = true;
            // Use a sorted view so the file diff-cleanly without depending on
            // insertion order — useful when a player versions their config dir
            // in git for sharing.
            Map<String, Long> sorted = new java.util.TreeMap<>(entries);
            for (Map.Entry<String, Long> e : sorted.entrySet()) {
                if (!first) w.write(",");
                w.write("\n    \"");
                w.write(escape(e.getKey()));
                w.write("\": ");
                w.write(Long.toString(e.getValue()));
                first = false;
            }
            if (!first) w.write("\n  ");
            w.write("}\n");
            w.write("}\n");
        }
    }

    /**
     * Force forward-slash separators so the JSON keys stay portable
     * between operating systems. Zip entry names always use {@code /} so
     * matching against the in-flight import path is straightforward.
     */
    private static String normalise(String relPath) {
        return relPath.replace('\\', '/');
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

    /** Test/internal helper — used by {@link UserContentImporter} to log the post-reload size. */
    public static int recordedCount() {
        synchronized (LOCK) {
            ensureLoaded();
            return entries.size();
        }
    }

    /**
     * Snapshot of every currently-recorded import as {@code (relPath →
     * recordedMillis)}. Used by tests and by the menu builder to filter
     * variants without re-doing the per-file mtime check.
     */
    public static Map<String, Long> snapshot() {
        synchronized (LOCK) {
            ensureLoaded();
            return new HashMap<>(entries);
        }
    }
}
