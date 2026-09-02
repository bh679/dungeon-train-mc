package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.ClientLanguage;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.narrative.PluralRules;
import games.brennan.dungeontrain.net.relay.DonationSummaryClient;
import games.brennan.dungeontrain.util.PresenceLine;
import net.minecraft.network.chat.Component;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The updates card on the death screen's donation page — the third tile of the grid, in every
 * state. Two lines: how many updates have shipped, and the span they landed in. It displaces the
 * month's takings while the server bill is still the ask, and the settled bill itself once that
 * bill is paid.
 *
 * <pre>
 *   765 Updates
 *   in 1 week
 * </pre>
 *
 * <p>An <b>update</b> is a MINOR version bump: one per Gate 3 merge, one shipped change. The
 * auto-release cascade's PATCH ticks are excluded at the source ({@code
 * scripts/version-history/collect.py}), because counting automation would treble the figure.</p>
 *
 * <p>The card shows the <b>last week</b> by default and swaps to the <b>longest window on
 * offer</b> while the cursor is over it — a year, or the project's own age when it is younger.
 * A week too thin to be worth showing (fewer than two updates) falls back to the month, and a
 * month too thin falls back to the long window: the card never has to print "1 Update".</p>
 *
 * <p>Two sources, in order: the relay's live block ({@link DonationSummaryClient.Updates}), else
 * the numbers {@link VersionInfo} baked at build time for a player who is offline or on a relay
 * that predates the field. Neither available means <b>unknown</b>, and the page keeps the layout
 * it had before this card existed — whichever tile the card would have displaced stays — rather
 * than putting a zero in front of a would-be donor, the same rule {@link DevHours#takesGoalSlot}
 * follows.</p>
 */
public final class UpdateStats {

    /** Fewer updates than this in a span and the card reaches for a longer one. */
    private static final int MIN_WORTH_SHOWING = 2;
    /** {@link #WINDOW} at this many months is a year, and says so. */
    private static final int MONTHS_IN_YEAR = 12;

    /** The three spans the card can name, shortest first. */
    private enum Span { WEEK, MONTH, WINDOW }

    /**
     * The resolved figures. {@code windowCount} covers {@code windowMonths} months;
     * {@code lastUpdateAtMs} is {@code 0} when no timestamp could be resolved, which only costs
     * the tooltip its recency line.
     */
    public record Figures(int week, int month, int windowCount, int windowMonths,
                          int day, int year, long lastUpdateAtMs, boolean live) {}

    /**
     * A timeframe the menu splash can name. Ordered shortest first, which is the order a player
     * reading several of them over a few visits would expect them in.
     */
    public enum Timeframe {
        DAY("gui.dungeontrain.splash.updates_day"),
        WEEK("gui.dungeontrain.splash.updates_week"),
        MONTH("gui.dungeontrain.splash.updates_month"),
        YEAR("gui.dungeontrain.splash.updates_year");

        private final String key;

        Timeframe(String key) {
            this.key = key;
        }

        int countIn(Figures f) {
            return switch (this) {
                case DAY -> f.day();
                case WEEK -> f.week();
                case MONTH -> f.month();
                case YEAR -> f.year();
            };
        }
    }

    private UpdateStats() {}

    /** The figures for this client right now, or null when nothing is known. */
    public static Figures current(DonationSummaryClient.Updates relay) {
        return resolve(relay, VersionInfo.UPDATES_WEEK, VersionInfo.UPDATES_MONTH,
                VersionInfo.UPDATES_COUNT, VersionInfo.UPDATES_WINDOW_MONTHS,
                VersionInfo.UPDATES_DAY, VersionInfo.UPDATES_YEAR,
                VersionInfo.LAST_UPDATE_DATE);
    }

    /**
     * Relay first, baked second, null when neither knows a count. Kept pure for tests — the baked
     * values are passed in rather than read, so a test can pin every combination.
     *
     * <p>The two sources are never mixed: a relay block that resolved is authoritative for every
     * figure, because its spans and its counts were derived together on the same day, and pairing
     * a live count with a baked window would mislabel it.</p>
     */
    public static Figures resolve(DonationSummaryClient.Updates relay, int bakedWeek,
                                  int bakedMonth, int bakedCount, int bakedWindowMonths,
                                  int bakedToday, int bakedYear, String bakedDay) {
        if (relay != null && relay.count() > 0) {
            return new Figures(relay.week(), relay.month(), relay.count(),
                    clampMonths(relay.windowMonths()), relay.day(), relay.year(),
                    relay.latestReleaseAtMs(), true);
        }
        if (bakedCount > 0) {
            return new Figures(bakedWeek, bakedMonth, bakedCount, clampMonths(bakedWindowMonths),
                    bakedToday, bakedYear, dayToMillis(bakedDay), false);
        }
        return null;
    }

    /**
     * Whether these figures came from the relay rather than the jar.
     *
     * <p>The menu splash requires it. A line reading "9 changes today!" is a claim about today, and
     * the baked numbers are frozen at build time — on a jar a fortnight old that sentence is
     * confidently wrong. The death screen's card is happy with either: "770 in 5 months" ages by a
     * rounding error, not by a lie.</p>
     */
    public static boolean isLive(Figures f) {
        return f != null && f.live();
    }

    /**
     * The timeframes worth naming on the menu, shortest first. A timeframe with nothing in it is
     * dropped rather than shown as a zero, so a quiet Monday offers three lines instead of
     * announcing that nothing happened.
     */
    public static List<Timeframe> splashTimeframes(Figures f) {
        if (!isLive(f)) return List.of();
        List<Timeframe> out = new ArrayList<>(Timeframe.values().length);
        for (Timeframe t : Timeframe.values()) {
            if (t.countIn(f) > 0) out.add(t);
        }
        return out;
    }

    /** One menu splash — "122 changes this week!" — for the language chosen in Minecraft. */
    public static Component splash(Figures f, Timeframe timeframe) {
        return splash(f, timeframe, ClientLanguage.selected(), DevHours.clientLocale());
    }

    /** As {@link #splash(Figures, Timeframe)}, with both locales explicit — kept pure for tests. */
    public static Component splash(Figures f, Timeframe timeframe, String localeCode,
                                   java.util.Locale grouping) {
        int count = timeframe.countIn(f);
        // "10 new updates" / "1 new update" — its own clause rather than the death-screen pitch's
        // changes_count, because this sentence supplies its own noun phrase ("made … to the
        // Dungeon Train mod!") and needs the number on its own, not pre-bound to the word "changes".
        Component clause = Component.translatable("gui.dungeontrain.splash.new_updates."
                + PluralRules.category(localeCode, count),
                NumberFormat.getIntegerInstance(grouping).format(count));
        return Component.translatable(timeframe.key, clause);
    }

    /** A window a relay or a build could not state properly still has to name something sane. */
    private static int clampMonths(int months) {
        return Math.max(1, Math.min(MONTHS_IN_YEAR, months));
    }

    /**
     * {@code "2026-09-01"} -> that day's start in UTC, as epoch millis; {@code 0} for anything
     * unparseable. Day resolution is all a jar can bake — the exact release time lives on the
     * relay — so an offline client's recency line is coarse by construction.
     */
    static long dayToMillis(String day) {
        if (day == null || day.isBlank()) return 0L;
        try {
            return LocalDate.parse(day.trim()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0L;
        }
    }

    /**
     * The span the card names when it is not being hovered: the week, unless too few landed in it
     * to be worth showing, in which case the month, and then the long window. Hovering always
     * takes the long window — that is what the hover is for.
     */
    private static Span span(Figures f, boolean hovered) {
        if (hovered) return Span.WINDOW;
        if (f.week() >= MIN_WORTH_SHOWING) return Span.WEEK;
        if (f.month() >= MIN_WORTH_SHOWING) return Span.MONTH;
        return Span.WINDOW;
    }

    private static int countOf(Figures f, Span span) {
        return switch (span) {
            case WEEK -> f.week();
            case MONTH -> f.month();
            case WINDOW -> f.windowCount();
        };
    }

    /** Whether there is anything to draw at all — a card with no count is no card. */
    public static boolean hasCount(Figures f) {
        return f != null && f.windowCount() > 0;
    }

    /**
     * Whether the donation page's pitch can name the week's work — "117 changes were laid this week
     * alone" — rather than opening with the plain line.
     *
     * <p>The same {@link #MIN_WORTH_SHOWING} threshold the card uses for its own default span, for
     * the same reason and then some: "1 change was laid this week alone" argues against the
     * sentence it sits in, and a quiet week is exactly when the pitch can least afford to sound
     * dead. Below the threshold the page says nothing about the week at all.</p>
     */
    public static boolean hasWeekPitch(Figures f) {
        return f != null && f.week() >= MIN_WORTH_SHOWING;
    }

    /**
     * "117 changes", for the pitch's opening sentence, with the grammatical number the language
     * wants.
     *
     * <p>{@code numberText} is the already-rendered figure rather than a count, so the caller can
     * hand in a string the death screen has wrapped in its number sentinels — which is what makes
     * the digits render white against the muted narration around them. Use {@link #groupedWeek} to
     * produce it.</p>
     */
    public static Component changesClause(Figures f, String localeCode, String numberText) {
        return Component.translatable("gui.dungeontrain.death.narr.changes_count."
                + PluralRules.category(localeCode, f.week()), numberText);
    }

    /** The week's count, grouped for the language chosen in Minecraft ("117" / "1.117"). */
    public static String groupedWeek(Figures f) {
        return groupedWeek(f, DevHours.clientLocale());
    }

    /** As {@link #groupedWeek(Figures)}, for an explicit locale — kept pure for tests. */
    public static String groupedWeek(Figures f, java.util.Locale locale) {
        return NumberFormat.getIntegerInstance(locale).format(f.week());
    }

    /** The card's first line — "765 Updates" — for the language chosen in Minecraft. */
    public static Component value(Figures f, boolean hovered) {
        return value(f, hovered, ClientLanguage.selected());
    }

    /** As {@link #value(Figures, boolean)}, for an explicit Minecraft language code. */
    public static Component value(Figures f, boolean hovered, String localeCode) {
        return PluralRules.clause(localeCode, "gui.dungeontrain.death.narr.updates_value",
                countOf(f, span(f, hovered)));
    }

    /** The card's second line — "in 1 week" / "in 5 months" / "in 1 year". */
    public static Component label(Figures f, boolean hovered) {
        return label(f, hovered, ClientLanguage.selected());
    }

    /** As {@link #label(Figures, boolean)}, for an explicit Minecraft language code. */
    public static Component label(Figures f, boolean hovered, String localeCode) {
        return Component.translatable("gui.dungeontrain.death.narr.lbl_updates_in",
                spanClause(f, span(f, hovered), localeCode));
    }

    /**
     * The span itself as a localized "N unit" clause, through the same
     * {@code chat.dungeontrain.time.*} plural family {@link PresenceLine} uses for durations —
     * so "1 week", "5 months" and "1 year" each take the grammatical number their language wants.
     */
    private static Component spanClause(Figures f, Span span, String localeCode) {
        return switch (span) {
            case WEEK -> PluralRules.clause(localeCode, "chat.dungeontrain.time.week", 1);
            case MONTH -> PluralRules.clause(localeCode, "chat.dungeontrain.time.month", 1);
            case WINDOW -> f.windowMonths() >= MONTHS_IN_YEAR
                    ? PluralRules.clause(localeCode, "chat.dungeontrain.time.year", 1)
                    : PluralRules.clause(localeCode, "chat.dungeontrain.time.month", f.windowMonths());
        };
    }

    /**
     * The hover tooltip: how long ago the newest release went out, in the coarse
     * largest-whole-unit phrasing {@link PresenceLine} already uses, so the two never drift apart.
     * Falls back to saying what an update <i>is</i> on a build with no timestamp to quote.
     */
    public static Component tooltip(Figures f, Instant now) {
        return tooltip(f, now, ClientLanguage.selected());
    }

    /** As {@link #tooltip(Figures, Instant)}, for an explicit Minecraft language code. */
    public static Component tooltip(Figures f, Instant now, String localeCode) {
        if (f.lastUpdateAtMs() <= 0L) {
            return Component.translatable("gui.dungeontrain.death.narr.tip_updates");
        }
        Duration elapsed = Duration.between(Instant.ofEpochMilli(f.lastUpdateAtMs()), now);
        return Component.translatable("gui.dungeontrain.death.narr.tip_last_update",
                PresenceLine.agoComponent(localeCode, elapsed));
    }
}
