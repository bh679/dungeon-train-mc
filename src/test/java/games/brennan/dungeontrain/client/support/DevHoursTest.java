package games.brennan.dungeontrain.client.support;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hours figure's two decisions: whether there is a count worth drawing at all, and how the
 * number reads. The first is the one that matters — a build that could not work out an hour count
 * bakes 0, which means unknown, not "no work", and an unknown figure must never reach a player as
 * a zero.
 */
class DevHoursTest {

    @Test
    void aRealCountIsDrawn() {
        assertTrue(DevHours.known(1394));
    }

    @Test
    void anUnknownCountIsWithheld() {
        // 0 is what build.gradle bakes when it could read neither the snapshot nor git history.
        assertFalse(DevHours.known(0), "0 means unknown — draw no card rather than a zero");
        assertFalse(DevHours.known(-1), "a nonsense count must not reach a player");
    }

    @Test
    void oneHourIsStillAFigureWorthShowing() {
        assertTrue(DevHours.known(1));
    }

    @Test
    void theCountIsNoLongerGatedOnTheFundingLadder() {
        // The card used to appear only once every goal was funded — a layout constraint (one free
        // slot, and the ask had first claim) rather than anything about the figure, which is just
        // as true while the bill is unpaid. The ask now holds its own slot, so the gate is gone.
        assertTrue(DevHours.known(1394), "no ladder state can withhold a known figure");
    }

    @Test
    void theCountIsGroupedForReadability() {
        assertEquals("1,394", DevHours.format(1394, Locale.US));
        assertEquals("999", DevHours.format(999, Locale.US), "no separator below a thousand");
    }

    @Test
    void groupingFollowsTheLanguageChosenInMinecraft() {
        // German groups with a full stop — the tile must not hard-code an English separator.
        assertEquals("1.394", DevHours.format(1394, Locale.GERMANY));
        assertEquals(Locale.GERMANY, DevHours.localeOf("de_de"));
        assertEquals("de", DevHours.localeOf("de").getLanguage());
        // Before the client is up there is no selected language — fall back, never crash.
        assertEquals(Locale.getDefault(), DevHours.localeOf(null));
        assertEquals(Locale.getDefault(), DevHours.localeOf("  "));
    }

    @Test
    void theBakedValueIsNeverNegative() {
        // VersionInfo clamps; a jar built without the property reads 0, never a crash.
        assertTrue(DevHours.hours() >= 0);
    }
}
