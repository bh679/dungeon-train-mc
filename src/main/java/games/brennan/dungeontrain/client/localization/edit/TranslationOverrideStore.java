package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Disk persistence for {@link TranslationEdits}, one file per locale per {@link Layer}.
 *
 * <p>Lives at {@code <config>/dungeontrain/translations/<layer>/<locale>.json}, deliberately
 * <b>outside</b> {@code config/dungeontrain/user/}: that tree is the editor's package content,
 * which {@code UserContentExporter} zips wholesale and {@code UserContentPaths.dir} redirects
 * when the player switches active package. Translation overrides are neither — they are
 * per-install client state that must not travel inside a shared carriage pack, and must not
 * disappear when the active package changes.</p>
 *
 * <p>Best-effort throughout, like {@code ChatOutbox}: a missing or corrupt file yields empty
 * edits and never throws into the render thread. Writes go through a tmp file + rename so a
 * crash mid-save cannot leave a half-written locale behind.</p>
 */
public final class TranslationOverrideStore {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String DIR = "translations";
    private static final String LANG_FIELD = "lang";
    private static final String BOOKS_FIELD = "books";

    /**
     * Which body of overrides a file holds. Precedence when they are merged is LOCAL over
     * APPROVED over the jar — a translator working on a string must always see their own text,
     * even when the relay has since served a different approved translation for the same key.
     */
    public enum Layer {
        /** What this player edited in the in-game editor. */
        LOCAL("local"),
        /** What the relay has approved for this locale, cached from the last successful fetch. */
        APPROVED("approved"),
        /**
         * A snapshot of {@link #LOCAL} as it was when the player last pressed Submit. Not an
         * override layer — it is never applied — but it shares the shape exactly, so comparing it
         * to LOCAL answers "is there finished work the relay has not seen?" without a second
         * format to maintain.
         */
        SUBMITTED("submitted");

        private final String dirName;

        Layer(String dirName) {
            this.dirName = dirName;
        }

        public String dirName() {
            return dirName;
        }
    }

    private TranslationOverrideStore() {}

    /** {@code <config>/dungeontrain/translations/} — also the parent of the export/import dirs. */
    public static Path root() {
        return FMLPaths.CONFIGDIR.get().resolve("dungeontrain").resolve(DIR);
    }

    public static Path layerDir(Layer layer) {
        return root().resolve(layer.dirName());
    }

    /**
     * Read one locale's overrides for {@code layer}. Never throws — an absent, unreadable, or
     * malformed file is indistinguishable from "no overrides", which is the safe reading: the
     * jar's shipped translation stands.
     */
    public static TranslationEdits load(Layer layer, String locale) {
        if (locale == null || locale.isBlank()) {
            return TranslationEdits.empty("");
        }
        Path file = fileFor(layer, locale);
        if (file == null || !Files.isRegularFile(file)) {
            return TranslationEdits.empty(locale);
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] Translations: {} is not a JSON object; ignoring it.", file);
                return TranslationEdits.empty(locale);
            }
            JsonObject obj = root.getAsJsonObject();
            return new TranslationEdits(locale,
                readEntries(obj, LANG_FIELD), readEntries(obj, BOOKS_FIELD));
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: failed to read {}; ignoring it — {}",
                file, e.toString());
            return TranslationEdits.empty(locale);
        }
    }

    /**
     * Write one locale's overrides, deleting the file instead when there is nothing left to
     * store — an emptied locale should leave no trace, so a player who reverts every edit is
     * back to a clean install rather than an empty file that looks like state.
     *
     * @return true when the file now reflects {@code edits}
     */
    public static boolean save(Layer layer, TranslationEdits edits) {
        if (edits == null || edits.locale() == null || edits.locale().isBlank()) {
            return false;
        }
        Path file = fileFor(layer, edits.locale());
        if (file == null) {
            return false;
        }
        try {
            if (edits.isEmpty()) {
                Files.deleteIfExists(file);
                return true;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("locale", edits.locale());
            obj.add(LANG_FIELD, writeEntries(edits.lang()));
            obj.add(BOOKS_FIELD, writeEntries(edits.books()));
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, obj.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: failed to write {} — {}", file, e.toString());
            return false;
        }
    }

    /**
     * The file backing {@code locale} in {@code layer}, or null when the locale code is not a
     * plain locale code. Locale codes reach this from a config file and (later) from a relay
     * response, so the character allowlist is what stops either from walking out of the
     * translations directory.
     */
    static Path fileFor(Layer layer, String locale) {
        if (!isLocaleCode(locale)) {
            LOGGER.warn("[DungeonTrain] Translations: refusing suspicious locale code {}", locale);
            return null;
        }
        try {
            return layerDir(layer).resolve(locale.toLowerCase(Locale.ROOT) + ".json");
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] Translations: could not resolve config dir — {}", e.toString());
            return null;
        }
    }

    /** Minecraft locale codes are {@code [a-z0-9_]} (e.g. {@code pt_br}, {@code zh_cn}). */
    static boolean isLocaleCode(String locale) {
        if (locale == null || locale.isEmpty() || locale.length() > 32) {
            return false;
        }
        for (int i = 0; i < locale.length(); i++) {
            char c = Character.toLowerCase(locale.charAt(i));
            if ((c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '_') {
                return false;
            }
        }
        return true;
    }

    /**
     * One body's entries, skipping anything that isn't a usable string pair. Values run back
     * through {@link TranslationEdits#normalizeValue} rather than being trusted as written: this
     * file is hand-editable and (for the APPROVED layer) relay-supplied.
     */
    private static Map<String, String> readEntries(JsonObject obj, String field) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!obj.has(field) || !obj.get(field).isJsonObject()) {
            return out;
        }
        for (Map.Entry<String, JsonElement> entry : obj.getAsJsonObject(field).entrySet()) {
            if (out.size() >= TranslationEdits.MAX_ENTRIES) {
                LOGGER.warn("[DungeonTrain] Translations: '{}' has more than {} entries; "
                    + "ignoring the rest.", field, TranslationEdits.MAX_ENTRIES);
                break;
            }
            JsonElement value = entry.getValue();
            if (entry.getKey().isEmpty() || value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
                continue;
            }
            String normalized = TranslationEdits.normalizeValue(value.getAsString());
            if (normalized != null) {
                out.put(entry.getKey(), normalized);
            }
        }
        return out;
    }

    private static JsonObject writeEntries(Map<String, String> entries) {
        JsonObject obj = new JsonObject();
        entries.forEach(obj::addProperty);
        return obj;
    }
}
