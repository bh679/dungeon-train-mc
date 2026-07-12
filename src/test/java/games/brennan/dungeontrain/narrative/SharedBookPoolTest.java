package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises {@link SharedBookPool#langParam} — the {@code /books/pool} language query fragment used for
 * language-matched delivery. A blank/null locale must emit nothing so an older-relay / no-host world
 * keeps the unfiltered behaviour. The pool's snapshot/roll logic is unchanged by this feature (filtering
 * is relay-side), so it needs no new coverage here.
 */
final class SharedBookPoolTest {

    @Test
    @DisplayName("langParam: emits &lang= for a real locale, nothing for blank/null (back-compat)")
    void langParam() {
        assertEquals("&lang=en_us", SharedBookPool.langParam("en_us"));
        assertEquals("&lang=pt_br", SharedBookPool.langParam("pt_br"));
        assertEquals("", SharedBookPool.langParam(""), "blank → no param (relay stays unfiltered)");
        assertEquals("", SharedBookPool.langParam("   "));
        assertEquals("", SharedBookPool.langParam(null));
    }
}
