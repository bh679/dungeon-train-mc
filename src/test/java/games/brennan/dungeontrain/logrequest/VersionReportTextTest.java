package games.brennan.dungeontrain.logrequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionReportTextTest {

    private static final String ID = "aB3dE5gH7jK9mN1pQ3sT5v";

    @Test
    @DisplayName("/version and /version full parse, with the relay id kept case-sensitive")
    void parse() {
        VersionReportText.Command c = VersionReportText.parse("/version #" + ID);
        assertFalse(c.full());
        assertEquals(ID, c.reqId());
        c = VersionReportText.parse("  /VERSION Full #" + ID + " ");
        assertTrue(c.full());
        assertEquals(ID, c.reqId());
        assertNull(VersionReportText.parse("/version").reqId());
        assertTrue(VersionReportText.parse("!version full").full());
    }

    @Test
    @DisplayName("anything else is not the command — including a too-short id")
    void notCommand() {
        assertNull(VersionReportText.parse(null));
        assertNull(VersionReportText.parse("/bug"));
        assertNull(VersionReportText.parse("what version are you on?"));
        assertNull(VersionReportText.parse("/version please"));
        assertNull(VersionReportText.parse("/version #short"));
    }

    @Test
    @DisplayName("the short line names the version and branch, dropping an unknown branch")
    void shortLine() {
        assertEquals("Dungeon Train 0.1288.0 (main)", VersionReportText.shortLine("0.1288.0", "main"));
        assertEquals("Dungeon Train 0.1288.0", VersionReportText.shortLine("0.1288.0", "?"));
        assertEquals("Dungeon Train ?", VersionReportText.shortLine("", null));
    }

    @Test
    @DisplayName("the full report lists every mod, and trims the mod list to stay under the cap")
    void full() {
        String small = VersionReportText.full("1.0", "main", "Java: 21", List.of("a@1", "b@2"));
        assertEquals("Dungeon Train 1.0 (main)\nJava: 21\nMods (2):\na@1\nb@2", small);

        List<String> many = new ArrayList<>();
        for (int i = 0; i < 3000; i++) many.add("somemod" + i + "@1.2.3");
        String big = VersionReportText.full("1.0", "main", "", many);
        assertTrue(big.length() <= VersionReportText.MAX_CHARS);
        assertTrue(big.contains("more"));
        assertTrue(big.startsWith("Dungeon Train 1.0 (main)\nMods (3000):"));
    }
}
