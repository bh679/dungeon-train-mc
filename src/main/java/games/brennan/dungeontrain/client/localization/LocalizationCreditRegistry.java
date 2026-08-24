package games.brennan.dungeontrain.client.localization;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.localization.edit.LocalizationCoverage;
import games.brennan.dungeontrain.client.localization.edit.RelayTranslationCredits;
import games.brennan.dungeontrain.client.localization.edit.TranslationCoverageClient;
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
     * Every credit for {@code localeCode} (e.g. {@code "es_es"}), sorted by name. Empty if none.
     *
     * <p>Includes the in-game translators whose approved work this client is applying, on top of the
     * ones baked into the build. Their fix is being read by everyone in the language today; making
     * them wait for the next release to be named would be crediting the release process rather than
     * the person.</p>
     */
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
        appendRelayContributors(out, localeCode);
        out.sort(Comparator.comparing(LocalizationCredit::name));
        return out;
    }

    /**
     * Add a credit per relay contributor not already named by a baked one.
     *
     * <p>Never {@code humanReviewed}: being credited is not a claim that the whole locale has been
     * proofread — that stays the coverage maths in {@link #isHumanReviewed}, which these approvals
     * feed through {@link #withRelayApprovals} rather than short-circuiting.</p>
     */
    private static void appendRelayContributors(List<LocalizationCredit> out, String localeCode) {
        List<String> names = RelayTranslationCredits.forLocale(localeCode);
        if (names.isEmpty()) {
            return;
        }
        for (String name : names) {
            boolean already = out.stream().anyMatch((c) -> c.name().equalsIgnoreCase(name));
            if (already) {
                continue;
            }
            out.add(new LocalizationCredit(
                ResourceLocation.fromNamespaceAndPath("dungeontrain", "relay/" + slug(name)),
                localeCode, name, Optional.empty(), false, Optional.empty()));
        }
    }

    /** A ResourceLocation-safe id for a translator name that may be in any script at all. */
    private static String slug(String name) {
        String cleaned = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "");
        return cleaned.isEmpty() ? Integer.toHexString(name.hashCode()) : cleaned;
    }

    /** Which generated count feeds {@link #aiFraction} — a one-line switch. */
    private enum Metric { AI_UNREVIEWED, AI_AUTHORED }

    private static final Metric RING_METRIC = Metric.AI_UNREVIEWED;

    /**
     * How much of a locale may still be AI-authored-and-unreviewed while the locale still counts
     * as human-reviewed. A translator's pass lands as one wave; English keys added afterwards sit
     * unreviewed until the next wave, so demanding 100% would flag a thoroughly reviewed locale as
     * machine-translated over a handful of new lines.
     */
    private static final double REVIEWED_UNREVIEWED_MAX = 0.10;

    /**
     * The locale's AI-translation fraction per {@link #RING_METRIC}, in {@code [0, 1]} — empty
     * when no loaded credit for that locale carries the generated count fields (older packs,
     * hand-made overrides). Drives the blue AI-fraction ring around the Dungeon Train logo in
     * the language-selection list.
     */
    public static synchronized OptionalDouble aiFraction(String localeCode) {
        LocalizationCredit.AiCounts best = withRelayApprovals(localeCode, bestCounts(localeCode));
        if (best == null) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(RING_METRIC == Metric.AI_UNREVIEWED
            ? best.unreviewedFraction() : best.authoredFraction());
    }

    /**
     * The baked counts brought up to date with the approved translations this client has since
     * downloaded and is applying.
     *
     * <p>{@code localization_credits/*.json} is stamped when the build is cut, so it describes a
     * language as it was then. Every approval released since has put a human on a line that file
     * still counts as machine-translated and unread — leaving the ring at its build-time size would
     * be telling the player their language is less reviewed than the text in front of them actually
     * is, and would keep a locale that volunteers have since finished off from ever earning the full
     * logo without a release.</p>
     *
     * <p>Counts the APPLIED layer, not every approval known: a translation withheld from this build
     * by {@link games.brennan.dungeontrain.client.localization.edit.PoolVersionGate} is not what the
     * player is reading, so it cannot make their reading less machine-generated.</p>
     */
    static LocalizationCredit.AiCounts withRelayApprovals(String localeCode,
                                                          LocalizationCredit.AiCounts baked) {
        // The locally verified count where there is one, the relay's per-locale total otherwise.
        // Only one of these is ever non-zero in practice: the pool is fetched for the language
        // being played, and the startup coverage call answers for all the others. Taking the
        // greater keeps the verified number winning if they ever overlap.
        int verified = RelayReviewedCount.forLocale(localeCode);
        int reported = TranslationCoverageClient.approvedFor(localeCode);
        return adjust(baked, Math.max(verified, reported));
    }

    /**
     * The arithmetic half of {@link #withRelayApprovals}, split out so it is testable without a
     * {@code ResourceManager} or a relay — the same reason {@link #meetsReviewedCoverage} is split.
     *
     * <p>{@code reviewed} comes off BOTH counts: an approved override is not machine translation a
     * human merely looked at, it is a human's own words in place of it, so the line is no longer
     * AI-authored either. The denominator never moves — the language still has as many lines as it
     * had.</p>
     */
    static LocalizationCredit.AiCounts adjust(LocalizationCredit.AiCounts baked, int reviewed) {
        if (baked == null || reviewed <= 0) {
            return baked;
        }
        // Never below zero: a locale can carry more approvals than the manifest ever flagged (a key
        // the stamp never saw, a pack of its own), and a negative would invert the ring.
        int unreviewed = Math.max(0, baked.aiUnreviewed() - reviewed);
        int authored = Math.max(unreviewed, baked.aiAuthored() - reviewed);
        return new LocalizationCredit.AiCounts(baked.totalKeys(), authored, unreviewed);
    }

    /**
     * The most authoritative generated counts for {@code localeCode}, or {@code null} if no loaded
     * credit for it carries them.
     *
     * <p>When several credit files match the locale (one per contributor is legal), the counts
     * with the greatest {@code totalKeys} win, tie-broken by the greatest numerator — so the most
     * complete data survives a stale third-party file coexisting with the shipped one.</p>
     */
    private static LocalizationCredit.AiCounts bestCounts(String localeCode) {
        if (localeCode == null || localeCode.isEmpty()) {
            return null;
        }
        LocalizationCredit.AiCounts best = null;
        // Falls through to counting the loaded lang files when no credit file names this locale --
        // which is every language but the nineteen the mod ships a stamped one for.
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
        return best != null ? best : LocalizationCoverage.forLocale(localeCode);
    }

    private static int numerator(LocalizationCredit.AiCounts counts) {
        return RING_METRIC == Metric.AI_UNREVIEWED ? counts.aiUnreviewed() : counts.aiAuthored();
    }

    /**
     * Whether {@code localeCode}'s translation is human-reviewed. Used to render the language's
     * Dungeon Train logo at full (vs faded) opacity in the language-selection list.
     *
     * <p>True on either of two independent signals: any loaded credit for the locale asserts
     * {@code "human_reviewed": true}, or the generated counts show at most
     * {@link #REVIEWED_UNREVIEWED_MAX} of its lines still AI-authored with no reviewer. The
     * derived route means a locale a translator has actually worked through reads as reviewed
     * without anyone remembering to flip a hand-maintained flag; the explicit flag stays
     * authoritative on its own for packs that ship no counts.</p>
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
        return meetsReviewedCoverage(withRelayApprovals(localeCode, bestCounts(localeCode)));
    }

    /**
     * Whether {@code counts} clear {@link #REVIEWED_UNREVIEWED_MAX} — the coverage half of
     * {@link #isHumanReviewed}, split out so the threshold is testable without a ResourceManager.
     * Absent counts never qualify: no data is not evidence of review.
     */
    static boolean meetsReviewedCoverage(LocalizationCredit.AiCounts counts) {
        return counts != null && counts.unreviewedFraction() <= REVIEWED_UNREVIEWED_MAX;
    }

    /**
     * Whether a person has been through <b>any</b> of this locale, at any depth.
     *
     * <p>A deliberately different question from {@link #isHumanReviewed}, which asks whether the
     * language as a whole can be called reviewed and holds out for 90% coverage. That threshold is
     * right for the badge on a row — it is a claim about what the player is about to read — and
     * wrong for a filter, where it currently matches <em>nothing</em>: the most worked-on language
     * in the mod is 18% unreviewed, so "Human reviewed" would be an option that never returned a
     * language. This asks the answerable question instead, and the two are allowed to disagree.</p>
     */
    public static synchronized boolean hasAnyHumanReview(String localeCode) {
        if (localeCode == null || localeCode.isEmpty()) {
            return false;
        }
        for (LocalizationCredit credit : CREDITS.values()) {
            if (credit.humanReviewed() && credit.locale().equalsIgnoreCase(localeCode)) {
                return true;
            }
        }
        return hasAnyReview(withRelayApprovals(localeCode, bestCounts(localeCode)));
    }

    /**
     * How many lines this locale has in total, or 0 when nothing is known about it — the
     * denominator behind a translator's percentage on the credits screen.
     */
    public static synchronized int totalKeysFor(String localeCode) {
        LocalizationCredit.AiCounts counts = bestCounts(localeCode);
        return counts == null ? 0 : counts.totalKeys();
    }

    /**
     * Whether any of this locale still wants a human — machine translation nobody has read yet.
     *
     * <p>The counterpart to {@link #hasAnyHumanReview}, and deliberately not its negation: a
     * language can be both. zh_cn has had a translator through most of it AND has 232 lines still
     * untouched; zh_tw has fourteen reviewed lines and 1265 that are not. Asking one question and
     * inverting it put both of them under "human reviewed" only, which hid the largest block of
     * outstanding work in the mod from the filter built to find outstanding work.</p>
     */
    public static synchronized boolean hasUnreviewedAi(String localeCode) {
        if (localeCode == null || localeCode.isEmpty()) {
            return false;
        }
        return hasUnreviewed(withRelayApprovals(localeCode, bestCounts(localeCode)));
    }

    /**
     * The counts half of {@link #hasUnreviewedAi}, pure so the arithmetic is testable.
     *
     * <p>Absent counts read as "still wants a human", the opposite of {@link #hasAnyReview}'s
     * reading of the same absence. Both fail towards MORE work being visible: no data is not
     * evidence that a language has been reviewed, and a translated language that fell out of every
     * filter would be a language nobody could find their way to.</p>
     */
    static boolean hasUnreviewed(LocalizationCredit.AiCounts counts) {
        return counts == null || counts.aiUnreviewed() > 0;
    }

    /**
     * The counts half of {@link #hasAnyHumanReview}, pure so the arithmetic is testable.
     *
     * <p>A key has had a human on it when it is not AI-authored-and-unreviewed — which covers both
     * routes to that: written by a person in the first place, or machine-written and since
     * corrected. That is {@code total - aiUnreviewed}, and one is enough.</p>
     */
    static boolean hasAnyReview(LocalizationCredit.AiCounts counts) {
        return counts != null && counts.totalKeys() > 0
            && counts.totalKeys() - counts.aiUnreviewed() > 0;
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
