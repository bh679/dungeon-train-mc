package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.ClientLanguage;
import games.brennan.dungeontrain.client.VersionInfo;
import games.brennan.dungeontrain.narrative.PluralRules;
import games.brennan.dungeontrain.net.relay.DonationSummaryClient;
import games.brennan.dungeontrain.util.PresenceLine;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * The updates card on the death screen's donation page — the tile that takes the server bill's
 * slot once that bill is paid. Two lines: how many updates have shipped, and the span they landed
 * in.
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
 * it had before this card existed rather than putting a zero in front of a would-be donor — the
 * same rule {@link DevHours#takesGoalSlot} follows.</p>
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
                          long lastUpdateAtMs) {}

    private UpdateStats() {}

    /** The figures for this client right now, or null when nothing is known. */
    public static Figures current(DonationSummaryClient.Updates relay) {
        return resolve(relay, VersionInfo.UPDATES_WEEK, VersionInfo.UPDATES_MONTH,
                VersionInfo.UPDATES_COUNT, VersionInfo.UPDATES_WINDOW_MONTHS,
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
                                  String bakedDay) {
        if (relay != null && relay.count() > 0) {
            return new Figures(relay.week(), relay.month(), relay.count(),
                    clampMonths(relay.windowMonths()), relay.latestReleaseAtMs());
        }
        if (bakedCount > 0) {
            return new Figures(bakedWeek, bakedMonth, bakedCount, clampMonths(bakedWindowMonths),
                    dayToMillis(bakedDay));
        }
        return null;
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
