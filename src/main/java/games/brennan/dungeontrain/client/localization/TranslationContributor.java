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

    /**
     * The name of the one line that thanks everybody who asked not to be named — a translator who
     * removed themself from the credits keeps their count under it. Never in the generated file.
     */
    public static final String ANONYMOUS = "";

    public TranslationContributor {
        url = url == null ? Optional.empty() : url;
        languages = languages == null ? List.of() : List.copyOf(languages);
    }

    /** Whether this is the {@link #ANONYMOUS} line. */
    public boolean isAnonymous() {
        return ANONYMOUS.equals(name);
    }

    /**
     * How much of one language this contributor authored or reviewed: {@code contributed}
     * lines against the locale's {@code total} player-facing lines (editor lines left out, see
     * {@code counted_keys}). {@code contributed} still counts the editor lines they did, so
     * {@link #fraction()} can pass 1.
     */
    public record LanguageShare(String locale, int contributed, int total) {

        public double fraction() {
            return total > 0 ? (double) contributed / total : 0.0;
        }

        /**
         * The share as a whole percent: at least 1, so a small-but-real contribution never reads
         * as "0%", and deliberately NOT capped at 100. The denominator is only the lines a player
         * sees, while the numerator is everything the translator did — editor lines, and for a
         * relay credit books and narrative units too — so one who has done more than the game's
         * own text reads over 100%.
         */
        public int percent() {
            return Math.max(1, (int) Math.round(fraction() * 100));
        }
    }
}
