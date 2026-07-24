package games.brennan.dungeontrain.client.localization;

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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * In-memory registry of every {@link LocalizationCredit} at
 * {@code assets/<ns>/localization_credits/}.
 *
 * <p>Loaded through the <b>client</b> resource-pack pipeline (see
 * {@link LocalizationCreditsClientLoaders}), not the server datapack pipeline
 * the narrative prose registries use — the main menu has no world/server
 * loaded yet, so only resource packs (already active at the title screen) can
 * feed this. A localization resource pack ships one file per contributor
 * alongside its {@code lang/<locale>.json} override.</p>
 */
public final class LocalizationCreditRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** ResourceManager directory (namespace-relative, no leading/trailing slash). */
    private static final String DIR = "localization_credits";
    private static final String JSON_EXT = ".json";

    private static final Map<ResourceLocation, LocalizationCredit> CREDITS = new LinkedHashMap<>();

    private LocalizationCreditRegistry() {}

    /** Reload from the given client {@link ResourceManager} (bundled + resource-pack overrides). */
    public static synchronized void load(ResourceManager resourceManager) {
        CREDITS.clear();
        int loaded = 0;
        int failed = 0;
        Map<ResourceLocation, Resource> resources =
            resourceManager.listResources(DIR, rl -> rl.getPath().endsWith(JSON_EXT));
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation file = entry.getKey();
            ResourceLocation id = stripJson(file);
            try (InputStream in = entry.getValue().open()) {
                CREDITS.put(id, parse(in, id));
                loaded++;
            } catch (ParseException e) {
                LOGGER.error("[DungeonTrain] LocalizationCredits: failed to parse {} — {}", file, e.getMessage());
                failed++;
            } catch (Exception e) {
                LOGGER.error("[DungeonTrain] LocalizationCredits: unexpected error reading {} — {}", file, e.toString());
                failed++;
            }
        }
        LOGGER.info("[DungeonTrain] LocalizationCredits registry loaded — {} credits from '{}' (failed: {})",
            loaded, DIR, failed);
    }

    /** Drop every loaded credit. */
    public static synchronized void clear() {
        CREDITS.clear();
    }

    public static synchronized int count() {
        return CREDITS.size();
    }

    /**
     * Every loaded credit across all locales, sorted by locale then name — a snapshot copy,
     * safe to iterate off-lock. Empty when no localization resource pack is active. Surfaced
     * for the Credits screen, which lists translators for every language rather than just the
     * player's currently-selected one (unlike {@link #creditsFor}).
     */
    public static synchronized List<LocalizationCredit> allCredits() {
        List<LocalizationCredit> out = new ArrayList<>(CREDITS.values());
        out.sort(Comparator.comparing(LocalizationCredit::locale).thenComparing(LocalizationCredit::name));
        return out;
    }

    /** Every credit for {@code localeCode} (e.g. {@code "es_es"}), sorted by name. Empty if none. */
    public static synchronized List<LocalizationCredit> creditsFor(String localeCode) {
        if (localeCode == null || localeCode.isEmpty()) {
            return List.of();
        }
        List<LocalizationCredit> out = new ArrayList<>();
        for (LocalizationCredit credit : CREDITS.values()) {
            if (credit.locale().equalsIgnoreCase(localeCode)) {
                out.add(credit);
            }
        }
        out.sort(Comparator.comparing(LocalizationCredit::name));
        return out;
    }

    /** Which generated count feeds {@link #aiFraction} — a one-line switch. */
    private enum Metric { AI_UNREVIEWED, AI_AUTHORED }

    private static final Metric RING_METRIC = Metric.AI_UNREVIEWED;

    /**
     * The locale's AI-translation fraction per {@link #RING_METRIC}, in {@code [0, 1]} — empty
     * when no loaded credit for that locale carries the generated count fields (older packs,
     * hand-made overrides). Drives the blue AI-fraction ring around the Dungeon Train logo in
     * the language-selection list.
     *
     * <p>When several credit files match the locale (one per contributor is legal), the counts
     * with the greatest {@code totalKeys} win, tie-broken by the greatest numerator — so the most
     * complete data survives a stale third-party file coexisting with the shipped one.</p>
     */
    public static synchronized OptionalDouble aiFraction(String localeCode) {
        if (localeCode == null || localeCode.isEmpty()) {
            return OptionalDouble.empty();
        }
        LocalizationCredit.AiCounts best = null;
        for (LocalizationCredit credit : CREDITS.values()) {
            if (!credit.locale().equalsIgnoreCase(localeCode) || credit.aiCounts().isEmpty()) {
                continue;
            }
            LocalizationCredit.AiCounts counts = credit.aiCounts().get();
            if (best == null
                || counts.totalKeys() > best.totalKeys()
                || (counts.totalKeys() == best.totalKeys()
                    && numerator(counts) > numerator(best))) {
                best = counts;
            }
        }
        if (best == null) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(RING_METRIC == Metric.AI_UNREVIEWED
            ? best.unreviewedFraction() : best.authoredFraction());
    }

    private static int numerator(LocalizationCredit.AiCounts counts) {
        return RING_METRIC == Metric.AI_UNREVIEWED ? counts.aiUnreviewed() : counts.aiAuthored();
    }

    /**
     * Whether {@code localeCode}'s translation is human-reviewed — {@code true} if any loaded
     * credit for that locale carries {@code "human_reviewed": true}. Used to render the language's
     * Dungeon Train logo at full (vs faded) opacity in the language-selection list.
     */
    public static synchronized boolean isHumanReviewed(String localeCode) {
        if (localeCode == null || localeCode.isEmpty()) {
            return false;
        }
        for (LocalizationCredit credit : CREDITS.values()) {
            if (credit.humanReviewed() && credit.locale().equalsIgnoreCase(localeCode)) {
                return true;
            }
        }
        return false;
    }

    private static LocalizationCredit parse(InputStream in, ResourceLocation id) throws ParseException {
        JsonElement rootEl;
        try {
            rootEl = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new ParseException("invalid JSON: " + e.getMessage(), e);
        }
        if (!rootEl.isJsonObject()) {
            throw new ParseException("root is not a JSON object");
        }
        JsonObject root = rootEl.getAsJsonObject();

        String locale = requiredString(root, "locale");
        String name = requiredString(root, "name");
        Optional<String> url = optionalString(root, "url");
        boolean humanReviewed = optionalBoolean(root, "human_reviewed");
        Optional<LocalizationCredit.AiCounts> aiCounts = optionalAiCounts(root);

        return new LocalizationCredit(id, locale, name, url, humanReviewed, aiCounts);
    }

    /**
     * The generated AI-count triple, or empty unless all three fields are present as consistent
     * non-negative integers ({@code ai_unreviewed <= ai_authored <= total_keys}, positive total).
     * Never fails the file — a credit without (valid) counts simply renders no ring, keeping old
     * packs and hand-made overrides loading exactly as before.
     */
    private static Optional<LocalizationCredit.AiCounts> optionalAiCounts(JsonObject obj) {
        Integer total = optionalInt(obj, "total_keys");
        Integer authored = optionalInt(obj, "ai_authored");
        Integer unreviewed = optionalInt(obj, "ai_unreviewed");
        if (total == null || authored == null || unreviewed == null) {
            return Optional.empty();
        }
        if (total <= 0 || unreviewed < 0 || unreviewed > authored || authored > total) {
            return Optional.empty();
        }
        return Optional.of(new LocalizationCredit.AiCounts(total, authored, unreviewed));
    }

    /** Integral number field, or {@code null} when absent, non-numeric, or not a whole number. */
    private static Integer optionalInt(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive() || !obj.get(key).getAsJsonPrimitive().isNumber()) {
            return null;
        }
        double v = obj.get(key).getAsDouble();
        if (v != Math.floor(v) || Double.isInfinite(v) || v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            return null; // reject 3.5, NaN handled by the != check, and out-of-range values
        }
        return (int) v;
    }

    private static String requiredString(JsonObject obj, String key) throws ParseException {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive() || !obj.get(key).getAsJsonPrimitive().isString()) {
            throw new ParseException("missing or non-string '" + key + "' field");
        }
        String v = obj.get(key).getAsString();
        if (v.isEmpty()) {
            throw new ParseException("'" + key + "' is empty");
        }
        return v;
    }

    private static Optional<String> optionalString(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive() || !obj.get(key).getAsJsonPrimitive().isString()) {
            return Optional.empty();
        }
        String v = obj.get(key).getAsString();
        return v.isEmpty() ? Optional.empty() : Optional.of(v);
    }

    /** Optional boolean field; {@code false} when absent or not a boolean primitive. */
    private static boolean optionalBoolean(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive() || !obj.get(key).getAsJsonPrimitive().isBoolean()) {
            return false;
        }
        return obj.get(key).getAsBoolean();
    }

    /** Strip the trailing {@code .json} from a resource location, keeping namespace + path. */
    private static ResourceLocation stripJson(ResourceLocation file) {
        String path = file.getPath();
        return ResourceLocation.fromNamespaceAndPath(
            file.getNamespace(), path.substring(0, path.length() - JSON_EXT.length()));
    }

    /** Surfaced to {@link #load}'s per-file try/catch so logging is uniform. */
    private static final class ParseException extends Exception {
        ParseException(String message) { super(message); }
        ParseException(String message, Throwable cause) { super(message, cause); }
    }
}
