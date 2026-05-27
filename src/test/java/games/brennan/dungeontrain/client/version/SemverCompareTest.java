package games.brennan.dungeontrain.client.version;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Confirms {@link SemverCompare#compare(String, String)} only looks at
 * the first two dot-segments — patch and pre-release differences must
 * collapse to {@code 0} so auto-release patch ticks don't trigger
 * "Update available" notifications.
 */
final class SemverCompareTest {

    @ParameterizedTest(name = "compare({0}, {1}) == sign({2})")
    @CsvSource({
        // a, b, expected sign (-1, 0, +1)

        // identical
        "'v0.240.1','0.240.1',0",
        "'V0.240.1','v0.240.1',0",

        // patch differences collapse to 0 (the new behaviour we want)
        "'0.240.2','0.240.5',0",
        "'0.240.5','0.240.2',0",
        "'0.240.99','0.240.0',0",
        "'0.240.0','0.240.99',0",

        // pre-release suffixes collapse to 0 (no longer distinguished)
        "'0.240.1-rc1','0.240.1',0",
        "'0.240.1','0.240.1-rc1',0",
        "'0.240.1-rc1','0.240.1-rc2',0",
        "'0.240.1-rc2','0.240.1-rc1',0",
        "'0.240.0-alpha','0.240.99',0",

        // missing trailing segment treated as zero
        "'0.240','0.240.0',0",
        "'0.240.0','0.240',0",
        "'0.240','0.240.5',0",
        "'0.240.5','0.240',0",

        // minor differences DO matter
        "'0.240.99','0.241.0',-1",
        "'0.241.0','0.240.99',1",
        "'0.240.1','0.241.0',-1",
        "'0.241.0','0.240.1',1",

        // major differences DO matter
        "'1.0.0','2.0.0',-1",
        "'2.0.0','1.0.0',1",
        "'1.99.99','2.0.0',-1",

        // garbage input → 0 (fail-safe, never spam a false update)
        "'garbage','0.240.1',0",
        "'0.240.1','garbage',0"
    })
    void compare(String a, String b, int expected) {
        int actual = SemverCompare.compare(a, b);
        assertEquals(expected, Integer.signum(actual),
            "SemverCompare.compare(\"" + a + "\", \"" + b + "\") should be sign " + expected);
    }

    @Test
    @DisplayName("stripV removes leading v/V")
    void stripV() {
        assertEquals("0.240.1", SemverCompare.stripV("v0.240.1"));
        assertEquals("0.240.1", SemverCompare.stripV("V0.240.1"));
        assertEquals("0.240.1", SemverCompare.stripV("0.240.1"));
        assertEquals("", SemverCompare.stripV(""));
        assertEquals("", SemverCompare.stripV(null));
    }
}
