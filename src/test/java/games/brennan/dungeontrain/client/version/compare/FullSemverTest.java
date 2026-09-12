package games.brennan.dungeontrain.client.version.compare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FullSemverTest {

    @Test
    @DisplayName("strict X.Y.Z, with or without a leading v")
    void parse() {
        assertEquals(new FullSemver(0, 849, 0), FullSemver.parse("0.849.0").orElseThrow());
        assertEquals(new FullSemver(0, 849, 0), FullSemver.parse("v0.849.0").orElseThrow());
        assertEquals(new FullSemver(1, 2, 3), FullSemver.parse(" 1.2.3 ").orElseThrow());
        assertTrue(FullSemver.parse("0.849").isEmpty());
        assertTrue(FullSemver.parse("0.849.0-beta").isEmpty());
        assertTrue(FullSemver.parse("latest").isEmpty());
        assertTrue(FullSemver.parse(null).isEmpty());
    }

    @Test
    @DisplayName("patch counts — unlike the badge comparator")
    void compare() {
        FullSemver a = FullSemver.parse("0.849.0").orElseThrow();
        FullSemver b = FullSemver.parse("0.849.1").orElseThrow();
        FullSemver c = FullSemver.parse("0.850.0").orElseThrow();
        assertTrue(b.isNewerThan(a));
        assertTrue(c.isNewerThan(b));
        assertTrue(!a.isNewerThan(a));
        assertEquals("0.849.0", a.toString());
    }
}
