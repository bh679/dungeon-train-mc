package games.brennan.dungeontrain.client.support;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hours figure's two decisions: when it takes the engine-room ledger's lead slot, and how the
 * number reads. The one that matters is the first — the tile displaces a funding goal, so it must
 * only appear once that goal is actually settled, and never on a build that could not work out an
 * hour count (which bakes 0, meaning unknown, not "no work").
 */
class DevHoursTest {

    @Test
    void theHoursTileLeadsOnlyOnceBothRungsAreFunded() {
        assertTrue(DevHours.takesGoalSlot(1394, true, true));
    }

    @Test
    void aGoalStillBeingAskedForKeepsTheLeadSlot() {
        assertFalse(DevHours.takesGoalSlot(1394, true, false), "the goal is still the ask");
        assertFalse(DevHours.takesGoalSlot(1394, false, false), "the server bill is still the ask");
        // Belt and braces: the goal cannot outrank an unpaid bill, but if the relay ever reported
        // that shape the bill must still lead.
        assertFalse(DevHours.takesGoalSlot(1394, false, true));
    }

    @Test
    void anUnknownCountNeverTakesTheSlot() {
        // 0 is what build.gradle bakes when it could read neither the snapshot nor git history.
        assertFalse(DevHours.takesGoalSlot(0, true, true), "0 means unknown — leave the layout alone");
        assertFalse(DevHours.takesGoalSlot(-1, true, true), "a nonsense count must not reach a player");
    }

    @Test
    void oneHourIsStillAFigureWorthShowing() {
        assertTrue(DevHours.takesGoalSlot(1, true, true));
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
