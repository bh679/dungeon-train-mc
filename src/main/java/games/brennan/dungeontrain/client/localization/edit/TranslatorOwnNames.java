package games.brennan.dungeontrain.client.localization.edit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The credited names this player has submitted translations under — what decides which lines on
 * the Credits page get an Edit button.
 *
 * <p>No endpoint of its own: {@code /translations/mine} already reports the translator on every
 * one of the player's submissions, and the set of those names is exactly the set the relay will
 * let this uuid rename (see the relay's {@code translations.rename}). A name that only the jar
 * knows — a translator who delivered a zip rather than using the in-game editor — is not in it,
 * and correctly so: there are no relay rows to rename.</p>
 *
 * <p>Consent-gated by way of the call it wraps: with the connection off it reports no names, so
 * the page simply shows no Edit buttons.</p>
 */
public final class TranslatorOwnNames {

    private TranslatorOwnNames() {}

    /** Fetch and hand the names to {@code onResult} on the render thread. Never fails. */
    public static void fetch(Consumer<Set<String>> onResult) {
        TranslationSubmissionsClient.fetch(history -> onResult.accept(namesOf(history)));
    }

    /** The distinct non-blank credited names across {@code history}, in first-seen order. */
    static Set<String> namesOf(List<TranslationSubmission> history) {
        Set<String> names = new LinkedHashSet<>();
        for (TranslationSubmission submission : history == null ? List.<TranslationSubmission>of() : history) {
            if (submission == null || submission.queued() || submission.unsubmitted()) {
                continue; // not on the relay yet, so not renameable there
            }
            String name = submission.translator() == null ? "" : submission.translator().trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return Set.copyOf(names);
    }
}
