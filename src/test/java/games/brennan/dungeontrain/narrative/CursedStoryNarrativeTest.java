package games.brennan.dungeontrain.narrative;

import games.brennan.dungeontrain.narrative.CursedStoryPool.Encounter;
import games.brennan.dungeontrain.narrative.CursedStoryPool.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down the second-person rendering of a landed curse's encounter — the difference between the
 * public Discord bulletin ("A remote echo of Brennan stepped aboard Dev's train") and what the curser
 * reads in their own book ("They crossed paths. Alex struck first.").
 */
final class CursedStoryNarrativeTest {

    private static Story story(String outcome, Encounter enc) {
        return new Story(1, "Alex", 20, 0L, 0L, outcome, enc, NoteKind.DEATH);
    }

    private static Encounter enc(List<String> beats, String end) {
        return new Encounter(beats, List.of(), List.of(), end, 30L);
    }

    @Test
    @DisplayName("the full account reads gear → beats → spoils → ending, in that order")
    void fullAccount() {
        Story s = story("target_killed_echo", new Encounter(
            List.of("SPAWNED", "MET", "PLAYER_STRUCK_ECHO", "ECHO_STRUCK_PLAYER"),
            List.of("a netherite axe", "a shield"),
            List.of("a golden apple"),
            "ECHO_SLAIN_BY_YOU", 61L));
        assertEquals("It still carried a netherite axe and a shield. They crossed paths. "
                + "Alex struck first. It struck back. Along the way it took a golden apple. "
                + "Alex killed it.",
            CursedStoryNarrative.story(s));
    }

    @Test
    @DisplayName("the arrival beat is the book's framing, so SPAWNED is never a sentence")
    void spawnedIsNotNarrated() {
        String out = CursedStoryNarrative.story(story("", enc(List.of("SPAWNED"), "LEFT_BEHIND")));
        assertEquals("The train rolled on and left your echo behind.", out);
    }

    @Test
    @DisplayName("every ending has a line, including the ones with no verdict")
    void everyEndingRenders() {
        assertEquals("Alex killed it.",
            CursedStoryNarrative.ending(story("", enc(List.of(), "ECHO_SLAIN_BY_YOU"))));
        assertEquals("It killed Alex.",
            CursedStoryNarrative.ending(story("", enc(List.of(), "YOU_SLAIN_BY_ECHO"))));
        assertEquals("Something else killed it before Alex could.",
            CursedStoryNarrative.ending(story("", enc(List.of(), "ECHO_SLAIN"))));
        assertEquals("Alex died to something else entirely, and your echo wandered on.",
            CursedStoryNarrative.ending(story("", enc(List.of(), "YOU_DIED"))));
        assertEquals("The train rolled on and left your echo behind.",
            CursedStoryNarrative.ending(story("", enc(List.of(), "LEFT_BEHIND"))));
    }

    @Test
    @DisplayName("with no journal, the ending falls back to the bare reported outcome")
    void endingFallsBackToOutcome() {
        assertEquals("Alex killed it.", CursedStoryNarrative.ending(story("target_killed_echo", null)));
        assertEquals("It killed Alex.", CursedStoryNarrative.ending(story("echo_killed_target", null)));
        assertEquals("", CursedStoryNarrative.ending(story("", null)));
        assertEquals("", CursedStoryNarrative.story(story("target_killed_echo", null)));
    }

    @Test
    @DisplayName("a beat name this build doesn't know is skipped, never printed raw")
    void unknownBeatsAreSkipped() {
        String out = CursedStoryNarrative.story(
            story("", enc(List.of("MET", "DANCED_A_JIG", "ECHO_STRUCK_PLAYER"), "ECHO_SLAIN")));
        assertFalse(out.contains("DANCED_A_JIG"), "a future beat must not leak into a book");
        assertTrue(out.startsWith("They crossed paths. It struck back."));
    }

    @Test
    @DisplayName("a nameless target degrades to the stand-in rather than an empty gap")
    void namelessTarget() {
        Story s = new Story(1, "  ", 20, 0L, 0L, "", enc(List.of("MET"), "ECHO_SLAIN_BY_YOU"),
                NoteKind.DEATH);
        assertEquals("They crossed paths. someone killed it.", CursedStoryNarrative.story(s));
    }
}
