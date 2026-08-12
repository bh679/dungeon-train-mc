package games.brennan.dungeontrain.narrative;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.localization.edit.NarrativeBookWriter;
import games.brennan.dungeontrain.client.localization.edit.TranslationEdits;
import games.brennan.dungeontrain.client.localization.edit.TranslationOverrideStore;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies the in-game translation editor's book edits to the prose the registries load.
 *
 * <p>Book text is datapack content the <b>server</b> owns, so unlike a lang key an edit here
 * cannot be applied client-side. On an integrated server (single-player, or a LAN host) the
 * editor and the server share a process and a config directory, so the same override files the
 * client wrote are readable from here. On a dedicated server there simply are none, and every
 * method below degrades to "no overrides" — which is correct: on a remote server the prose comes
 * from that server, and a client's local edit is submit-only until it is approved.</p>
 *
 * <p>This reaches into {@code client.localization.edit} for the store and the JSON writer. Both
 * are deliberately side-agnostic — file IO and Gson, no client-only types and no
 * {@code @OnlyIn} — so loading them here is safe; they live there because that is where the rest
 * of the editor lives.</p>
 */
public final class NarrativeTranslationOverrides {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Cached per content locale so a registry reload does not re-read the files 50 times. */
    private static String cachedLocale;
    private static Map<String, Map<String, String>> cachedByBook = Map.of();

    private NarrativeTranslationOverrides() {}

    /**
     * Re-read the override files for the active content locale. Called at the top of a registry
     * reload sweep, so the four registries share one read.
     */
    public static synchronized void refresh() {
        String locale = NarrativeContentLocale.current();
        cachedLocale = locale;
        cachedByBook = locale.isEmpty() ? Map.of() : groupByBook(locale);
        if (!cachedByBook.isEmpty()) {
            LOGGER.info("[DungeonTrain] Translations: {} book(s) carry local overrides for '{}'.",
                cachedByBook.size(), locale);
        }
    }

    /**
     * Read both layers and split their book overrides by book path. Same precedence as the
     * client: the host's own edits beat relay-approved text, which beats what the mod ships.
     */
    private static Map<String, Map<String, String>> groupByBook(String locale) {
        try {
            TranslationEdits merged =
                TranslationOverrideStore.load(TranslationOverrideStore.Layer.APPROVED, locale)
                    .mergedWith(TranslationOverrideStore.load(TranslationOverrideStore.Layer.LOCAL, locale));
            Map<String, Map<String, String>> byBook = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : merged.books().entrySet()) {
                int hash = entry.getKey().indexOf('#');
                if (hash <= 0 || hash == entry.getKey().length() - 1) {
                    continue; // not a <book>#<field> key
                }
                byBook.computeIfAbsent(entry.getKey().substring(0, hash), k -> new LinkedHashMap<>())
                    .put(entry.getKey().substring(hash + 1), entry.getValue());
            }
            return byBook;
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Translations: could not read book overrides — {}", t.toString());
            return Map.of();
        }
    }

    /**
     * The book's JSON with any overrides folded in, as a stream the registry's codec can parse.
     *
     * <p>Wrapping the stream rather than changing the four codecs is deliberate: each registry
     * parses a different shape, and the override paths are expressed against the JSON tree, so
     * one text-level hook covers all of them and none of them has to know this exists.</p>
     *
     * @param source   the resource the registry would otherwise have opened
     * @param bookPath the overlay-relative path, e.g. {@code random_books/deathnote}
     */
    public static InputStream open(net.minecraft.server.packs.resources.Resource source,
                                   String bookPath) throws java.io.IOException {
        Map<String, String> overrides = overridesFor(bookPath);
        if (overrides.isEmpty()) {
            return source.open();
        }
        try (InputStream in = source.open()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            int applied = NarrativeBookWriter.apply(root, overrides);
            if (applied == 0) {
                return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
            }
            return new ByteArrayInputStream(root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // A bad override must never cost the server a book — fall back to the shipped text.
            LOGGER.warn("[DungeonTrain] Translations: could not apply overrides to {} — {}",
                bookPath, e.toString());
            return source.open();
        }
    }

    private static synchronized Map<String, String> overridesFor(String bookPath) {
        if (!NarrativeContentLocale.current().equals(cachedLocale)) {
            // A locale switch that skipped refresh() — read rather than serve the wrong language.
            refresh();
        }
        return cachedByBook.getOrDefault(bookPath, Map.of());
    }
}
