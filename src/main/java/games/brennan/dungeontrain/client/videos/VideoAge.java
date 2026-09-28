package games.brennan.dungeontrain.client.videos;

import games.brennan.dungeontrain.narrative.PluralRules;
import net.minecraft.network.chat.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * How old a video is, as the Videos page says it: "Today", "3 days ago", "2 weeks ago",
 * "5 months ago" — and the plain date once it is a year or more old. Pure; pinned by
 * {@code VideoAgeTest}.
 *
 * <p>The relay sends a video's publish <em>day</em> only (no clock time), so a day is the finest
 * honest unit: a video published this morning is "Today", never "3 hours ago". The unit clauses
 * reuse the {@code chat.dungeontrain.time.<unit>} plural families every locale already carries
 * (the same seam {@code PresenceLine} uses).</p>
 */
public final class VideoAge {

    static final int DAYS_PER_WEEK = 7;
    static final int DAYS_PER_MONTH = 30;
    static final int DAYS_PER_YEAR = 365;

    private VideoAge() {}

    /**
     * The age label for an ISO {@code day} against {@code today}, or {@code null} when the day is
     * missing or unparseable (the caller then drops the segment, as it always did). A day in the
     * future (clock skew) or a year or more back is shown as the date itself.
     */
    public static Component label(String day, LocalDate today, String locale) {
        if (day == null || day.isBlank()) return null;
        LocalDate then;
        try {
            then = LocalDate.parse(day.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(then, today);
        if (days == 0) return Component.translatable("gui.dungeontrain.videos.today");
        if (days < 0 || days >= DAYS_PER_YEAR) return Component.literal(then.toString());
        if (days < DAYS_PER_WEEK) return ago(locale, "day", days);
        if (days < DAYS_PER_MONTH) return ago(locale, "week", days / DAYS_PER_WEEK);
        // Calendar months, so the 11th of July reads "2 months ago" on the 14th of September.
        long months = Math.max(1L, ChronoUnit.MONTHS.between(then, today));
        return ago(locale, "month", months);
    }

    private static Component ago(String locale, String unit, long n) {
        return Component.translatable("gui.dungeontrain.videos.ago",
                PluralRules.clause(locale, "chat.dungeontrain.time." + unit, n));
    }
}
