package games.brennan.dungeontrain.client.localization.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which credited names are the player's own — pure, no relay. */
class TranslatorOwnNamesTest {

    @Test
    @DisplayName("own names are the distinct credited names on submissions the relay has")
    void ownNames() {
        List<TranslationSubmission> history = List.of(
            new TranslationSubmission(3L, "de_de", " Ada ", 2, 1, 1, 0, 0, false, false),
            new TranslationSubmission(2L, "fr_fr", "Ada", 1, 0, 1, 0, 0, false, false),
            new TranslationSubmission(1L, "de_de", "", 1, 0, 0, 0, 1, false, false),
            new TranslationSubmission(4L, "de_de", "Bea", 1, 0, 0, 0, 0, false, false),
            TranslationSubmission.queued(5L, "de_de", "Queued Only", 1),
            TranslationSubmission.unsubmitted("de_de", 0));
        assertEquals(Set.of("Ada", "Bea"), TranslatorOwnNames.namesOf(history));
        assertEquals(Set.of(), TranslatorOwnNames.namesOf(null));
    }
}
