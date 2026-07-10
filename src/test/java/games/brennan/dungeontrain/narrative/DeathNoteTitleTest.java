package games.brennan.dungeontrain.narrative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the pure "Death Note" signing rules (see {@link DeathNoteTitle}): the trigger title is
 * matched case- and whitespace-insensitively, and the curse target is the first non-empty line of
 * page 1.
 */
class DeathNoteTitleTest {

    @Test
    void recognisesTheTitleRegardlessOfCaseAndSpacing() {
        assertTrue(DeathNoteTitle.isDeathNoteTitle("Death Note"));
        assertTrue(DeathNoteTitle.isDeathNoteTitle("deathnote"));
        assertTrue(DeathNoteTitle.isDeathNoteTitle("DEATHNOTE"));
        assertTrue(DeathNoteTitle.isDeathNoteTitle("death note"));
        assertTrue(DeathNoteTitle.isDeathNoteTitle("  death   NOTE  "));
        assertTrue(DeathNoteTitle.isDeathNoteTitle("DeAtH\tNoTe"));
    }

    @Test
    void rejectsNonMatchingTitles() {
        assertFalse(DeathNoteTitle.isDeathNoteTitle(null));
        assertFalse(DeathNoteTitle.isDeathNoteTitle(""));
        assertFalse(DeathNoteTitle.isDeathNoteTitle("my diary"));
        assertFalse(DeathNoteTitle.isDeathNoteTitle("death notes")); // plural → "deathnotes"
        assertFalse(DeathNoteTitle.isDeathNoteTitle("deathnotebook"));
        assertFalse(DeathNoteTitle.isDeathNoteTitle("the death note")); // extra word
    }

    @Test
    void targetIsTheFirstNonEmptyLineOfPageOne() {
        assertEquals("Steve", DeathNoteTitle.firstLineTarget(List.of("Steve\nyou will pay", "page 2")));
        assertEquals("Victim", DeathNoteTitle.firstLineTarget(List.of("\n\n  Victim  \nmore")));
        assertEquals("Alex", DeathNoteTitle.firstLineTarget(List.of("Alex")));
        assertEquals("Bob", DeathNoteTitle.firstLineTarget(List.of("   Bob   ")));
    }

    @Test
    void noTargetWhenPageOneIsBlankOrAbsent() {
        assertNull(DeathNoteTitle.firstLineTarget(null));
        assertNull(DeathNoteTitle.firstLineTarget(List.of()));
        assertNull(DeathNoteTitle.firstLineTarget(List.of("")));
        assertNull(DeathNoteTitle.firstLineTarget(List.of("   \n  ")));
        // only page 1 is consulted — a blank page 1 does not fall through to page 2
        assertNull(DeathNoteTitle.firstLineTarget(List.of("", "Victim")));
    }
}
