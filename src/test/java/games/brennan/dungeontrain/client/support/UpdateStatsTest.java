package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.net.relay.DonationSummaryClient;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The updates card's decisions: which source wins, which span it names, and when there is no card
 * at all. The one that matters is the last — the card takes the settled server bill's slot, so a
 * build that knows nothing must leave that slot to the bill rather than draw a zero at a player
 * who is being asked for money.
 */
class UpdateStatsTest {

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final long THREE_HOURS_AGO = NOW.minus(Duration.ofHours(3)).toEpochMilli();

    /** A relay block: {@code count} over {@code windowMonths}, plus the shorter spans. */
    private static DonationSummaryClient.Updates relay(int count, int windowMonths, int month,
                                                       int week, int day, int year) {
        return new DonationSummaryClient.Updates(count, windowMonths, month, week, day, year,
                THREE_HOURS_AGO, "0.768.0");
    }

    private static DonationSummaryClient.Updates relay(int count, int windowMonths, int month, int week) {
        return relay(count, windowMonths, month, week, 0, count);
    }

    private static UpdateStats.Figures figures(int week, int month, int count, int windowMonths) {
        return UpdateStats.resolve(relay(count, windowMonths, month, week), 0, 0, 0, 0, 0, 0, "");
    }

    // ---- which source answers ----

    @Test
    void theRelayBeatsTheBakedNumbers() {
        var f = UpdateStats.resolve(relay(765, 5, 244, 117), 9, 40, 700, 4, 1, 700, "2026-08-01");
        assertEquals(117, f.week());
        assertEquals(244, f.month());
        assertEquals(765, f.windowCount());
        assertEquals(5, f.windowMonths());
        assertEquals(THREE_HOURS_AGO, f.lastUpdateAtMs());
    }

    @Test
    void anOfflineClientFallsBackToWhatTheJarBaked() {
        var f = UpdateStats.resolve(null, 9, 40, 700, 4, 1, 700, "2026-08-01");
        assertEquals(9, f.week());
        assertEquals(700, f.windowCount());
        assertEquals(4, f.windowMonths());
        assertEquals(Instant.parse("2026-08-01T00:00:00Z").toEpochMilli(), f.lastUpdateAtMs());
    }

    @Test
    void aRelayBlockWithNoCountIsNotAResult() {
        // An `updates` object the relay's own upstream poll never filled must not shadow the baked
        // numbers — 0 is unknown, and the baked figure is the better answer.
        assertEquals(700, UpdateStats.resolve(relay(0, 5, 0, 0), 9, 40, 700, 4, 1, 700, "2026-08-01")
                .windowCount());
    }

    @Test
    void nothingKnownMeansNoCardAtAll() {
        assertNull(UpdateStats.resolve(null, 0, 0, 0, 0, 0, 0, ""),
                "0 baked means the build could count none — the settled bill keeps its slot");
        assertFalse(UpdateStats.hasCount(null));
        assertTrue(UpdateStats.hasCount(figures(117, 244, 765, 5)));
    }

    @Test
    void aNonsenseWindowIsStillNamedSanely() {
        // Neither a relay nor a build should send 0 or 400 months, but the card has to render
        // something rather than ask for a plural form of nothing.
        assertEquals(1, figures(0, 0, 5, 0).windowMonths());
        assertEquals(12, figures(0, 0, 5, 400).windowMonths());
    }

    // ---- which span the card names ----

    @Test
    void theCardShowsTheWeekByDefault() {
        var f = figures(117, 244, 765, 5);
        assertEquals(117L, count(UpdateStats.value(f, false, "en_us")));
        assertEquals("chat.dungeontrain.time.week.one", spanKey(f, false));
    }

    @Test
    void aThinWeekReachesForTheMonth() {
        // One update this week is a true number that reads as a dead project — the month is the
        // honest picture, and it is the one shown.
        var f = figures(1, 40, 765, 5);
        assertEquals(40L, count(UpdateStats.value(f, false, "en_us")));
        assertEquals("chat.dungeontrain.time.month.one", spanKey(f, false));
    }

    @Test
    void aThinMonthFallsAllTheWayThroughToTheWindow() {
        var f = figures(0, 1, 765, 5);
        assertEquals(765L, count(UpdateStats.value(f, false, "en_us")));
        assertEquals("chat.dungeontrain.time.month.other", spanKey(f, false));
    }

