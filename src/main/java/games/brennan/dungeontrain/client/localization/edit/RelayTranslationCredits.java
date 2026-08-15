package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The translators whose approved work this client is applying, per locale, as reported by
 * {@code /translations/pool}.
 *
 * <p>The jar's own {@code localization_credits/*.json} can only name people who were credited when
 * the build was cut. A volunteer whose fix was approved yesterday is being read by every player in
 * their language today and would otherwise go unnamed until the next release — so the names ride
 * along with the translations and are shown beside the baked credits.</p>
 *
 * <p>Only people who ASKED to be credited appear: the submit screen sends an empty translator name
 * when the "credit me" box is unticked, the relay drops blanks when building the list, and the
 * parser drops them again. Cached to disk beside the override layers so the credits screen still
 * names them on an offline launch.</p>
 */
public final class RelayTranslationCredits {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE = "contributors.json";
    /** A guard against a hostile or corrupt file, not a real limit — a locale has a handful. */
    private static final int MAX_PER_LOCALE = 200;

    private static final Map<String, List<String>> BY_LOCALE = new LinkedHashMap<>();
    private static boolean loaded;

    private RelayTranslationCredits() {}

    /** The credited translators for {@code locale}, newest approval last. Never null. */
    public static synchronized List<String> forLocale(String locale) {
        ensureLoaded();
        if (locale == null || locale.isBlank()) {
            return List.of();
        }
        return BY_LOCALE.getOrDefault(locale.toLowerCase(java.util.Locale.ROOT), List.of());
    }

    /** Replace one locale's list from a fresh pool response, and persist. */
    public static synchronized void put(String locale, List<String> names) {
        ensureLoaded();
        if (locale == null || locale.isBlank()) {
            return;
        }
        String key = locale.toLowerCase(java.util.Locale.ROOT);
        List<String> clean = new ArrayList<>();
        for (String name : names == null ? List.<String>of() : names) {
            if (clean.size() >= MAX_PER_LOCALE) {
                break;
            }
            String trimmed = name == null ? "" : name.trim();
            if (!trimmed.isEmpty() && !clean.contains(trimmed)) {
                clean.add(trimmed);
            }
        }
        List<String> previous = BY_LOCALE.get(key);
        if (clean.equals(previous)) {
            return; // nothing new to write — the fetch runs on every editor open
        }
        if (clean.isEmpty()) {
            BY_LOCALE.remove(key);
        } else {
            BY_LOCALE.put(key, List.copyOf(clean));
        }
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
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonArray()) {
                    continue;
                }
                List<String> names = new ArrayList<>();
                for (JsonElement el : entry.getValue().getAsJsonArray()) {
                    if (names.size() >= MAX_PER_LOCALE) {
                        break;
                    }
                    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                        String name = el.getAsString().trim();
                        if (!name.isEmpty() && !names.contains(name)) {
                            names.add(name);
                        }
                    }
                }
                if (!names.isEmpty()) {
                    BY_LOCALE.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), List.copyOf(names));
                }
            }
        } catch (Exception e) {
            // No credits is a worse outcome than stale credits, but neither is worth a crash.
            LOGGER.warn("[DungeonTrain] Translations: could not read {} — {}", path, e.toString());
        }
    }

    private static void save() {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            for (Map.Entry<String, List<String>> entry : BY_LOCALE.entrySet()) {
                JsonArray arr = new JsonArray();
                entry.getValue().forEach(arr::add);
                root.add(entry.getKey(), arr);
            }
            Files.writeString(path, root.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: could not write {} — {}", path, e.toString());
        }
    }

    /** Test seam — forget everything read from disk. */
    static synchronized void reset() {
        BY_LOCALE.clear();
        loaded = false;
    }
}
