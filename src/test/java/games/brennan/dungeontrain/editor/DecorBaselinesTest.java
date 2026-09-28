package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DecorBaselines}: a plot's decoration is only judged while its entities can be seen, and a
 * baseline stamped out of sight is taken once they can.
 */
final class DecorBaselinesTest {

    private static final String KEY = "contents:pigs";

    @Test
    @DisplayName("a baseline taken in sight catches an added entity")
    void observableBaselineDetectsChange() {
        DecorBaselines<String> b = new DecorBaselines<>();
        b.capture(KEY, "region", true, () -> 10L);
        assertTrue(b.matches(KEY, true, () -> 10L));
        assertFalse(b.matches(KEY, true, () -> 11L));
        assertFalse(b.hasPending());
    }

    @Test
    @DisplayName("a plot out of sight is never judged — its entities vanish from the query")
    void unobservableMatches() {
        DecorBaselines<String> b = new DecorBaselines<>();
        b.capture(KEY, "region", true, () -> 10L);
        assertTrue(b.matches(KEY, false, () -> 0L));
    }

    @Test
    @DisplayName("a stamp out of sight holds the baseline pending instead of recording an empty plot")
    void stampOutOfSightIsPending() {
        DecorBaselines<String> b = new DecorBaselines<>();
        b.capture(KEY, "region", false, () -> 0L);
        assertTrue(b.hasPending());
        assertEquals("region", b.pending().get(KEY));
        // The mobs appear once the player walks up: the first sighting takes the baseline.
        assertTrue(b.matches(KEY, true, () -> 7L));
        assertFalse(b.hasPending());
        assertTrue(b.matches(KEY, true, () -> 7L));
        assertFalse(b.matches(KEY, true, () -> 8L));
    }

    @Test
    @DisplayName("the tick resolves a pending baseline, after which edits are caught")
    void resolveTakesBaseline() {
        DecorBaselines<String> b = new DecorBaselines<>();
        b.capture(KEY, "region", false, () -> 0L);
        b.resolve(KEY, 7L);
        assertFalse(b.hasPending());
        assertFalse(b.matches(KEY, true, () -> 9L));
    }

    @Test
    @DisplayName("a later capture in sight replaces a pending one; clear drops both")
    void captureAndClear() {
        DecorBaselines<String> b = new DecorBaselines<>();
        b.capture(KEY, "region", false, () -> 0L);
        b.capture(KEY, "region", true, () -> 3L);
        assertFalse(b.hasPending());
        assertFalse(b.matches(KEY, true, () -> 4L));
        b.capture("other", "r2", false, () -> 0L);
        b.clear(KEY);
        assertTrue(b.matches(KEY, true, () -> 4L));
        b.clearAll();
        assertFalse(b.hasPending());
    }
}
