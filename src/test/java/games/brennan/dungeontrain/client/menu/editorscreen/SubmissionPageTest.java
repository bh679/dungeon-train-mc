package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.SubmitNote;
import games.brennan.dungeontrain.editor.SubmitHints;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which questions the Submitted answers page shows, answered or not. */
class SubmissionPageTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final String REDSTONE = "gui.dungeontrain.builder.profile.note.redstone.label";
    private static final String LOOT = "gui.dungeontrain.builder.profile.note.loot.label";
    private static final String NOTES = "gui.dungeontrain.builder.profile.note.notes.label";

    @Test
    void generalQuestionIsAlwaysAsked() {
        List<SubmissionPage.Section> s = SubmissionPage.sections(SubmitNote.EMPTY, SubmitHints.Hints.NONE);
        assertEquals(1, s.size());
        assertEquals(NOTES, s.get(0).labelKey());
        assertFalse(s.get(0).answered(), "unanswered, not hidden");
    }

    @Test
    void earnedQuestionsShowUnanswered() {
        SubmitHints.Hints redstone = new SubmitHints.Hints(
                List.of(new SubmitHints.Found(Blocks.REPEATER, 1, SubmitHints.Kind.REDSTONE, "", 1)), List.of());
        List<SubmissionPage.Section> s = SubmissionPage.sections(SubmitNote.of("near the engine"), redstone);
        assertEquals(List.of(REDSTONE, NOTES), s.stream().map(SubmissionPage.Section::labelKey).toList());
        assertFalse(s.get(0).answered());
        assertTrue(s.get(1).answered());
    }

    @Test
    void anAnswerIsNeverHiddenEvenIfNoLongerEarned() {
        List<SubmissionPage.Section> s = SubmissionPage.sections(new SubmitNote("", " two diamonds ", ""),
                SubmitHints.Hints.NONE);
        assertEquals(List.of(LOOT, NOTES), s.stream().map(SubmissionPage.Section::labelKey).toList());
        assertEquals("two diamonds", s.get(0).text());
    }

    @Test
    void hasAnswers() {
        assertFalse(SubmissionPage.hasAnswers(SubmitNote.EMPTY));
        assertFalse(SubmissionPage.hasAnswers(new SubmitNote("  ", "\n", "")));
        assertFalse(SubmissionPage.hasAnswers(null));
        assertTrue(SubmissionPage.hasAnswers(SubmitNote.of("x")));
    }
}
