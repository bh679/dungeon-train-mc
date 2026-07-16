package games.brennan.dungeontrain.narrative;

import com.google.gson.JsonArray;
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
import java.util.ArrayList;
import java.util.Collection;
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
 * <p>Client {@code .lang} assets aren't loaded on a dedicated server, so the translated titles live
 * in the server-data channel: bundled files under {@code data/dungeontrain/deathnote_titles/*.json},
 * each named for its locale ({@code zh_cn.json}) and containing a JSON array of accepted titles
 * (or {@code {"titles":[...]}}). A datapack can override or add locales. Registered by
 * {@link NarrativeDataLoaders}, so it honours {@code /reload}.</p>
 *
 * <p>The load body is plain {@link ResourceManager} code (no loader-specific imports), matching the
 * other narrative loaders, so a future Fabric entrypoint can reuse it.</p>
 */
public final class DeathNoteTitleLocalization {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Datapack directory holding the per-locale trigger-title files. */
    static final String SUBDIR = "deathnote_titles";
    private static final String EXT = ".json";

    /**
     * Locale (lowercased, e.g. {@code zh_cn}) → normalized accepted titles. Rebuilt wholesale on each
     * reload and swapped in atomically (immutable value; no in-place mutation). {@code volatile}
     * suffices: written on the server thread during reload, read on the server thread while signing.
     */
    private static volatile Map<String, List<String>> byLocale = Map.of();

    private DeathNoteTitleLocalization() {}

    /**
     * The accepted trigger titles for {@code locale}, or an empty list when the locale is unknown,
     * blank, or English. Returned titles are already {@link DeathNoteTitle#normalize normalized};
     * callers still get English matching for free from {@link DeathNoteTitle}.
     */
    public static Collection<String> titlesFor(String locale) {
        if (locale == null || locale.isEmpty()) return List.of();
        return byLocale.getOrDefault(locale.toLowerCase(Locale.ROOT), List.of());
    }

    /**
     * Reload the table from the datapack channel (bundled data + datapack overrides). Called by the
     * reload listener at world load / {@code /reload} and by the {@code /dungeontrain narrative reload}
     * command. Builds a fresh map and swaps it in — never mutates the live one.
     */
    public static void load(ResourceManager resourceManager) {
        Map<String, List<String>> next = new java.util.HashMap<>();
        Map<ResourceLocation, Resource> resources =
            resourceManager.listResources(SUBDIR, rl -> rl.getPath().endsWith(EXT));
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation file = entry.getKey();
            String locale = localeOf(file.getPath());
            if (locale.isEmpty()) continue;
            try (InputStream in = entry.getValue().open();
                 Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                List<String> titles = parseTitles(r, file.toString());
                if (!titles.isEmpty()) {
                    next.computeIfAbsent(locale, k -> new ArrayList<>()).addAll(titles);
                }
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] deathnote_titles: failed reading '{}': {}", file, e.toString());
            }
        }
        // Freeze each locale's list before publishing the immutable snapshot.
        Map<String, List<String>> frozen = new java.util.HashMap<>();
        for (Map.Entry<String, List<String>> e : next.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }
        byLocale = Map.copyOf(frozen);
        LOGGER.info("[DungeonTrain] deathnote_titles loaded — {} locale(s)", byLocale.size());
    }

    /** Drop the table (called on server stop). */
    public static void clear() {
        byLocale = Map.of();
    }

    /** The locale key from a resource path like {@code deathnote_titles/zh_cn.json} → {@code zh_cn}. */
    static String localeOf(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (!name.endsWith(EXT)) return "";
        return name.substring(0, name.length() - EXT.length()).toLowerCase(Locale.ROOT);
    }

    /** Parse a file body — a JSON array of titles, or {@code {"titles":[...]}}. Normalizes each entry. */
    static List<String> parseTitles(Reader reader, String source) {
        JsonElement root;
        try {
            root = JsonParser.parseReader(reader);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] deathnote_titles: {} is not valid JSON — skipped ({})",
                    source, e.toString());
            return List.of();
        }
        JsonArray arr;
        if (root.isJsonArray()) {
            arr = root.getAsJsonArray();
        } else if (root.isJsonObject() && root.getAsJsonObject().has("titles")
                && root.getAsJsonObject().get("titles").isJsonArray()) {
            arr = root.getAsJsonObject().getAsJsonArray("titles");
        } else {
            LOGGER.warn("[DungeonTrain] deathnote_titles: {} is neither an array nor {{titles:[...]}} — skipped",
                    source);
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) continue;
            String title = el.getAsString();
            if (title == null || title.isBlank()) continue;
            out.add(DeathNoteTitle.normalize(title));
        }
        return out;
    }
}
