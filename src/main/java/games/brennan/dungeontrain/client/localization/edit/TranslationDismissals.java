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
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The strings this player has marked <b>good as is</b> — machine translation they have read and
 * decided needs no fixing.
 *
 * <p>The editor opens on the AI-unreviewed queue, which is every string the jar machine-translated
 * and nobody has been through. Most of those are correct. Without a way to say so the queue can
 * only ever be emptied by rewriting lines that did not need rewriting, so a translator working
 * honestly through it meets the same lines again on every visit — the fastest way to spend the
 * goodwill this feature runs on.</p>
 *
 * <p>Per install and per locale, kept beside the override layers in
 * {@code <config>/dungeontrain/translations/dismissed/<locale>.json}, and reversible: the edit
 * screen always offers the opposite of the state a string is in. This file is the authority — the
 * copy sent to the relay ({@link TranslationDismissClient}) is a record and nothing more, so a
 * client that never reaches the relay loses none of this.</p>
 *
 * <p>Best-effort throughout, like {@link TranslationOverrideStore}: an unreadable file reads as
 * "nothing dismissed", which leaves every string in the queue — failing towards more work rather
 * than towards quietly hiding something.</p>
 */
public final class TranslationDismissals {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String DIR = "dismissed";
    private static final String LANG_FIELD = "lang";
    private static final String BOOKS_FIELD = "books";
    /** Same ceiling as an override body — a locale has ~1100 keys and ~50 book fields. */
    private static final int MAX_ENTRIES = TranslationEdits.MAX_ENTRIES;

    /** locale → what is dismissed in it. Read once per locale per session, written through. */
    private static final Map<String, Sets> CACHE = new HashMap<>();

    private record Sets(Set<String> lang, Set<String> books) {
        static Sets empty() {
            return new Sets(new LinkedHashSet<>(), new LinkedHashSet<>());
        }

        Set<String> of(TranslationUnit.Type type) {
            return type == TranslationUnit.Type.BOOK ? books : lang;
        }

        int size() {
            return lang.size() + books.size();
        }
    }

    private TranslationDismissals() {}

    /** Whether this player has already said {@code unit} reads fine as it is. */
    public static synchronized boolean isDismissed(String locale, TranslationUnit unit) {
        if (unit == null) {
            return false;
        }
        return sets(locale).of(unit.type()).contains(unit.id());
    }

    /**
     * Mark {@code unit} good as is, or put it back in the queue.
     *
     * @return true when the stored state actually changed — the caller uses it to decide whether
     *         the relay is worth telling, so a repeated click costs nothing
     */
    public static synchronized boolean set(String locale, TranslationUnit unit, boolean dismissed) {
        if (unit == null || locale == null || locale.isBlank()) {
            return false;
        }
        Sets current = sets(locale);
        Set<String> body = current.of(unit.type());
        boolean changed = dismissed
            ? (body.size() < MAX_ENTRIES || body.contains(unit.id())) && body.add(unit.id())
            : body.remove(unit.id());
        if (changed) {
            save(key(locale), current);
        }
        return changed;
    }

    /** How many strings are dismissed for {@code locale}. */
    public static synchronized int count(String locale) {
        return sets(locale).size();
    }

    /** Drop the cached state for {@code locale} so the next read comes off disk again. */
    public static synchronized void invalidate(String locale) {
        CACHE.remove(key(locale));
    }

    private static Sets sets(String locale) {
        String code = key(locale);
        Sets cached = CACHE.get(code);
        if (cached != null) {
            return cached;
        }
        Sets loaded = load(code);
        CACHE.put(code, loaded);
        return loaded;
    }

    private static String key(String locale) {
        return locale == null ? "" : locale.toLowerCase(Locale.ROOT);
    }

    private static Sets load(String locale) {
        Path file = fileFor(locale);
        if (file == null || !Files.isRegularFile(file)) {
            return Sets.empty();
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                LOGGER.warn("[DungeonTrain] Translations: {} is not a JSON object; ignoring it.", file);
                return Sets.empty();
            }
            JsonObject obj = root.getAsJsonObject();
            return new Sets(readIds(obj, LANG_FIELD), readIds(obj, BOOKS_FIELD));
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: failed to read {}; ignoring it — {}",
                file, e.toString());
            return Sets.empty();
        }
    }

    private static void save(String locale, Sets sets) {
        Path file = fileFor(locale);
        if (file == null) {
            return;
        }
        try {
            if (sets.size() == 0) {
                // Nothing dismissed leaves no file, so un-dismissing the last string returns the
                // install to a clean state rather than to an empty file that looks like state.
                Files.deleteIfExists(file);
                return;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("locale", locale);
            obj.add(LANG_FIELD, writeIds(sets.lang()));
            obj.add(BOOKS_FIELD, writeIds(sets.books()));
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, obj.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: failed to write {} — {}", file, e.toString());
        }
    }

    /**
     * The file backing {@code locale}, or null when that is not a plain locale code — the same
     * allowlist {@link TranslationOverrideStore#fileFor} applies, and for the same reason: locale
     * codes reach this from a config file and from relay responses.
     */
    static Path fileFor(String locale) {
        if (!TranslationOverrideStore.isLocaleCode(locale)) {
            LOGGER.warn("[DungeonTrain] Translations: refusing suspicious locale code {}", locale);
            return null;
        }
        try {
            return TranslationOverrideStore.root().resolve(DIR)
                .resolve(locale.toLowerCase(Locale.ROOT) + ".json");
        } catch (Exception e) {
            LOGGER.debug("[DungeonTrain] Translations: could not resolve config dir — {}", e.toString());
            return null;
        }
    }

    /** A JSON array of ids, bounded, skipping anything that is not a non-empty string. */
    static Set<String> readIds(JsonObject obj, String field) {
        Set<String> out = new LinkedHashSet<>();
        if (!obj.has(field) || !obj.get(field).isJsonArray()) {
            return out;
        }
        for (JsonElement el : obj.getAsJsonArray(field)) {
            if (out.size() >= MAX_ENTRIES) {
                LOGGER.warn("[DungeonTrain] Translations: '{}' holds more than {} dismissals; "
                    + "ignoring the rest.", field, MAX_ENTRIES);
                break;
            }
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
                continue;
            }
            String id = el.getAsString();
            if (!id.isEmpty()) {
                out.add(id);
            }
        }
        return out;
    }

    private static JsonArray writeIds(Set<String> ids) {
        JsonArray arr = new JsonArray();
        ids.forEach(arr::add);
        return arr;
    }
}
