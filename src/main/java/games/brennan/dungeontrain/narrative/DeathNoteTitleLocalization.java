package games.brennan.dungeontrain.narrative;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side table of localized "Death Note" trigger titles, keyed by client locale. Backs the
 * multilingual title check in {@link DeathNoteTitle#isDeathNoteTitle(String, Collection)}: the sign
 * interceptor looks up the <em>author's</em> client locale and passes the translated trigger words
 * so, e.g., a Simplified-Chinese player titling their book {@code 死亡笔记} triggers the curse. The
 * canonical English "death note" is always accepted by {@link DeathNoteTitle} regardless of this
 * table, so this only <em>adds</em> per-locale aliases.
 *
 * <h2>Single source of truth: the instruction book</h2>
 * The game ships a random loot book that teaches players how to make a Death Note
 * ({@code data/dungeontrain/narratives/random_books/deathnote.json}, whose per-locale overlays live
 * at {@code narrative_localizations/<locale>/random_books/deathnote.json}). The word that book is
 * <em>titled</em> is exactly the word a player must name their own book — so the trigger for each
 * locale is derived straight from that locale's instruction-book title. There is no separate
 * trigger file to keep in sync: one translated book drives both the shown instructions and the
 * trigger, and they cannot drift because they are the same string. Adding a new language's book
 * overlay automatically enables that language's trigger.
 *
 * <p>The English base title ("Deathnote") is intentionally ignored here — it normalizes to
 * {@code deathnote}, which {@link DeathNoteTitle} already accepts everywhere. Only genuinely
 * different (non-English) overlay titles are stored.</p>
 *
 * <p>Client {@code .lang} assets aren't loaded on a dedicated server, so this reads the server-data
 * channel (bundled data + datapack overrides) via {@link ResourceManager}. Registered by
 * {@link NarrativeDataLoaders}, so it honours {@code /reload}. The load body is plain
 * {@link ResourceManager} code, matching the other narrative loaders.</p>
 */
public final class DeathNoteTitleLocalization {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Root of the per-locale narrative overlays (shared with {@link NarrativeContentLocale#ROOT}). */
    private static final String ROOT = NarrativeContentLocale.ROOT;
    /** The instruction book whose title defines the trigger word: {@code random_books/deathnote}. */
    static final String INSTRUCTION_BOOK_SUFFIX = "/random_books/deathnote.json";

    /**
     * Locale (lowercased, e.g. {@code zh_cn}) → normalized accepted titles. Rebuilt wholesale on each
     * reload and swapped in atomically (immutable value; no in-place mutation). {@code volatile}
     * suffices: written on the server thread during reload, read on the server thread while signing.
     */
    private static volatile Map<String, List<String>> byLocale = Map.of();

    private DeathNoteTitleLocalization() {}

    /**
     * The accepted trigger titles for {@code locale}, or an empty list when the locale is unknown,
     * blank, English, or ships no localized instruction book. Returned titles are already
     * {@link DeathNoteTitle#normalize normalized}; callers still get English matching for free from
     * {@link DeathNoteTitle}.
     */
    public static Collection<String> titlesFor(String locale) {
        if (locale == null || locale.isEmpty()) return List.of();
        return byLocale.getOrDefault(locale.toLowerCase(Locale.ROOT), List.of());
    }

    /**
     * Reload the table by scanning the per-locale instruction-book overlays through the datapack
     * channel (bundled data + datapack overrides). Called by the reload listener at world load /
     * {@code /reload} and by {@code /dungeontrain narrative reload}. Builds a fresh map and swaps it
     * in — never mutates the live one.
     */
    public static void load(ResourceManager resourceManager) {
        Map<String, List<String>> next = new HashMap<>();
        Map<ResourceLocation, Resource> resources =
            resourceManager.listResources(ROOT, rl -> rl.getPath().endsWith(INSTRUCTION_BOOK_SUFFIX));
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation file = entry.getKey();
            String locale = localeFromPath(file.getPath());
            if (locale.isEmpty()) continue;
            try (InputStream in = entry.getValue().open();
                 Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                String title = titleFrom(r);
                // Ignore an absent/blank title or one that reduces to the always-accepted English form.
                if (!title.isEmpty()) {
                    next.put(locale, List.of(title));
                }
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] deathnote trigger: failed reading '{}': {}", file, e.toString());
            }
        }
        byLocale = Map.copyOf(next);
        LOGGER.info("[DungeonTrain] deathnote localized triggers loaded — {} locale(s)", byLocale.size());
    }

    /** Drop the table (called on server stop). */
    public static void clear() {
        byLocale = Map.of();
    }

    /**
     * The locale key from an overlay path like
     * {@code narrative_localizations/zh_cn/random_books/deathnote.json} → {@code zh_cn}. Empty when
     * the path isn't a {@code <root>/<locale>/…} overlay.
     */
    static String localeFromPath(String path) {
        String prefix = ROOT + "/";
        if (!path.startsWith(prefix)) return "";
        String rest = path.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) return "";
        return rest.substring(0, slash).toLowerCase(Locale.ROOT);
    }

    /**
     * The normalized {@code title} of an instruction-book JSON body, or {@code ""} when the title is
     * absent/blank, a placeholder ({@code "Untitled"}), or reduces to the English {@code deathnote}
     * form (already accepted everywhere). Never throws — malformed JSON yields {@code ""}.
     */
    static String titleFrom(Reader reader) {
        JsonElement root;
        try {
            root = JsonParser.parseReader(reader);
        } catch (Exception e) {
            return "";
        }
        if (!root.isJsonObject()) return "";
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("title") || !obj.get("title").isJsonPrimitive()
                || !obj.getAsJsonPrimitive("title").isString()) {
            return "";
        }
        String raw = obj.get("title").getAsString();
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("Untitled")) return "";
        String normalized = DeathNoteTitle.normalize(raw);
        // "Deathnote" (English base) normalizes to the always-accepted form — nothing to add.
        return DeathNoteTitle.isDeathNoteTitle(raw) ? "" : normalized;
    }
}