    @Test
    void hoveringAlwaysTakesTheLongestSpan() {
        var f = figures(117, 244, 765, 5);
        assertEquals(765L, count(UpdateStats.value(f, true, "en_us")));
        assertEquals("chat.dungeontrain.time.month.other", spanKey(f, true));
        assertEquals(5L, spanArg(f, true));
    }

    @Test
    void aFullWindowIsAYearNotTwelveMonths() {
        var f = figures(117, 244, 3000, 12);
        assertEquals("chat.dungeontrain.time.year.one", spanKey(f, true));
        assertEquals(1L, spanArg(f, true));
    }

    @Test
    void oneUpdateTakesTheSingularForm() {
        var f = figures(0, 0, 1, 1);
        assertEquals("gui.dungeontrain.death.narr.updates_value.one",
                translatable(UpdateStats.value(f, false, "en_us")).getKey());
    }

    // ---- the pitch's opening line ----

    @Test
    void theWeekIsNamedInThePitchOnlyWhenItIsWorthNaming() {
        assertTrue(UpdateStats.hasWeekPitch(figures(2, 244, 765, 5)));
        assertTrue(UpdateStats.hasWeekPitch(figures(117, 244, 765, 5)));
        // "1 change was laid this week alone" argues against the sentence it sits in.
        assertFalse(UpdateStats.hasWeekPitch(figures(1, 244, 765, 5)));
        assertFalse(UpdateStats.hasWeekPitch(figures(0, 244, 765, 5)));
        assertFalse(UpdateStats.hasWeekPitch(null), "nothing known — the plain line stands");
    }

    @Test
    void theClauseTakesTheNumberVerbatimSoItsHighlightingSurvives() {
        // The screen hands in the figure already wrapped in its narration sentinels; the clause
        // must pass that string through untouched or the digits stop rendering white.
        var tc = translatable(UpdateStats.changesClause(figures(117, 244, 765, 5), "en_us", "117"));
        assertEquals("gui.dungeontrain.death.narr.changes_count.other", tc.getKey());
        assertEquals("117", tc.getArgs()[0]);
    }

    @Test
    void aSingleChangeWouldTakeTheSingularForm() {
        // Unreachable through hasWeekPitch, but the clause is public and must not be wrong.
        assertEquals("gui.dungeontrain.death.narr.changes_count.one",
                translatable(UpdateStats.changesClause(figures(1, 0, 1, 1), "en_us", "1")).getKey());
    }

    @Test
    void theWeeksFigureIsGroupedForTheClientLanguage() {
        var f = figures(1117, 2440, 7650, 5);
        assertEquals("1,117", UpdateStats.groupedWeek(f, Locale.US));
        assertEquals("1.117", UpdateStats.groupedWeek(f, Locale.GERMANY));
    }

    // ---- the menu splash ----

    /** Figures as the relay would send them, with every timeframe populated. */
    private static UpdateStats.Figures live(int day, int week, int month, int year) {
        return UpdateStats.resolve(relay(770, 5, month, week, day, year), 0, 0, 0, 0, 0, 0, "");
    }

    @Test
    void everyTimeframeWithSomethingToSayIsOffered() {
        assertEquals(List.of(UpdateStats.Timeframe.DAY, UpdateStats.Timeframe.WEEK,
                        UpdateStats.Timeframe.MONTH, UpdateStats.Timeframe.YEAR),
                UpdateStats.splashTimeframes(live(9, 122, 249, 770)));
    }

    @Test
    void aQuietDayDropsOutRatherThanAnnouncingZero() {
        assertEquals(List.of(UpdateStats.Timeframe.WEEK, UpdateStats.Timeframe.MONTH,
                        UpdateStats.Timeframe.YEAR),
                UpdateStats.splashTimeframes(live(0, 122, 249, 770)));
        // A brand-new year with nothing in it yet leaves nothing at all to say.
        assertEquals(List.of(), UpdateStats.splashTimeframes(live(0, 0, 0, 0)));
    }

