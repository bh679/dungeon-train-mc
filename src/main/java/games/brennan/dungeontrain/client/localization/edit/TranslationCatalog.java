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
            Set<String> keys = new LinkedHashSet<>(english.keySet());
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

    private static void collectBookUnits(String locale, List<TranslationUnit> out) {
        String root = NARRATIVE_ROOT + "/" + locale;
        Map<String, String> files = ModJarResources.readAll(root, JSON_EXT);
        for (Map.Entry<String, String> file : files.entrySet()) {
            String bookPath = file.getKey().substring(root.length() + 1,
                file.getKey().length() - JSON_EXT.length());
            Map<String, String> translated = NarrativeBookFields.flatten(file.getValue());
            if (translated.isEmpty()) {
                continue;
            }
            Map<String, String> english =
                NarrativeBookFields.flatten(ModJarResources.read(englishPathFor(bookPath)));
            boolean aiUnreviewed = ProvenanceManifestRegistry.isAiUnreviewedBook(locale, bookPath);
            for (Map.Entry<String, String> field : translated.entrySet()) {
                out.add(new TranslationUnit(
                    TranslationUnit.Type.BOOK,
                    "dungeontrain",
                    bookPath + "#" + field.getKey(),
                    english.getOrDefault(field.getKey(), ""),
                    field.getValue(),
                    aiUnreviewed));
            }
        }
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
    private static Map<String, String> readLang(String namespace, String locale) {
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
