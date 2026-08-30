package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Everything a translator can edit for one locale, assembled from what the game actually has
 * loaded — the lang files of Dungeon Train and the three sibling mods whose translations are
 * committed here, plus every prose field of every narrative book.
 *
 * <p>Nothing is hardcoded but the namespace list: locales, keys and books are all discovered,
 * mirroring {@code DungeonTrainLanguages}. The two bodies come from different places for a
 * reason:</p>
 * <ul>
 *   <li>Lang files through the client {@link ResourceManager}, merged across the pack stack, so
 *       what the editor shows is what the player is actually seeing — including any localization
 *       resource pack they have enabled.</li>
 *   <li>Books through {@link ModJarResources}, because {@code data/} is the server channel and
 *       the editor opens at the title screen.</li>
 * </ul>
 *
 * <p>A side benefit of reading English through the resource manager: the sibling mods ship their
 * own {@code en_us}, so this can show their English source, which the repo-side
 * {@code build-review-package.py} cannot (their English lives in their own repos).</p>
 *
 * <p>Cached per locale — building it parses ~1100 keys × 4 namespaces plus ~50 books, which is
 * cheap but not free, and the screen rebuilds its list on every keystroke.</p>
 */
public final class TranslationCatalog {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The namespaces whose translations live in this repo — DT plus the three sibling mods
     * (English-only upstream, so their community translations are committed and provenance-
     * tracked here). Matches {@code provenance_io.SIBLING_NAMESPACES}.
     */
    public static final List<String> NAMESPACES =
        List.of("dungeontrain", "adventureitemnames", "playermob", "discordpresence");

    private static final String SOURCE_LOCALE = "en_us";
    private static final String NARRATIVE_ROOT = "data/dungeontrain/narrative_localizations";
    private static final String NARRATIVES_BASE = "data/dungeontrain/narratives";
    private static final String DEATH_LORE_BASE = "data/dungeontrain/death_lore";
    private static final String DEATH_LORE_CATEGORY = "death_lore/";
    private static final String JSON_EXT = ".json";

    private static String cachedLocale;
    private static List<TranslationUnit> cached;

    private TranslationCatalog() {}

    /** Drop the cache — call after a resource reload or a language switch. */
    public static synchronized void invalidate() {
        cachedLocale = null;
        cached = null;
    }