    @Test
    void bakedFiguresNeverReachTheMenu() {
        // The baked numbers are frozen at build time; "9 changes today!" off a fortnight-old jar is
        // a claim about today that is simply false. The card may use them, the splash may not.
        var baked = UpdateStats.resolve(null, 122, 249, 770, 5, 9, 770, "2026-09-01");
        assertTrue(UpdateStats.hasCount(baked), "the card still draws from them");
        assertFalse(UpdateStats.isLive(baked));
        assertEquals(List.of(), UpdateStats.splashTimeframes(baked));
        assertFalse(UpdateStats.isLive(null));
        assertTrue(UpdateStats.isLive(live(9, 122, 249, 770)));
    }

    @Test
    void eachSplashQuotesItsOwnTimeframesCount() {
        var f = live(9, 122, 249, 770);
        assertSplash(f, UpdateStats.Timeframe.DAY, "gui.dungeontrain.splash.updates_day", "9");
        assertSplash(f, UpdateStats.Timeframe.WEEK, "gui.dungeontrain.splash.updates_week", "122");
        assertSplash(f, UpdateStats.Timeframe.MONTH, "gui.dungeontrain.splash.updates_month", "249");
        assertSplash(f, UpdateStats.Timeframe.YEAR, "gui.dungeontrain.splash.updates_year", "770");
    }

    @Test
    void aSplashCountIsGroupedAndPluralisedForItsLanguage() {
        var many = live(1, 1234, 0, 0);
        var clause = translatable((Component) translatable(
                UpdateStats.splash(many, UpdateStats.Timeframe.WEEK, "en_us", Locale.US)).getArgs()[0]);
        assertEquals("gui.dungeontrain.death.narr.changes_count.other", clause.getKey());
        assertEquals("1,234", clause.getArgs()[0]);

        var one = translatable((Component) translatable(
                UpdateStats.splash(many, UpdateStats.Timeframe.DAY, "en_us", Locale.US)).getArgs()[0]);
        assertEquals("gui.dungeontrain.death.narr.changes_count.one", one.getKey());
    }

    private static void assertSplash(UpdateStats.Figures f, UpdateStats.Timeframe timeframe,
                                     String expectedKey, String expectedCount) {
        var tc = translatable(UpdateStats.splash(f, timeframe, "en_us", Locale.US));
        assertEquals(expectedKey, tc.getKey());
        assertEquals(expectedCount, translatable((Component) tc.getArgs()[0]).getArgs()[0]);
    }

    // ---- the tooltip ----

    @Test
    void theTooltipIsTheRecencyFigure() {
        var f = figures(117, 244, 765, 5);
        var tc = translatable(UpdateStats.tooltip(f, NOW, "en_us"));
        assertEquals("gui.dungeontrain.death.narr.tip_last_update", tc.getKey());
        var elapsed = translatable((Component) tc.getArgs()[0]);
        assertEquals("chat.dungeontrain.time.hour.other", elapsed.getKey());
        assertEquals(3L, elapsed.getArgs()[0]);
    }

    @Test
    void withNoTimestampTheTooltipExplainsInsteadOfGoingBlank() {
        var f = UpdateStats.resolve(null, 9, 40, 700, 4, 1, 700, "not-a-date");
        assertEquals(0L, f.lastUpdateAtMs());
        assertEquals("gui.dungeontrain.death.narr.tip_updates",
                translatable(UpdateStats.tooltip(f, NOW, "en_us")).getKey());
        assertTrue(UpdateStats.hasCount(f), "the card still renders — only the tooltip changes");
    }

    // ---- helpers: read back what a component encodes ----

    private static TranslatableContents translatable(Component c) {
        return assertInstanceOf(TranslatableContents.class, c.getContents());
    }

    /** The count the card's first line is built around. */
    private static long count(Component value) {
        return (Long) translatable(value).getArgs()[0];
    }

    /** The {@code chat.dungeontrain.time.*} key the second line's span resolves to. */
    private static String spanKey(UpdateStats.Figures f, boolean hovered) {
        return translatable((Component) translatable(UpdateStats.label(f, hovered, "en_us"))
                .getArgs()[0]).getKey();
    }

    private static long spanArg(UpdateStats.Figures f, boolean hovered) {
        return (Long) translatable((Component) translatable(UpdateStats.label(f, hovered, "en_us"))
                .getArgs()[0]).getArgs()[0];
    }
}
