package games.brennan.dungeontrain.client.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * How an active Community Reward's end reads on its card: close ends as time left, a next-day end as
 * "tomorrow", anything later as a date. Under a minute: seconds; under an hour: minutes; under a day:
 * hours (all rounded down, so the line never promises more than is left); ending tomorrow (the next
 * calendar day in the viewer's zone) a day or more away: tomorrow; else the date. Pure, so the
 * boundaries are pinned by tests.
 */
public final class RewardTimeLeft {

    /** Which wording the line takes. */
    public enum Kind { SECONDS, MINUTES, HOURS, TOMORROW, DATE }

    /** The wording and, for SECONDS / MINUTES / HOURS, the count to show (at least 1). */
    public record Left(Kind kind, long count) {}

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    private RewardTimeLeft() {}

    /** What is left of a reward on until {@code untilMs}, seen at {@code nowMs} in {@code zone}. */
    public static Left of(long untilMs, long nowMs, ZoneId zone) {
        long left = Math.max(0, untilMs - nowMs);
        if (left < MINUTE) return new Left(Kind.SECONDS, Math.max(1, left / SECOND));
        if (left < HOUR) return new Left(Kind.MINUTES, left / MINUTE);
        if (left < DAY) return new Left(Kind.HOURS, left / HOUR);
        LocalDate today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        LocalDate end = Instant.ofEpochMilli(untilMs).atZone(zone).toLocalDate();
        return end.equals(today.plusDays(1)) ? new Left(Kind.TOMORROW, 0) : new Left(Kind.DATE, 0);
    }

    /** The lang key base for a counted kind ({@code .one} / {@code .other} … is appended), else null. */
    public static String countKey(Kind kind) {
        return switch (kind) {
            case SECONDS -> "gui.dungeontrain.rewards.status.on_for_seconds";
            case MINUTES -> "gui.dungeontrain.rewards.status.on_for_minutes";
            case HOURS -> "gui.dungeontrain.rewards.status.on_for_hours";
            default -> null;
        };
    }
}
