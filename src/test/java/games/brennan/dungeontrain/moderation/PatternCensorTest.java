package games.brennan.dungeontrain.moderation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Links and phone numbers are hidden; version strings, coordinates, times and dates are not. */
final class PatternCensorTest {

    private static String c(String s) {
        return PatternCensor.censor(s).text();
    }

    @Test
    @DisplayName("URLs with a scheme or www, bare domains, invites and IPs are masked")
    void links() {
        assertEquals("see ****", c("see https://example.com/path?x=1"));
        assertEquals("see **** now", c("see www.example.com now"));
        assertEquals("join **** pls", c("join discord.gg/abc123 pls"));
        assertEquals("go to ****.", c("go to example.com."));
        assertEquals("ip ****", c("ip 192.168.1.1:25565"));
        assertEquals("**** and ****", c("youtube.com/watch?v=x and http://a.b.co"));
        assertEquals("LINK", PatternCensor.censor("example.com").hits().get(0));
    }

    @Test
    @DisplayName("versions, abbreviations, decimals and file-ish words are not links")
    void notLinks() {
        for (String ok : new String[] {"v0.1286.0", "1.21.1", "e.g. this", "i.e.", "3.5 hearts", "x 1234 z -567",
                                       "dungeontrain.mixins.json", "user@example", "come to the shop", "re:invent"}) {
            assertFalse(PatternCensor.censor(ok).changed(), ok);
        }
    }

    @Test
    @DisplayName("phone numbers in common formats are masked")
    void phones() {
        assertEquals("call ****", c("call +61 412 345 678"));
        assertEquals("call **** now", c("call (555) 123-4567 now"));
        assertEquals("call ****", c("call 0412345678"));
        assertEquals("call ****", c("call 555-123-4567"));
        assertEquals("call ****", c("call 04 1234 5678"));
        assertEquals("PHONE", PatternCensor.censor("0412345678").hits().get(0));
    }

    @Test
    @DisplayName("dates, times, coordinates, scores and short numbers are not phone numbers")
    void notPhones() {
        for (String ok : new String[] {"2026-10-14", "14/10/2026", "at 12:30", "x 1234 z -567", "1234-567", "level 42",
                                       "seed 123456", "12 34 56", "100000", "scored 1500 - 200"}) {
            assertFalse(PatternCensor.censor(ok).changed(), ok);
        }
    }

    @Test
    @DisplayName("while typing, a match at the end of the text waits for a boundary")
    void tail() {
        assertFalse(PatternCensor.censorTail("see example.com").changed());
        assertEquals("see **** ", PatternCensor.censorTail("see example.com ").text());
        assertFalse(PatternCensor.censorTail("call 0412345678").changed());
        assertEquals("call **** ", PatternCensor.censorTail("call 0412345678 ").text());
    }

    @Test
    @DisplayName("idempotent and safe on empty input")
    void edges() {
        String once = c("see example.com and call 0412345678");
        assertEquals(once, c(once));
        assertFalse(PatternCensor.censor("").changed());
        assertEquals("", PatternCensor.censor(null).text());
        assertTrue(PatternCensor.censor("example.com 0412345678").hits().size() == 2);
    }
}
