package games.brennan.dungeontrain.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** /liveappeal reads a leading line number, and keeps whatever follows as the reason. */
final class LiveAppealCommandTest {

    @Test
    @DisplayName("a leading number picks a line; the rest is the reason")
    void numberThenReason() {
        assertEquals(3, LiveAppealCommandTest.num("3"));
        assertEquals(3, LiveAppealCommandTest.num("3 it was a typo"));
        assertEquals("it was a typo", LiveAppealCommand.reason("3 it was a typo"));
        assertEquals("", LiveAppealCommand.reason("3"));
        assertEquals(12, LiveAppealCommandTest.num("  12   "));
    }

    @Test
    @DisplayName("no number → free text appeal")
    void freeText() {
        assertNull(LiveAppealCommand.lineNumber("it was a typo"));
        assertNull(LiveAppealCommand.lineNumber("3rd line"));
        assertNull(LiveAppealCommand.lineNumber("1234"));
        assertNull(LiveAppealCommand.lineNumber(""));
        assertNull(LiveAppealCommand.lineNumber(null));
        assertEquals("it was a typo", LiveAppealCommand.reason("  it was a typo "));
        assertEquals("", LiveAppealCommand.reason(null));
    }

    private static Integer num(String s) {
        return LiveAppealCommand.lineNumber(s);
    }
}
