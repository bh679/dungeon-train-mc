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

/**
 * How many updates have shipped and how long ago the newest one was — the pair the death screen's
 * donation page shows once the running costs are met, beside the hours behind the train.
 *
 * <p>An <b>update</b> is a MINOR version bump: one per Gate 3 merge, one shipped change. The
 * auto-release cascade's PATCH ticks are excluded at the source ({@code
 * scripts/version-history/collect.py}), because counting automation would treble the figure.</p>
 *
 * <p>The window shrinks to the project's own age while it is younger than a year: a five-month-old
 * game says "765 updates in the last 5 months", not "this year". Once it passes a year the window
 * becomes the calendar year to date and the label becomes "this year" — a flip the relay performs
 * on its own, so no jar has to ship for it.</p>
 *
 * <p>Two sources, in order: the relay's live block ({@link DonationSummaryClient.Updates}), else
 * the numbers {@link VersionInfo} baked at build time for a player who is offline or on a relay
 * that predates the field. Neither available means <b>unknown</b>, and the page omits the row
 * entirely rather than putting a zero in front of a would-be donor — the same rule
 * {@link DevHours#takesGoalSlot} follows.</p>
 */
public final class UpdateStats {

    /**
     * The resolved figures. {@code windowMonths} is {@code 0} for "the calendar year so far";
     * {@code lastUpdateAtMs} is {@code 0} when no timestamp could be resolved, which suppresses
     * the recency tile on its own without hiding the count.
     */
    public record Figures(int count, int windowMonths, int recentMonth, long lastUpdateAtMs) {}

    private UpdateStats() {}

    /** The figures for this client right now, or null when nothing is known. */
    public static Figures current(DonationSummaryClient.Updates relay) {
        return resolve(relay, VersionInfo.UPDATES_COUNT, VersionInfo.UPDATES_WINDOW_MONTHS,
                VersionInfo.UPDATES_MONTH, VersionInfo.LAST_UPDATE_DATE);
    }

    /**
     * Relay first, baked second, null when neither has a count. Kept pure for tests — the baked
     * values are passed in rather than read, so a test can pin every combination.
     *
     * <p>The two sources are never mixed: a relay block that resolved is authoritative for all
     * four figures, because its window and its count were derived together from the same day and
     * pairing a live count with a baked window would mislabel it.</p>
     */
    public static Figures resolve(DonationSummaryClient.Updates relay, int bakedCount,
                                  int bakedWindowMonths, int bakedMonth, String bakedDay) {
        if (relay != null && relay.count() > 0) {
            return new Figures(relay.count(), relay.windowMonths(), relay.month(),
                    relay.latestReleaseAtMs());
        }
        if (bakedCount > 0) {
            return new Figures(bakedCount, bakedWindowMonths, bakedMonth, dayToMillis(bakedDay));
        }
        return null;
    }

    /**
     * {@code "2026-09-01"} -> that day's start in UTC, as epoch millis; {@code 0} for anything
     * unparseable. Day resolution is all a jar can bake — the exact release time lives on the
     * relay — so an offline client's "last update" is coarse by construction.
     */
    static long dayToMillis(String day) {
        if (day == null || day.isBlank()) return 0L;
        try {
            return LocalDate.parse(day.trim()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0L;
        }
    }

    /** The count, grouped for the language chosen in Minecraft ("765" / "1.394"). */
    public static String countValue(Figures f) {
        return countValue(f, DevHours.clientLocale());
    }

    /** As {@link #countValue(Figures)}, for an explicit locale — kept pure for tests. */
    public static String countValue(Figures f, java.util.Locale locale) {
        return NumberFormat.getIntegerInstance(locale).format(f.count());
    }

    /**
     * The tile's label: "updates this year" once the project is a year old, else "updates in the
     * last N months" with N given the grammatical number its language wants.
     */
    public static Component countLabel(Figures f) {
        return countLabel(f, ClientLanguage.selected());
    }

    /** As {@link #countLabel(Figures)}, for an explicit Minecraft language code. */
    public static Component countLabel(Figures f, String localeCode) {
        if (f.windowMonths() <= 0) {
            return Component.translatable("gui.dungeontrain.death.narr.lbl_updates_year");
        }
        return Component.translatable("gui.dungeontrain.death.narr.lbl_updates_window",
                PluralRules.clause(localeCode, "chat.dungeontrain.time.month", f.windowMonths()));
    }

    /** The hover tooltip: how many of those updates landed in the last 30 days. */
    public static Component countTip(Figures f) {
        return countTip(f, ClientLanguage.selected());
    }

    /** As {@link #countTip(Figures)}, for an explicit Minecraft language code. */
    public static Component countTip(Figures f, String localeCode) {
        return Component.translatable("gui.dungeontrain.death.narr.tip_updates",
                PluralRules.clause(localeCode, "gui.dungeontrain.death.narr.updates_count",
                        f.recentMonth()));
    }

    /** Whether a recency figure is known at all — the tile is dropped when it isn't. */
    public static boolean hasRecency(Figures f) {
        return f.lastUpdateAtMs() > 0L;
    }

    /**
     * "2 hours ago" in the client's language, from the coarse largest-whole-unit phrasing
     * {@link PresenceLine} already uses for Discord presence, so the two never drift apart.
     */
    public static Component ago(Figures f, Instant now) {
        return ago(f, now, ClientLanguage.selected());
    }

    /** As {@link #ago(Figures, Instant)}, for an explicit Minecraft language code. */
    public static Component ago(Figures f, Instant now, String localeCode) {
        Duration elapsed = Duration.between(Instant.ofEpochMilli(f.lastUpdateAtMs()), now);
        return Component.translatable("gui.dungeontrain.death.narr.ago",
                PresenceLine.agoComponent(localeCode, elapsed));
    }
}
