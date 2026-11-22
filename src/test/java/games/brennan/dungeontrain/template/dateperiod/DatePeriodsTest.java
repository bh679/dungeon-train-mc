package games.brennan.dungeontrain.template.dateperiod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundled {@code date_periods.json} and the window maths over it. */
final class DatePeriodsTest {

    private static boolean on(String id, String day) {
        return DatePeriods.get(id).orElseThrow().isOn(LocalDate.parse(day));
    }

    @Test
    @DisplayName("The bundled file loads all 22 periods, each in a known picker group")
    void loadsEveryPeriod() {
        List<DatePeriod> all = DatePeriods.all();
        assertEquals(22, all.size());
        for (DatePeriod p : all) assertTrue(DatePeriods.MONTH_GROUPS.contains(p.months()), p.id());
    }

    @Test
    @DisplayName("Every table period has a main day in every year 2026 to 2036 (Vesak: published to 2027)")
    void tableCoversTheDecade() {
        for (int year = 2026; year <= 2036; year++) {
            List<String> missing = DatePeriods.missingTableYears(year);
            if (year <= 2027) {
                assertTrue(missing.isEmpty(), year + " " + missing);
            } else {
                assertEquals(List.of("vesak"), missing, Integer.toString(year));
            }
        }
    }

    @Test
    @DisplayName("Fixed windows are inclusive at both ends")
    void fixedBoundaries() {
        assertFalse(on("christmas", "2026-12-17"));
        assertTrue(on("christmas", "2026-12-18"));
        assertTrue(on("christmas", "2026-12-26"));
        assertFalse(on("christmas", "2026-12-27"));
        assertTrue(on("halloween", "2026-10-31"));
        assertTrue(on("day_of_the_dead", "2026-10-31"));
    }

    @Test
    @DisplayName("Windows that cross New Year are on at both ends")
    void yearWrap() {
        assertTrue(on("new_year", "2026-12-31"));
        assertTrue(on("new_year", "2027-01-02"));
        assertFalse(on("new_year", "2027-01-03"));
        assertTrue(on("kwanzaa", "2027-01-01"));
        assertFalse(on("kwanzaa", "2026-12-25"));
    }

    @Test
    @DisplayName("Easter runs Good Friday to Easter Monday; Carnival ends on Shrove Tuesday")
    void easterRelative() {
        assertFalse(on("easter", "2026-04-02"));
        assertTrue(on("easter", "2026-04-03"));
        assertTrue(on("easter", "2026-04-06"));
        assertFalse(on("easter", "2026-04-07"));
        assertTrue(on("carnival", "2026-02-17"));   // Shrove Tuesday 2026
        assertFalse(on("carnival", "2026-02-18")); // Ash Wednesday
    }

    @Test
    @DisplayName("Table windows widen the main day; Eid al-Fitr falls twice in 2033")
    void tableWindows() {
        assertTrue(on("diwali", "2026-11-06"));
        assertTrue(on("diwali", "2026-11-10"));
        assertFalse(on("diwali", "2026-11-11"));
        assertTrue(on("hanukkah", "2026-12-04"));   // first candle, the evening before
        assertTrue(on("hanukkah", "2026-12-12"));
        assertFalse(on("hanukkah", "2026-12-13"));
        assertTrue(on("eid_al_fitr", "2033-01-03"));
        assertTrue(on("eid_al_fitr", "2033-12-23"));
        assertFalse(on("vesak", "2028-05-09"));      // off: no published date
    }

    @Test
    @DisplayName("activeOn lists overlapping periods together")
    void activeOverlap() {
        Set<String> active = DatePeriods.activeOn(LocalDate.parse("2026-12-26"));
        assertTrue(active.containsAll(Set.of("christmas", "kwanzaa")), active.toString());
        assertTrue(DatePeriods.activeOn(LocalDate.parse("2026-07-31")).isEmpty());
    }

    @Test
    @DisplayName("nextFrom returns the running window, else the next one")
    void nextWindow() {
        DatePeriod xmas = DatePeriods.get("christmas").orElseThrow();
        assertEquals(LocalDate.parse("2026-12-18"), xmas.nextFrom(LocalDate.parse("2026-11-22")).orElseThrow().from());
        assertEquals(LocalDate.parse("2026-12-18"), xmas.nextFrom(LocalDate.parse("2026-12-20")).orElseThrow().from());
        assertEquals(LocalDate.parse("2027-12-18"), xmas.nextFrom(LocalDate.parse("2026-12-27")).orElseThrow().from());
        assertTrue(DatePeriods.get("vesak").orElseThrow().nextFrom(LocalDate.parse("2028-01-01")).isEmpty());
    }
}
