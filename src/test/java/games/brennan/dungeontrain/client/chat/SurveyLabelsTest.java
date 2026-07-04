package games.brennan.dungeontrain.client.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Survey prompts → compact panel labels, read from the bundled {@code dp_surveys/*.json} definitions
 * (so the mapping can't drift from what the survey actually asks). Unknown prompts stay unlabeled.
 */
class SurveyLabelsTest {

    @Test
    void bundledPromptsResolveToTheirLabels() {
        assertEquals("Bug", SurveyLabels.labelFor("Did you face any bugs in this run?"));
        assertEquals("Change one thing",
                SurveyLabels.labelFor("If you could change one thing about the mod, what would it be?"));
    }

    @Test
    void unknownPromptsStayUnlabeled() {
        assertNull(SurveyLabels.labelFor("Would you recommend Dungeon Train to a friend?"));
        assertNull(SurveyLabels.labelFor(""));
        assertNull(SurveyLabels.labelFor(null));
    }
}