    /**
     * Every editable unit for {@code locale}, lang keys first (in English-file order) then book
     * fields (in book then document order). Empty for {@code en_us}: English is the source, not
     * a translation, and is never a target.
     */
    public static synchronized List<TranslationUnit> forLocale(String locale) {
        String code = locale == null ? "" : locale.toLowerCase(Locale.ROOT);
        if (code.isEmpty() || SOURCE_LOCALE.equals(code)) {
            return List.of();
        }
        if (code.equals(cachedLocale) && cached != null) {
            return cached;
        }
        List<TranslationUnit> units = new ArrayList<>();
        try {
            collectLangUnits(code, units);
            collectBookUnits(code, units);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Translations: failed to build the catalog for {} — {}",
                code, e.toString());
        }
        cachedLocale = code;
        cached = List.copyOf(units);
        LOGGER.debug("[DungeonTrain] Translations: catalog for {} — {} unit(s).", code, cached.size());
        return cached;
    }

    private static void collectLangUnits(String locale, List<TranslationUnit> out) {
        for (String namespace : NAMESPACES) {
            Map<String, String> english = readLang(namespace, SOURCE_LOCALE);
            Map<String, String> translated = readLang(namespace, locale);
            if (english.isEmpty() && translated.isEmpty()) {
                continue; // this namespace isn't installed
            }
            // English order first (that is the order the sidecars and review CSVs use), then any
            // key the locale has but English doesn't — zh_cn/zh_tw legitimately carry extras.
            //
            // English's keys are projected through this language's plural rules on the way in, so
            // a translator is offered the forms their own grammar reaches rather than English's.
            // The union with what the locale really ships happens AFTER, so a key already in the
            // file stays editable either way. See TranslationPluralForms.
            Set<String> keys = new LinkedHashSet<>(
                TranslationPluralForms.project(english.keySet(), locale));
            keys.addAll(translated.keySet());
            for (String key : keys) {
                out.add(new TranslationUnit(
                    TranslationUnit.Type.LANG,
                    namespace,
                    key,
                    english.getOrDefault(key, ""),
                    translated.getOrDefault(key, ""),
                    ProvenanceManifestRegistry.isAiUnreviewedLang(locale, namespace, key)));
            }
        }
    }

    /**
     * Book prose, driven by the ENGLISH originals rather than by what the locale happens to have.
     *
     * <p>The direction is the whole point. Reading the locale's own directory and iterating its
     * fields — which is what this did — can only ever show prose somebody has already translated:
     * a book the locale has never touched has no file, a field nobody filled in has no entry, and
     * both were simply absent from the editor. That is exactly the work a translator is looking
     * for, so the one body of text they most needed to find was the one they could not.</p>
     *
     * <p>English first, then the locale's own extras, mirroring {@link #collectLangUnits}'s union —
     * a field or a book that exists only in the locale is still real and still editable.</p>
     */
    private static void collectBookUnits(String locale, List<TranslationUnit> out) {
        String root = NARRATIVE_ROOT + "/" + locale;
        Map<String, String> localeFiles = ModJarResources.readAll(root, JSON_EXT);

        // Both English trees, keyed the way the locale overlay names them (see englishPathFor).
        Map<String, String> englishByBook = new LinkedHashMap<>();
        for (Map.Entry<String, String> file : ModJarResources.readAll(NARRATIVES_BASE, JSON_EXT).entrySet()) {
            englishByBook.put(bookPathFor(file.getKey()), file.getValue());
        }
        for (Map.Entry<String, String> file : ModJarResources.readAll(DEATH_LORE_BASE, JSON_EXT).entrySet()) {
            englishByBook.put(bookPathFor(file.getKey()), file.getValue());
        }

        Set<String> bookPaths = new LinkedHashSet<>(englishByBook.keySet());
        for (String path : localeFiles.keySet()) {
            bookPaths.add(path.substring(root.length() + 1, path.length() - JSON_EXT.length()));
        }

        for (String bookPath : bookPaths) {
            Map<String, String> english = NarrativeBookFields.flatten(englishByBook.get(bookPath));
            Map<String, String> translated = NarrativeBookFields.flatten(
                localeFiles.get(root + "/" + bookPath + JSON_EXT));
            if (english.isEmpty() && translated.isEmpty()) {
                continue; // not prose — an index or a malformed file, with nothing to show either way
            }
            boolean aiUnreviewed = ProvenanceManifestRegistry.isAiUnreviewedBook(locale, bookPath);
            Set<String> fields = new LinkedHashSet<>(english.keySet());
            fields.addAll(translated.keySet());
            for (String field : fields) {
                out.add(new TranslationUnit(
                    TranslationUnit.Type.BOOK,
                    "dungeontrain",
                    bookPath + "#" + field,
                    english.getOrDefault(field, ""),
                    translated.getOrDefault(field, ""),
                    aiUnreviewed));
            }
        }
    }

    /**
     * The locale-relative book path an English resource path belongs to — the inverse of
     * {@link #englishPathFor}, and the reason both live next to each other.
     */
    static String bookPathFor(String englishPath) {
        String path = englishPath.endsWith(JSON_EXT)
            ? englishPath.substring(0, englishPath.length() - JSON_EXT.length())
            : englishPath;
        if (path.startsWith(DEATH_LORE_BASE + "/")) {
            return DEATH_LORE_CATEGORY + path.substring(DEATH_LORE_BASE.length() + 1);
        }
        if (path.startsWith(NARRATIVES_BASE + "/")) {
            return path.substring(NARRATIVES_BASE.length() + 1);
        }
        return path;
    }

    /**
     * Where a locale-relative book path's English original lives. Inverts the overlay mapping in
     * {@code NarrativeContentLocale#localized}: everything sits under {@code narratives/} except
     * death lore, whose English base is its own top-level directory.
     */
    static String englishPathFor(String bookPath) {
        String base = bookPath.startsWith(DEATH_LORE_CATEGORY)
            ? DEATH_LORE_BASE + "/" + bookPath.substring(DEATH_LORE_CATEGORY.length())
            : NARRATIVES_BASE + "/" + bookPath;
        return base + JSON_EXT;
    }

    /**
     * One namespace's lang file for {@code locale}, merged across the whole pack stack so a
     * resource pack's overrides win — the same precedence the game itself applies. Missing or
     * malformed files yield an empty map rather than failing the catalog.
     */
    static Map<String, String> readLang(String namespace, String locale) {
        Map<String, String> out = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return out;
        }
        ResourceManager resourceManager = mc.getResourceManager();
        ResourceLocation id =
            ResourceLocation.fromNamespaceAndPath(namespace, "lang/" + locale + JSON_EXT);
        // Lowest priority first, so later packs overwrite earlier ones.
        for (Resource resource : resourceManager.getResourceStack(id)) {
            try (var in = resource.open()) {
                JsonElement root =
                    JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                if (!root.isJsonObject()) {
                    continue;
                }
                JsonObject obj = root.getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                    JsonElement value = entry.getValue();
                    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                        out.put(entry.getKey(), value.getAsString());
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("[DungeonTrain] Translations: skipping {} — {}", id, e.toString());
            }
        }
        return out;
    }
}
