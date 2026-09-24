package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.SubmitNote;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which answers the Submitted answers page shows, and whether it is offered at all. */
class SubmissionPageTest {

    @Test
    void answeredQuestionsInAskedOrder() {
        List<SubmissionPage.Section> s = SubmissionPage.sections(new SubmitNote(" lever first ", "", "near the engine"));
        assertEquals(2, s.size());
        assertEquals("gui.dungeontrain.builder.profile.note.redstone.label", s.get(0).labelKey());
        assertEquals("lever first", s.get(0).text());
        assertEquals("gui.dungeontrain.builder.profile.note.notes.label", s.get(1).labelKey());
    }

    @Test
    void blankAnswersAreNoPage() {
        assertFalse(SubmissionPage.hasAnswers(SubmitNote.EMPTY));
        assertFalse(SubmissionPage.hasAnswers(new SubmitNote("  ", "\n", "")));
        assertFalse(SubmissionPage.hasAnswers(null));
        assertTrue(SubmissionPage.hasAnswers(SubmitNote.of("x")));
    }
}
