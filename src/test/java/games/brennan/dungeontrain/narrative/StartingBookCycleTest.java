package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down the pure dimension-cycling helpers in {@link StartingBookFactory}:
 * the {@link StartingBookFactory#dimKey} key format (also the persisted form in
 * {@link PlayerPlayedMarker}) and the {@link StartingBookFactory#isExhausted}
 * predicate that drives the route / fall-through decision in
 * {@code StartingBookEvents.resolveLoginContext}. Pure — no registry or server.
 */
final class StartingBookCycleTest {

    @Test
    @DisplayName("dimKey is folder/basename#index and folder-namespaced")
    void dimKeyFormat() {
        assertEquals("nether/why_the_nether#0",
            StartingBookFactory.dimKey("nether", "why_the_nether", 0));
        assertEquals("end/begin_at_the_end#2",
            StartingBookFactory.dimKey("end", "begin_at_the_end", 2));
        // Same basename in two dimensions must never collide.
        assertNotEquals(StartingBookFactory.dimKey("nether", "x", 0),
            StartingBookFactory.dimKey("end", "x", 0));
    }

    @Test
    @DisplayName("isExhausted: nothing/partial seen → false; all keys seen → true")
    void exhaustionTransitions() {
        List<String> keys = List.of("nether/a#0", "nether/a#1", "nether/b#0");
        assertFalse(StartingBookFactory.isExhausted(keys, Set.of()),
            "nothing seen → unseen tuples remain");
        assertFalse(StartingBookFactory.isExhausted(keys, Set.of("nether/a#0", "nether/a#1")),
            "partial seen → still not exhausted");
        assertTrue(StartingBookFactory.isExhausted(keys, Set.of("nether/a#0", "nether/a#1", "nether/b#0")),
            "every key seen → exhausted");
        // Unrelated extra seen keys don't change the verdict.
        Set<String> withExtras =
            new LinkedHashSet<>(List.of("nether/a#0", "nether/a#1", "nether/b#0", "end/z#0"));
        assertTrue(StartingBookFactory.isExhausted(keys, withExtras));
    }

    @Test
    @DisplayName("isExhausted: empty pool counts as exhausted → caller falls through")
    void emptyPoolExhausted() {
        assertTrue(StartingBookFactory.isExhausted(List.of(), Set.of()),
            "no books authored → exhausted → fall through to lifecycle welcome");
        assertTrue(StartingBookFactory.isExhausted(List.of(), Set.of("nether/a#0")));
    }

    @Test
    @DisplayName("Nether and End exhaust independently")
    void dimensionsIndependent() {
        List<String> nether = List.of("nether/a#0", "nether/b#0");
        List<String> end = List.of("end/a#0");
        Set<String> seen = Set.of("nether/a#0", "nether/b#0"); // all Nether, no End
        assertTrue(StartingBookFactory.isExhausted(nether, seen), "Nether exhausted");
        assertFalse(StartingBookFactory.isExhausted(end, seen), "End still has an unseen book");
    }
}
