package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.client.support.RewardTimeLeft.Kind;
import games.brennan.dungeontrain.client.support.RewardTimeLeft.Left;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** An active reward's end: seconds, minutes, hours, "tomorrow", then a date. */
class RewardTimeLeftTest {

    private static final ZoneId PERTH = ZoneId.of("Australia/Perth");
    private static final long S = 1000L, M = 60 * S, H = 60 * M;
    /** 26 Oct 2026, 18:00 in Perth. */
    private static final long NOW = ZonedDateTime.of(2026, 10, 26, 18, 0, 0, 0, PERTH).toInstant().toEpochMilli();

    private static Left left(long ms) {
        return RewardTimeLeft.of(NOW + ms, NOW, PERTH);
    }

    @Test
    void underAMinuteIsSecondsNeverZero() {
        assertEquals(new Left(Kind.SECONDS, 42), left(42 * S + 900));
        assertEquals(new Left(Kind.SECONDS, 59), left(59 * S + 999));
        assertEquals(new Left(Kind.SECONDS, 1), left(300));
    }

    @Test
    void underAnHourIsWholeMinutes() {
        assertEquals(new Left(Kind.MINUTES, 1), left(60 * S));
        assertEquals(new Left(Kind.MINUTES, 59), left(59 * M + 59 * S));
    }

    @Test
    void underADayIsWholeHours() {
        assertEquals(new Left(Kind.HOURS, 1), left(H));
        assertEquals(new Left(Kind.HOURS, 12), left(12 * H));
        assertEquals(new Left(Kind.HOURS, 23), left(23 * H + 59 * M));
    }

    @Test
    void aDayOrMoreAwayButTomorrowIsTomorrowElseADate() {
        // 18:00 now: tomorrow ends at midnight, 30 h away; tomorrow 23:59 is 29 h 59 away.
        assertEquals(new Left(Kind.TOMORROW, 0), left(24 * H));
        assertEquals(new Left(Kind.TOMORROW, 0), left(29 * H + 59 * M));
        assertEquals(new Left(Kind.DATE, 0), left(30 * H));
        assertEquals(new Left(Kind.DATE, 0), left(48 * H));
    }

    @Test
    void tomorrowIsTheViewersCalendarDay() {
        // The same end, 25 h away, is tomorrow in Perth (UTC+8) but two days on in Tonga (UTC+13, where now is 23:00).
        long until = NOW + 25 * H;
        assertEquals(Kind.TOMORROW, RewardTimeLeft.of(until, NOW, PERTH).kind());
        assertEquals(Kind.DATE, RewardTimeLeft.of(until, NOW, ZoneId.of("Pacific/Tongatapu")).kind());
    }

    @Test
    void countedKindsHaveAKeyTheOthersDoNot() {
        assertEquals("gui.dungeontrain.rewards.status.on_for_hours", RewardTimeLeft.countKey(Kind.HOURS));
        assertEquals("gui.dungeontrain.rewards.status.on_for_seconds", RewardTimeLeft.countKey(Kind.SECONDS));
        assertNull(RewardTimeLeft.countKey(Kind.TOMORROW));
        assertNull(RewardTimeLeft.countKey(Kind.DATE));
    }
}
