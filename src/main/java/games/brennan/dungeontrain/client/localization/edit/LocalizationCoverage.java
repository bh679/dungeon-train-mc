package games.brennan.dungeontrain.client.localization.edit;

import games.brennan.dungeontrain.client.localization.LocalizationCredit;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A locale's AI-coverage counts worked out from what this client has actually loaded, for the
 * languages the build-time credit files say nothing about.
 *
 * <p>{@code localization_credits/*.json} is stamped when the jar is cut and exists for the
 * nineteen languages the mod ships. Every other language the player can pick -- and any language a
 * localization resource pack adds -- has no counts at all, so the ring and the logo have nothing to
 * draw and the language reads as though the question had never been asked. The client is holding
 * the lang files and the provenance manifests either way; this is the same arithmetic the stamp
 * script does, done against what is in front of the player rather than against what was in the
 * repo.</p>
 *
 * <p>Agrees with the baked figures where both exist -- zh_cn's manifest names its 232 unreviewed
 * keys and this counts 232 of them -- which is what makes it safe as a fallback rather than a
 * second, quietly different answer.</p>
 *
 * <p>Returns null for a locale carrying no strings of its own. That is not a language at 0%: it is
 * a language nothing is known about, and calling it 0% AI-unreviewed would hand it the FULL logo
 * of a thoroughly human-reviewed translation.</p>
 */
public final class LocalizationCoverage {

    /** Locale to counts, or to an absent marker. The ring asks once per visible row per frame. */
    private static final Map<String, LocalizationCredit.AiCounts> CACHE = new HashMap<>();
    private static final LocalizationCredit.AiCounts NONE =
        new LocalizationCredit.AiCounts(0, 0, 0);

    private LocalizationCoverage() {}

    /** Drop the cache -- on resource reload, when the loaded lang files may have changed. */
    public static synchronized void invalidate() {
        CACHE.clear();
    }

    /**
     * Counts for {@code locale} from the loaded lang files, or null when it carries no strings.
     */
    public static synchronized LocalizationCredit.AiCounts forLocale(String locale) {
        if (locale == null || locale.isBlank() || "en_us".equalsIgnoreCase(locale)) {
            return null;
        }
        String code = locale.toLowerCase(Locale.ROOT);
        LocalizationCredit.AiCounts hit = CACHE.get(code);
        if (hit == null) {
            hit = compute(code);
            CACHE.put(code, hit);
        }
        return hit == NONE ? null : hit;
    }

    private static LocalizationCredit.AiCounts compute(String locale) {
        int total = 0;
        int unreviewed = 0;
        int own = 0;
        for (String namespace : TranslationCatalog.NAMESPACES) {
            Map<String, String> english = TranslationCatalog.readLang(namespace, "en_us");
            Map<String, String> translated = TranslationCatalog.readLang(namespace, locale);
            if (english.isEmpty() && translated.isEmpty()) {
                continue; // this namespace is not installed
            }
            own += translated.size();
            // The union, the same denominator TranslationCatalog lists against: a key the locale
            // carries and English does not is still a line the player reads.
            Set<String> keys = new LinkedHashSet<>(english.keySet());
            keys.addAll(translated.keySet());
            total += keys.size();
            for (String key : keys) {
                if (ProvenanceManifestRegistry.isAiUnreviewedLang(locale, namespace, key)) {
                    unreviewed++;
                }
            }
        }
        if (own == 0 || total == 0) {
            return NONE;
        }
        // aiAuthored cannot be told apart from aiUnreviewed here: the shipped manifest records
        // which lines still want a human, not who wrote them -- authorship lives in the repo-side
        // sidecars, which are not in the jar. Reporting the floor is honest, and the ring reads
        // aiUnreviewed anyway (LocalizationCreditRegistry.RING_METRIC).
        return new LocalizationCredit.AiCounts(total, unreviewed, unreviewed);
    }
}
