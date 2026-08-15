package games.brennan.dungeontrain.client.localization.edit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One parsed {@code /translations/pool} response, split into the two things the mod does with it.
 *
 * <p>{@code applicable} is what the running game may install — the subset this build is entitled to
 * by {@link PoolVersionGate}. {@code all} is every approved translation for the locale regardless of
 * build, which is what the EDITOR reads: a translator is deciding what still needs work, and a
 * string somebody has already fixed and had approved does not, whichever build they were on.</p>
 *
 * <p>Both are persisted, because both have to survive an offline launch: the overlay needs its set
 * to keep the player's language intact, and the editor needs its set to keep the queue honest.</p>
 *
 * @param all          every approved override for the locale
 * @param applicable   the subset this build may apply
 * @param contributors the translators who asked to be credited, first-approved order
 */
public record ApprovedPool(TranslationEdits all, TranslationEdits applicable,
                           List<String> contributors) {

    public ApprovedPool {
        contributors = contributors == null ? List.of() : List.copyOf(contributors);
    }

    public static ApprovedPool empty(String locale) {
        return new ApprovedPool(TranslationEdits.empty(locale), TranslationEdits.empty(locale),
            List.of());
    }

    /**
     * Split {@code all} by what {@code clientVersion} may apply, given each unit's authoring build.
     * A unit with no entry in the version maps is unknown, which {@link PoolVersionGate} lets
     * through — that is how an older relay, which sends no versions at all, keeps working.
     */
    public static ApprovedPool gate(TranslationEdits all, Map<String, String> langVersions,
                                    Map<String, String> bookVersions, String clientVersion,
                                    List<String> contributors) {
        return new ApprovedPool(all,
            new TranslationEdits(all.locale(),
                allowed(all.lang(), langVersions, clientVersion),
                allowed(all.books(), bookVersions, clientVersion)),
            contributors);
    }

    private static Map<String, String> allowed(Map<String, String> body, Map<String, String> versions,
                                               String clientVersion) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : body.entrySet()) {
            String authoredIn = versions == null ? null : versions.get(entry.getKey());
            if (PoolVersionGate.applies(authoredIn, clientVersion)) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }
}
