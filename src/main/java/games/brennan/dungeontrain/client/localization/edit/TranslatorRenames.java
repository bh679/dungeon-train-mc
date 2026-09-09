package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The renames this client has made to its own credit, old name → new name, remembered so the
 * Credits page can show the new name at once.
 *
 * <p>A rename takes effect on the relay immediately, but two other places still carry the old
 * name: the build-time {@code translation_contributors.json} (until the next release, after the
 * repo importer has applied the rename) and this client's own cached relay credits (until the
 * next coverage fetch lands). Without an alias the page would list the same person twice, once
 * under each name, which is the very thing {@code TranslationCreditsMerge} exists to prevent.</p>
 *
 * <p>Local to this client on purpose. Every other client sees the relay's new name on its next
 * fetch and the jar's old name until the release — the same gap the relay's live credits have
 * always had over the baked list, and not one worth a second endpoint to close.</p>
 */
public final class TranslatorRenames {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE = "renames.json";
    /** A guard against a corrupt file, not a real limit. */
    private static final int MAX_ENTRIES = 50;

    private static final Map<String, String> ALIASES = new LinkedHashMap<>();
    private static boolean loaded;

    private TranslatorRenames() {}

    /**
     * The name {@code name} is now credited under: follows a chain of renames (A→B, B→C gives C
     * for A) and stops on a cycle. A name never renamed is returned as is.
     */
    public static synchronized String resolve(String name) {
        ensureLoaded();
        return resolve(ALIASES, name);
    }

    /** A copy of every recorded rename, old name → new name, for the credits merge. */
    public static synchronized Map<String, String> snapshot() {
        ensureLoaded();
        return Map.copyOf(ALIASES);
    }

    /** Pure form of {@link #resolve(String)}, for the merge and its tests. */
    public static String resolve(Map<String, String> aliases, String name) {
        if (name == null || aliases == null || aliases.isEmpty()) {
            return name;
        }
        Set<String> seen = new HashSet<>();
        String current = name;
        while (aliases.containsKey(current) && seen.add(current)) {
            current = aliases.get(current);
        }
        return current;
    }

    /** Remember that {@code from} is now {@code to}, and persist. Blank or equal names are ignored. */
    public static synchronized void record(String from, String to) {
        ensureLoaded();
        String f = from == null ? "" : from.trim();
        String t = to == null ? "" : to.trim();
        if (f.isEmpty() || t.isEmpty() || f.equals(t)) {
            return;
        }
        // Renaming back to an earlier name closes the loop; drop the reverse edge so resolve()
        // does not spin on it and the earlier name reads as current again.
        ALIASES.remove(t);
        ALIASES.put(f, t);
        save();
    }

    private static Path file() {
        return TranslationOverrideStore.root().resolve(FILE);
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true; // set first: a failed read must not retry on every credits render
        Path path;
        try {
            path = file();
        } catch (Exception e) {
            return;
        }
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                if (ALIASES.size() >= MAX_ENTRIES) {
                    break;
                }
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
                    String to = entry.getValue().getAsString().trim();
                    String from = entry.getKey().trim();
                    if (!from.isEmpty() && !to.isEmpty() && !from.equals(to)) {
                        ALIASES.put(from, to);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: could not read {} — {}", path, e.toString());
        }
    }

    private static void save() {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            ALIASES.forEach(root::addProperty);
            Files.writeString(path, root.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: could not save the rename list — {}", e.toString());
        }
    }

    /** Test seam — forget everything read from disk. */
    static synchronized void reset() {
        ALIASES.clear();
        loaded = false;
    }
}
