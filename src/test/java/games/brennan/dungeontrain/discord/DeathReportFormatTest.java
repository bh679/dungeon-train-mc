package games.brennan.dungeontrain.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for the death-report field formatters. */
class DeathReportFormatTest {

    @Test
    void distanceRoundsToWholeMeters() {
        assertEquals("28 m", DeathReportFormat.distance(28.4));
        assertEquals("29 m", DeathReportFormat.distance(28.6));
        assertEquals("0 m", DeathReportFormat.distance(0.0));
        assertEquals("1234 m", DeathReportFormat.distance(1234.49));
    }

    @Test
    void timeFormatsMinutesAndSeconds() {
        assertEquals("0:29", DeathReportFormat.time(29L * 20L));
        assertEquals("1:05", DeathReportFormat.time(65L * 20L));
        assertEquals("0:00", DeathReportFormat.time(0L));
        assertEquals("0:00", DeathReportFormat.time(-100L)); // clamps
    }

    @Test
    void timeFormatsHoursPastAnHour() {
        assertEquals("1:00:00", DeathReportFormat.time(3600L * 20L));
        assertEquals("2:03:04", DeathReportFormat.time((2L * 3600L + 3L * 60L + 4L) * 20L));
    }

    @Test
    void damageRoundsAndAbbreviatesLargeTotals() {
        assertEquals("0", DeathReportFormat.damage(0.0));
        assertEquals("342", DeathReportFormat.damage(342.4));
        assertEquals("343", DeathReportFormat.damage(342.6));
        assertEquals("9,999", DeathReportFormat.damage(9_999.0)); // grouping separator
        assertEquals("10.0k", DeathReportFormat.damage(10_000.0)); // k threshold
        assertEquals("12.3k", DeathReportFormat.damage(12_345.0));
        assertEquals("1.0M", DeathReportFormat.damage(1_000_000.0)); // M threshold
        assertEquals("0", DeathReportFormat.damage(-5.0)); // clamps to zero
    }
}
