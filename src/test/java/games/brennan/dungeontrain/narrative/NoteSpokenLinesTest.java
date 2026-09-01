package games.brennan.dungeontrain.narrative;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The script a note echo reads aloud, and its pacing — {@link NoteSpokenLines}. Pure, so the rules
 * the author's game, the relay and the target's game all agree on can be checked without a server.
 */
class NoteSpokenLinesTest {

    @Test
    void theTargetNameLineIsNeverSpoken() {
        // Line one of page one is how a note names its victim (DeathNoteTitle.firstLineTarget); the
        // echo calls that name out itself, so it must not also be read as a line of the note.
        List<String> lines = NoteSpokenLines.fromPages(List.of("Victim\nI remember.\nNow I take yours."));
        assertEquals(List.of("I remember.", "Now I take yours."), lines);
    }

    @Test
    void blankLinesAndPaddingAreDropped_andPagesRunOnInOrder() {
        List<String> lines = NoteSpokenLines.fromPages(Arrays.asList(
                "  \n\nVictim\n\n  one  \n", null, "two\n\n\nthree"));
        assertEquals(List.of("one", "two", "three"), lines);
    }

    @Test
    void aNoteThatIsOnlyANameHasNothingToSay() {
        assertEquals(List.of(), NoteSpokenLines.fromPages(List.of("Victim")));
        assertEquals(List.of(), NoteSpokenLines.fromPages(List.of("   ")));
        assertEquals(List.of(), NoteSpokenLines.fromPages(List.of()));
        assertEquals(List.of(), NoteSpokenLines.fromPages(null));
    }

    @Test
    void theScriptIsCappedInLinesAndInLineLength() {
        StringBuilder page = new StringBuilder("Victim\n");
        for (int i = 0; i < NoteSpokenLines.MAX_LINES + 10; i++) page.append("line ").append(i).append('\n');
        assertEquals(NoteSpokenLines.MAX_LINES, NoteSpokenLines.fromPages(List.of(page.toString())).size());

        String long_ = "x".repeat(NoteSpokenLines.MAX_LINE_CHARS + 50);
        List<String> clamped = NoteSpokenLines.fromPages(List.of("Victim\n" + long_));
        assertEquals(NoteSpokenLines.MAX_LINE_CHARS, clamped.get(0).length());
    }

    @Test
    void aLongerLineBuysALongerSilenceAfterIt() {
        int shortLine = NoteSpokenLines.delayTicksFor("I remember.");
        int longLine = NoteSpokenLines.delayTicksFor(
                "You left me on the tracks at the ninth carriage and did not look back once.");
        assertTrue(longLine > shortLine, "pacing must follow the length of the line");
    }

    @Test
    void pacingIsClampedAtBothEnds() {
        assertEquals(NoteSpokenLines.MIN_DELAY_TICKS, NoteSpokenLines.delayTicksFor("hi"));
        assertEquals(NoteSpokenLines.MIN_DELAY_TICKS, NoteSpokenLines.delayTicksFor(""));
        assertEquals(NoteSpokenLines.MIN_DELAY_TICKS, NoteSpokenLines.delayTicksFor(null));
        assertEquals(NoteSpokenLines.MAX_DELAY_TICKS, NoteSpokenLines.delayTicksFor("x".repeat(1000)));
    }

    @Test
    void aLongerNoteStartsFromFurtherAway() {
        // One carriage of run-up per two lines, so a long note has room to finish around the time
        // the echo actually arrives instead of still being mid-page.
        assertEquals(1, NoteSpokenLines.startCarriageGap(1));
        assertEquals(1, NoteSpokenLines.startCarriageGap(2));
        assertEquals(2, NoteSpokenLines.startCarriageGap(3));
        assertEquals(2, NoteSpokenLines.startCarriageGap(4));
        assertEquals(8, NoteSpokenLines.startCarriageGap(NoteSpokenLines.MAX_LINES));
    }

    @Test
    void aShortNoteKeepsTheAdjacentCarriageGapItAlwaysHad() {
        // The floor is the pre-existing behaviour: nothing gets a SMALLER window than it used to.
        assertEquals(1, NoteSpokenLines.startCarriageGap(0));
        assertEquals(1, NoteSpokenLines.startCarriageGap(-3));
    }

    @Test
    void theCapsMatchTheRelaysOwnCaps() {
        // deathnotes.js MAX_BODY_LINES / MAX_BODY_LINE_CHARS. Three copies of these numbers exist by
        // design (author, relay, target); this is the one that fails loudly if they drift.
        assertEquals(16, NoteSpokenLines.MAX_LINES);
        assertEquals(128, NoteSpokenLines.MAX_LINE_CHARS);
    }
}
