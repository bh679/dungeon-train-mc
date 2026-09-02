package games.brennan.dungeontrain.client.support;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Last Active card. The question it answers — is anyone still working on this? — is one a
 * would-be supporter is entitled to an honest answer to, so the tests are mostly about refusing to
 * render something untrue rather than about formatting.
 */
class LastActiveTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    private static long agoHours(long h) {
        return NOW.minus(Duration.ofHours(h)).toEpochMilli();
    }

    @Test
    void aKnownTimestampIsDrawn() {
        assertTrue(LastActive.known(agoHours(3), NOW));
    }

    @Test
    void anUnknownTimestampIsWithheld() {
        // No relay block, or a relay whose poll has not resolved. Withheld, never a zero.
        assertFalse(LastActive.known(0L, NOW));
        assertFalse(LastActive.known(-1L, NOW));
    }

    @Test
    void aFutureTimestampIsWithheld() {
        // Clock skew would otherwise render as a negative duration — "last active in -2 hours".
        assertFalse(LastActive.known(NOW.plus(Duration.ofHours(2)).toEpochMilli(), NOW));
    }

    @Test
    void anOldFigureIsStillDrawn() {
        // Deliberately NOT hidden once it stops flattering the project: a card that appears only
        // when it argues for donating is a badge, not a fact, and this page has a payment button
        // on it.
        assertTrue(LastActive.known(agoHours(24 * 90), NOW), "90 days ago is still the answer");
    }

    @Test
    void theFigureReusesTheSharedDurationPhrasing() {
        // chat.dungeontrain.time.* — the same family the presence lines and the updates tooltip
        // use, so the mod never grows a second opinion on how to say "three hours".
        assertEquals("chat.dungeontrain.time.hour.other", key(LastActive.value(agoHours(3), NOW, "en_us")));
        assertEquals("chat.dungeontrain.time.hour.one", key(LastActive.value(agoHours(1), NOW, "en_us")));
        assertEquals("chat.dungeontrain.time.day.other", key(LastActive.value(agoHours(72), NOW, "en_us")));
    }

    @Test
    void theTooltipNamesTheSameFigure() {
        Component tip = LastActive.tooltip(agoHours(3), NOW, "en_us");
        assertEquals("gui.dungeontrain.death.narr.tip_last_active", key(tip));
        assertEquals("chat.dungeontrain.time.hour.other",
                key((Component) contents(tip).getArgs()[0]));
    }

    @Test
    void theLabelIsItsOwnKey() {
        assertEquals("gui.dungeontrain.death.narr.lbl_last_active", key(LastActive.label()));
    }

    private static TranslatableContents contents(Component c) {
        return assertInstanceOf(TranslatableContents.class, c.getContents());
    }

    private static String key(Component c) {
        return contents(c).getKey();
    }
}
