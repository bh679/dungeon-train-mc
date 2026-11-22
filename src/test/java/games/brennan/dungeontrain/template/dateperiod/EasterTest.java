package games.brennan.dungeontrain.template.dateperiod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class EasterTest {

    @ParameterizedTest(name = "{0}")
    @DisplayName("Western Easter Sunday matches the published dates")
    @CsvSource({
        "2024-03-31", "2025-04-20", "2026-04-05", "2027-03-28", "2028-04-16", "2029-04-01",
        "2030-04-21", "2031-04-13", "2032-03-28", "2033-04-17", "2034-04-09", "2035-03-25", "2036-04-13",
        "2038-04-25", "2285-03-22"
    })
    void matchesKnownDates(String iso) {
        LocalDate expected = LocalDate.parse(iso);
        assertEquals(expected, Easter.sunday(expected.getYear()));
    }
}
