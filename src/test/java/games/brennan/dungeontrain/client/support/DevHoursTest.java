package games.brennan.dungeontrain.client.support;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Contribute page's development-hours line. The figure is a fundraising claim, so the rule
 * that matters is what happens when the build could NOT determine one: show nothing at all,
 * rather than a zero or a number the build guessed at.
 */
class DevHoursTest {

    private static TranslatableContents contentsOf(Component c) {
        return assertInstanceOf(TranslatableContents.class, c.getContents());
    }

    @Test
    void anUnknownCountShowsNothing() {
        // 0 is what build.gradle bakes when it could read neither the snapshot nor git.
        assertFalse(DevHours.line(0, Locale.US).isPresent(), "0 means unknown — say nothing");
        assertFalse(DevHours.line(-1, Locale.US).isPresent(), "a nonsense count must not reach a player");
    }

    @Test
    void aRealCountBecomesTheTranslatedLine() {
        Optional<Component> line = DevHours.line(1394, Locale.US);
        assertTrue(line.isPresent());
        TranslatableContents contents = contentsOf(line.get());
        assertEquals("gui.dungeontrain.death.narr.donate_hours", contents.getKey());
        assertEquals(1, contents.getArgs().length, "one argument: the hour count");
    }

    @Test
    void oneHourIsStillAFigureWorthShowing() {
        assertTrue(DevHours.line(1, Locale.US).isPresent());
    }

    @Test
    void theCountIsGroupedForReadability() {
        assertEquals("1,394", DevHours.format(1394, Locale.US));
        assertEquals("999", DevHours.format(999, Locale.US), "no separator below a thousand");
    }

    @Test
    void groupingFollowsTheLocale() {
        // German groups with a full stop — the line must not hard-code an English separator.
        assertEquals("1.394", DevHours.format(1394, Locale.GERMANY));
    }

    @Test
    void groupingFollowsTheLanguageChosenInMinecraft() {
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
