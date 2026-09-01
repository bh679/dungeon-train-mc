package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.net.relay.DonationSummaryClient;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The updates tiles' decisions: which source wins, when the row is absent altogether, and which
 * label the window picks. The one that matters is the second — the tiles only appear once the
 * page has stopped asking for money, and a "0 updates" tile in that slot would be worse than no
 * tile at all, so an unknown figure has to resolve to nothing rather than a zero.
 */
class UpdateStatsTest {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

    private static DonationSummaryClient.Updates relay(int count, int windowMonths) {
        return new DonationSummaryClient.Updates(count, windowMonths, 244,
                NOW.minus(Duration.ofHours(3)).toEpochMilli(), "0.763.0");
    }

    @Test
    void theRelayBeatsTheBakedNumbers() {
        var f = UpdateStats.resolve(relay(765, 5), 700, 4, 100, "2026-08-01");
        assertEquals(765, f.count());
        assertEquals(5, f.windowMonths());
        assertEquals(244, f.recentMonth());
        assertEquals(NOW.minus(Duration.ofHours(3)).toEpochMilli(), f.lastUpdateAtMs());
    }

    @Test
    void anOfflineClientFallsBackToWhatTheJarBaked() {
        var f = UpdateStats.resolve(null, 700, 4, 100, "2026-08-01");
        assertEquals(700, f.count());
        assertEquals(4, f.windowMonths());
        assertEquals(100, f.recentMonth());
        assertEquals(Instant.parse("2026-08-01T00:00:00Z").toEpochMilli(), f.lastUpdateAtMs());
    }

    @Test
    void aRelayBlockWithNoCountIsNotAResult() {
        // An `updates` object the relay's own upstream poll never filled must not shadow the
        // baked numbers — 0 is unknown, and the baked figure is the better answer.
        assertEquals(700, UpdateStats.resolve(relay(0, 5), 700, 4, 100, "2026-08-01").count());
    }

    @Test
    void nothingKnownMeansNoRowAtAll() {
        assertNull(UpdateStats.resolve(null, 0, 0, 0, ""),
                "0 baked means the build could count none — show nothing, never a zero");
        assertNull(UpdateStats.resolve(relay(0, 5), 0, 0, 0, ""));
    }

    @Test
    void theSourcesAreNeverMixed() {
        // A live count under a baked window would mislabel it ("765 in the last 4 months").
        var f = UpdateStats.resolve(relay(765, 5), 700, 4, 100, "2026-08-01");
        assertEquals(5, f.windowMonths());
        assertEquals(244, f.recentMonth());
    }

    @Test
    void anUnparseableBakedDateLeavesTheRecencyTileOut() {
        var f = UpdateStats.resolve(null, 700, 4, 100, "not-a-date");
        assertEquals(700, f.count(), "the count survives a bad date");
        assertFalse(UpdateStats.hasRecency(f));
        assertFalse(UpdateStats.hasRecency(UpdateStats.resolve(null, 700, 4, 100, "")));
        assertTrue(UpdateStats.hasRecency(UpdateStats.resolve(null, 700, 4, 100, "2026-08-01")));
    }

    @Test
    void theCountIsGroupedForTheClientLanguage() {
        var f = UpdateStats.resolve(relay(1765, 5), 0, 0, 0, "");
        assertEquals("1,765", UpdateStats.countValue(f, Locale.US));
        assertEquals("1.765", UpdateStats.countValue(f, Locale.GERMANY));
    }

    @Test
    void ayoungProjectNamesItsOwnWindowRatherThanTheYear() {
        var tc = translatable(UpdateStats.countLabel(UpdateStats.resolve(relay(765, 5), 0, 0, 0, ""),
                "en_us"));
        assertEquals("gui.dungeontrain.death.narr.lbl_updates_window", tc.getKey());
        var months = translatable((net.minecraft.network.chat.Component) tc.getArgs()[0]);
        assertEquals("chat.dungeontrain.time.month.other", months.getKey());
        assertEquals(5L, months.getArgs()[0]);
    }

    @Test
    void aMonthOldProjectSaysMonthNotMonths() {
        var tc = translatable(UpdateStats.countLabel(UpdateStats.resolve(relay(12, 1), 0, 0, 0, ""),
                "en_us"));
        var months = translatable((net.minecraft.network.chat.Component) tc.getArgs()[0]);
        assertEquals("chat.dungeontrain.time.month.one", months.getKey());
    }

    @Test
    void pastItsFirstBirthdayTheTileSaysThisYear() {
        // windowMonths 0 is the relay saying "calendar year to date" — the flip happens there,
        // so an old jar starts reading "this year" without shipping anything.
        var tc = translatable(UpdateStats.countLabel(UpdateStats.resolve(relay(900, 0), 0, 0, 0, ""),
                "en_us"));
        assertEquals("gui.dungeontrain.death.narr.lbl_updates_year", tc.getKey());
    }

    @Test
    void theTooltipQuotesTheLastThirtyDays() {
        var tc = translatable(UpdateStats.countTip(UpdateStats.resolve(relay(765, 5), 0, 0, 0, ""),
                "en_us"));
        assertEquals("gui.dungeontrain.death.narr.tip_updates", tc.getKey());
        var recent = translatable((net.minecraft.network.chat.Component) tc.getArgs()[0]);
        assertEquals("gui.dungeontrain.death.narr.updates_count.other", recent.getKey());
        assertEquals(244L, recent.getArgs()[0]);
    }

    @Test
    void recencyReadsInTheLargestWholeUnit() {
        var tc = translatable(UpdateStats.ago(UpdateStats.resolve(relay(765, 5), 0, 0, 0, ""),
                NOW, "en_us"));
        assertEquals("gui.dungeontrain.death.narr.ago", tc.getKey());
        var elapsed = translatable((net.minecraft.network.chat.Component) tc.getArgs()[0]);
        assertEquals("chat.dungeontrain.time.hour.other", elapsed.getKey());
        assertEquals(3L, elapsed.getArgs()[0]);
    }

    private static TranslatableContents translatable(net.minecraft.network.chat.Component c) {
        return assertInstanceOf(TranslatableContents.class, c.getContents());
    }
}
