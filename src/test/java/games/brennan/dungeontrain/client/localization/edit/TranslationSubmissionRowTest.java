package games.brennan.dungeontrain.client.localization.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three states a row in the "sent in" column can be in, and what each one says about itself.
 * The working batch is the one that has never left the machine, and it must not read like work
 * somebody else is now sitting on.
 */
class TranslationSubmissionRowTest {

    @Test
    @DisplayName("the working batch carries no date — it has not happened yet")
    void unsubmittedHasNoDate() {
        // A zero timestamp formatted as a date would read 1970-01-01, which is not just wrong but
        // wrong in a way that looks like real data.
        assertEquals("", TranslationSubmission.unsubmitted("de_de", 3).date());
    }

    @Test
    @DisplayName("a sent submission still shows its date")
    void sentKeepsItsDate() {
        TranslationSubmission sent = new TranslationSubmission(
            1_760_000_000_000L, "de_de", "Klara", 3, 1, 2, 0, 0, false, false);
        assertFalse(sent.date().isEmpty());
    }

    @Test
    @DisplayName("nobody is credited for work that has not been submitted")
    void unsubmittedCreditsNobody() {
        // Who gets the credit is chosen at Submit, so the row must not pre-empt it — and must not
        // fall through to the "Anonymous" placeholder, which would state the opposite of unknown.
        assertEquals("", TranslationSubmission.unsubmitted("de_de", 3).creditLine().getString());
    }

    @Test
    @DisplayName("the working batch says one thing, whatever it is carrying")
    void oneWordWhateverTheCount() {
        // There is no empty state to word: the screen leaves the row out when nothing is unsent
        // (TranslationScreen#onHistory), so every row that exists has real work in it.
        assertEquals(TranslationSubmission.unsubmitted("de_de", 1).statusLine(),
            TranslationSubmission.unsubmitted("de_de", 40).statusLine());
        assertEquals(TranslationSubmission.unsubmitted("de_de", 1).statusColour(),
            TranslationSubmission.unsubmitted("de_de", 40).statusColour());
    }

    @Test
    @DisplayName("the working batch is not coloured like work under review")
    void unsubmittedIsNotAmber() {
        // Amber is "waiting on a reviewer". Nothing here is waiting on anyone but the translator.
        TranslationSubmission waiting = new TranslationSubmission(
            1L, "de_de", "Klara", 3, 0, 3, 0, 0, false, false);
        assertFalse(TranslationSubmission.unsubmitted("de_de", 2).statusColour()
            .equals(waiting.statusColour()));
    }

    @Test
    @DisplayName("queued and unsubmitted are exclusive — a row cannot claim both")
    void queuedAndUnsubmittedAreExclusive() {
        assertThrows(IllegalArgumentException.class, () -> new TranslationSubmission(
            1L, "de_de", "Klara", 1, 0, 0, 0, 0, true, true));
        assertTrue(TranslationSubmission.queued(1L, "de_de", "Klara", 1).queued());
        assertTrue(TranslationSubmission.unsubmitted("de_de", 1).unsubmitted());
        assertFalse(TranslationSubmission.unsubmitted("de_de", 1).queued());
    }
}
