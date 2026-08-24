package games.brennan.dungeontrain.client.localization;

import games.brennan.dungeontrain.client.localization.edit.TranslationCoverageClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * The credits screen's translator list: everybody the jar knows about, plus everybody the relay has
 * approved since the jar was built.
 *
 * <p>{@code translation_contributors.json} is generated at build time and so can only name people
 * whose work had been released. A volunteer approved yesterday is being read by every player in
 * their language today and would go unnamed until the next release — the relay has always carried
 * their name, but only for the one locale a client fetched a pool for, so a page listing every
 * language could never show them.</p>
 *
 * <p>Merged rather than appended as a second list. Somebody who appears in both is one person, and
 * a credits page that thanked them twice — once with a percentage and once without — would read as
 * a bug rather than as generosity. The baked share WINS on any overlap: it was computed against the
 * provenance sidecars, which know who authored a line as well as who reviewed it, where the relay
 * can only count approvals.</p>
 *
 * <p>Pure, so the merge can be reasoned about — and tested — without a relay or a ResourceManager.
 * </p>
 */
public final class TranslationCreditsMerge {

    private TranslationCreditsMerge() {}

    /** The merged list for the running client. */
    public static List<TranslationContributor> merged() {
        return merge(TranslationContributorsRegistry.all(),
            TranslationCoverageClient.allCredits(),
            TranslationCreditsMerge::totalKeysFor);
    }

    /**
     * @param baked        the build-time list, authoritative where it overlaps
     * @param relay        locale to the relay's credited names and their approval counts
     * @param totalForLocale a locale's total key count, for the percentage; 0 when unknown
     */
    public static List<TranslationContributor> merge(List<TranslationContributor> baked,
                                                     Map<String, List<TranslationCoverageClient.Credit>> relay,
                                                     ToIntFunction<String> totalForLocale) {
        // Insertion-ordered: the baked list's order is the one the screen has always shown, and
        // people who appear only through the relay join the end rather than reshuffling it.
        Map<String, TranslationContributor> byName = new LinkedHashMap<>();
        for (TranslationContributor person : baked) {
            byName.put(person.name(), person);
        }
        if (relay == null) {
            return List.copyOf(byName.values());
        }

        for (Map.Entry<String, List<TranslationCoverageClient.Credit>> entry : relay.entrySet()) {
            String locale = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            if (locale.isEmpty() || entry.getValue() == null) {
                continue;
            }
            int total = Math.max(0, totalForLocale.applyAsInt(locale));
            for (TranslationCoverageClient.Credit credit : entry.getValue()) {
                if (credit == null || credit.name() == null || credit.name().isBlank()) {
                    continue;
                }
                byName.compute(credit.name(),
                    (name, existing) -> withShare(name, existing, locale, credit.units(), total));
            }
        }
        return List.copyOf(byName.values());
    }

    /**
     * {@code person} with {@code locale} added, or left alone when they are already credited for it.
     *
     * <p>Already-credited wins because the baked entry counts authorship, not just approvals —
     * overwriting it with the relay's smaller number would shrink a translator's contribution every
     * time their language was released.</p>
     */
    private static TranslationContributor withShare(String name, TranslationContributor person,
                                                    String locale, int units, int total) {
        if (person == null) {
            return new TranslationContributor(name, Optional.empty(),
                List.of(new TranslationContributor.LanguageShare(locale, units, total)));
        }
        for (TranslationContributor.LanguageShare share : person.languages()) {
            if (share.locale().equalsIgnoreCase(locale)) {
                return person;
            }
        }
        List<TranslationContributor.LanguageShare> languages = new ArrayList<>(person.languages());
        languages.add(new TranslationContributor.LanguageShare(locale, units, total));
        return new TranslationContributor(name, person.url(), List.copyOf(languages));
    }

    /**
     * The denominator for a relay-only share. Absent counts give 0, which
     * {@code LanguageShare#fraction} must therefore tolerate — a name with no percentage is still
     * a name worth showing.
     */
    private static int totalKeysFor(String locale) {
        return LocalizationCreditRegistry.totalKeysFor(locale);
    }
}
