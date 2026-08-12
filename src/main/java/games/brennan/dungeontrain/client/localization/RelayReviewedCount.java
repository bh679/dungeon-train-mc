package games.brennan.dungeontrain.client.localization;

import games.brennan.dungeontrain.client.localization.edit.ProvenanceManifestRegistry;
import games.brennan.dungeontrain.client.localization.edit.TranslationEdits;
import games.brennan.dungeontrain.client.localization.edit.TranslationOverrides;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How many of a locale's machine-translated, unreviewed lines an approved relay translation has
 * since covered — the correction {@link LocalizationCreditRegistry} applies to the build-time AI
 * counts behind the language list's ring and full-vs-faded logo.
 *
 * <p>Counted against provenance rather than trusted from the relay: an approval for a key the
 * manifest never marked AI-unreviewed (a human-translated line someone improved, a key from a
 * third-party pack) has not reduced how much machine translation the player is reading, and must
 * not shrink the ring.</p>
 *
 * <p>Cached per locale because the language list asks once per visible row per frame. Deliberately
 * cheap by construction — set lookups against the already-loaded manifest, never a catalog build,
 * which is what makes it safe to ask that often. {@link #invalidate()} on anything that changes the
 * approved layers or the manifests.</p>
 */
public final class RelayReviewedCount {

    private static final Map<String, Integer> CACHE = new HashMap<>();

    private RelayReviewedCount() {}

    /** Drop the cache — after a resource reload, or after a pool fetch changes an approved layer. */
    public static synchronized void invalidate() {
        CACHE.clear();
    }

    /** Applied approved overrides for {@code locale} that provenance still counts as unreviewed. */
    public static synchronized int forLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return 0;
        }
        String code = locale.toLowerCase(Locale.ROOT);
        Integer hit = CACHE.get(code);
        if (hit != null) {
            return hit;
        }
        int count = compute(code);
        CACHE.put(code, count);
        return count;
    }

    private static int compute(String code) {
        TranslationEdits applied = TranslationOverrides.appliedApprovedFor(code);
        if (applied.isEmpty() || !ProvenanceManifestRegistry.hasData(code)) {
            return 0;
        }
        // Lang keys only. The baked totals come from stamp-provenance's lang-key count, and book
        // prose is flagged per BOOK while overrides are per FIELD — subtracting five approved fields
        // of one book from a lang-key denominator would shrink the ring by more than the player
        // gained. Books wanting their own credit signal is a separate job with its own denominator.
        int count = 0;
        for (String key : applied.lang().keySet()) {
            if (ProvenanceManifestRegistry.isAiUnreviewedLangAnyNamespace(code, key)) {
                count++;
            }
        }
        return count;
    }
}
