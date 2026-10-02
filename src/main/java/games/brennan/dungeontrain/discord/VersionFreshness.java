package games.brennan.dungeontrain.discord;

import games.brennan.dungeontrain.client.version.SemverCompare;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * How current a build was at the moment its player sent feedback, as the coloured dot that leads
 * the feedback header (see {@link SurveyTag}).
 *
 * <p>Discord cannot colour inline text, so the colour is an emoji. It is a snapshot: the post is
 * written once, so a build that was current when the answer was sent stays green.</p>
 *
 * <p>Pure — no clock, no network — so every boundary is unit-testable.</p>
 */
public enum VersionFreshness {
    /** On the newest public release, or ahead of it (a dev build). */
    LATEST("🟢"),
    /** Not the newest, but built under {@link #RECENT_DAYS} days ago. */
    RECENT("🔵"),
    /** Built within the last week. */
    WEEK("🟡"),
    /** Built within the last month. */
    MONTH("🟠"),
    /** Older than a month. */
    OLDER("🔴"),
    /** Neither the newest release nor this build's date could be read. */
    UNKNOWN("⚪");

    static final int RECENT_DAYS = 2;
    static final int WEEK_DAYS = 7;
    static final int MONTH_DAYS = 30;

    /** A version this class is willing to compare: at least {@code major.minor}, both numeric. */
    private static final Pattern COMPARABLE = Pattern.compile("[vV]?\\d+\\.\\d+.*");

    private final String dot;

    VersionFreshness(String dot) {
        this.dot = dot;
    }

    public String dot() {
        return dot;
    }

    /**
     * @param running   the version this jar is
     * @param latest    the newest public release, blank/{@code null} when it is not known
     * @param buildDate the day this build landed, {@code yyyy-MM-dd}, blank when unknown
     * @param today     the day the answer is being posted
     */
    public static VersionFreshness classify(String running, String latest, String buildDate, LocalDate today) {
        // SemverCompare answers "equal" for anything it cannot parse, which would read as LATEST —
        // so only ask it about versions that are comparable in the first place.
        if (isComparable(running) && isComparable(latest) && SemverCompare.compare(running, latest) >= 0) {
            return LATEST;
        }
        LocalDate built = parseDay(buildDate);
        if (built == null || today == null) {
            return UNKNOWN;
        }
        // A build dated in the future is a clock disagreement, not an old build.
        long days = Math.max(0L, ChronoUnit.DAYS.between(built, today));
        if (days < RECENT_DAYS) return RECENT;
        if (days <= WEEK_DAYS) return WEEK;
        if (days <= MONTH_DAYS) return MONTH;
        return OLDER;
    }

    private static boolean isComparable(String version) {
        return version != null && COMPARABLE.matcher(version.strip()).matches();
    }

    private static LocalDate parseDay(String day) {
        if (day == null || day.isBlank()) return null;
        try {
            return LocalDate.parse(day.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
