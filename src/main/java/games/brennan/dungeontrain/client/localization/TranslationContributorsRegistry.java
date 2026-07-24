package games.brennan.dungeontrain.client.localization;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * In-memory registry of the human translators shown on the Credits page, loaded
 * from the single generated file {@code assets/dungeontrain/translation_contributors.json}.
 *
 * <p>Unlike {@link LocalizationCreditRegistry} (per-locale files that also drive the
 * language-list logo/ring and the "Localized by" menu label), this is one file,
 * grouped by person, purpose-built for the Credits page. It is generated at build
 * time from the repo-side provenance data + {@code authors.json}, so adding a
 * translator to the provenance data credits them here automatically — nothing is
 * hand-authored. Loaded on the same client resource-reload seam as the credit
 * registry (see {@link LocalizationCreditsClientLoaders}).</p>
 */
public final class TranslationContributorsRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The shipped generated file, at the namespace root (not a sub-directory). */
    private static final ResourceLocation FILE =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "translation_contributors.json");

    private static volatile List<TranslationContributor> CONTRIBUTORS = List.of();

    private TranslationContributorsRegistry() {}

    /** Every credited translator, in the file's (generated, strongest-share-first) order. */
    public static List<TranslationContributor> all() {
        return CONTRIBUTORS;
    }

    /** Reload from the given client {@link ResourceManager}; a missing file clears the list. */
    public static void load(ResourceManager resourceManager) {
        Optional<Resource> resource = resourceManager.getResource(FILE);
        if (resource.isEmpty()) {
            CONTRIBUTORS = List.of();
            LOGGER.info("[DungeonTrain] TranslationContributors: no {} present — Credits translator list empty.",
                FILE);
            return;
        }
        try (InputStream in = resource.get().open()) {
            CONTRIBUTORS = parse(in);
            LOGGER.info("[DungeonTrain] TranslationContributors loaded — {} contributor(s).",
                CONTRIBUTORS.size());
        } catch (Exception e) {
            CONTRIBUTORS = List.of();
            LOGGER.error("[DungeonTrain] TranslationContributors: failed to read {} — {}", FILE, e.toString());
        }
    }

    private static List<TranslationContributor> parse(InputStream in) {
        JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        if (!root.isJsonObject()) {
            LOGGER.error("[DungeonTrain] TranslationContributors: root is not a JSON object.");
            return List.of();
        }
        JsonElement contributorsEl = root.getAsJsonObject().get("contributors");
        if (contributorsEl == null || !contributorsEl.isJsonArray()) {
            return List.of();
        }
        List<TranslationContributor> out = new ArrayList<>();
        for (JsonElement el : contributorsEl.getAsJsonArray()) {
            TranslationContributor contributor = parseContributor(el);
            if (contributor != null) {
                out.add(contributor);
            }
        }
        return List.copyOf(out);
    }

    /** One contributor, or null when the entry is malformed (logged, skipped — never fatal). */
    private static TranslationContributor parseContributor(JsonElement el) {
        if (!el.isJsonObject()) {
            return null;
        }
        JsonObject obj = el.getAsJsonObject();
        String name = optionalString(obj, "name");
        if (name == null) {
            LOGGER.warn("[DungeonTrain] TranslationContributors: entry missing a name — skipped.");
            return null;
        }
        Optional<String> url = Optional.ofNullable(optionalString(obj, "url"));

        List<TranslationContributor.LanguageShare> languages = new ArrayList<>();
        JsonElement langsEl = obj.get("languages");
        if (langsEl != null && langsEl.isJsonArray()) {
            for (JsonElement le : langsEl.getAsJsonArray()) {
                TranslationContributor.LanguageShare share = parseShare(le);
                if (share != null) {
                    languages.add(share);
                }
            }
        }
        if (languages.isEmpty()) {
            return null; // a contributor with no language contributes nothing to show
        }
        return new TranslationContributor(name, url, languages);
    }

    private static TranslationContributor.LanguageShare parseShare(JsonElement el) {
        if (!el.isJsonObject()) {
            return null;
        }
        JsonObject obj = el.getAsJsonObject();
        String locale = optionalString(obj, "locale");
        Integer contributed = optionalInt(obj, "contributed");
        Integer total = optionalInt(obj, "total");
        if (locale == null || contributed == null || total == null || total <= 0 || contributed <= 0) {
            return null;
        }
        return new TranslationContributor.LanguageShare(locale, contributed, total);
    }

    private static String optionalString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            String v = el.getAsString();
            return v.isEmpty() ? null : v;
        }
        return null;
    }

    private static Integer optionalInt(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        double v = el.getAsDouble();
        if (v != Math.floor(v) || Double.isInfinite(v)) {
            return null;
        }
        return (int) v;
    }
}
