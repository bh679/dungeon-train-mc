package games.brennan.dungeontrain.client.localization;

import java.util.List;
import java.util.Optional;

/**
 * One human translator credited on the Credits page, loaded from the generated,
 * shipped {@code assets/dungeontrain/translation_contributors.json} (see
 * {@link TranslationContributorsRegistry}). Grouped by person: a contributor who
 * worked on several languages appears once, with one {@link LanguageShare} per
 * language.
 *
 * <p>The whole file is generated at build time from the repo-side provenance
 * sidecars + {@code localization/authors.json} by
 * {@code scripts/localization/stamp-provenance.py} and validated by
 * {@code check-provenance.py} — nothing here is hand-authored. {@code url} is the
 * optional profile link carried in {@code authors.json}; when present the name is
 * a clickable link on the page.</p>
 */
public record TranslationContributor(String name, Optional<String> url, List<LanguageShare> languages) {

    public TranslationContributor {
        url = url == null ? Optional.empty() : url;
        languages = languages == null ? List.of() : List.copyOf(languages);
    }

    /**
     * How much of one language this contributor authored or reviewed: {@code contributed}
     * of the locale's {@code total} keys. The invariant {@code 0 < contributed <= total}
     * (total &gt; 0) is guaranteed by the generator, so {@link #fraction()} is always in
     * {@code (0, 1]}.
     */
    public record LanguageShare(String locale, int contributed, int total) {

        public double fraction() {
            return total > 0 ? (double) contributed / total : 0.0;
        }
    }
}
