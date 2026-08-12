package games.brennan.dungeontrain.client.localization.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Splitting a pool response into what this build may APPLY and what the editor should KNOW ABOUT.
 * The two differ on purpose: the editor always runs off the latest, whatever this client may show.
 */
class ApprovedPoolTest {

    private static TranslationEdits all() {
        return new TranslationEdits("de_de",
            Map.of("old.key", "Alt", "new.key", "Neu"),
            Map.of("random_books/x#title", "Titel"));
    }

    @Test
    @DisplayName("only translations written for this build or earlier are applied")
    void gatesTheAppliedSet() {
        ApprovedPool pool = ApprovedPool.gate(all(),
            Map.of("old.key", "0.550.0", "new.key", "0.560.0"),
            Map.of("random_books/x#title", "0.551.15"),
            "0.551.15", List.of());
        assertEquals(Map.of("old.key", "Alt"), pool.applicable().lang());
        assertEquals(Map.of("random_books/x#title", "Titel"), pool.applicable().books());
    }

    @Test
    @DisplayName("the editor's set keeps everything, including what this build may not show")
    void keepsEverythingForTheEditor() {
        ApprovedPool pool = ApprovedPool.gate(all(),
            Map.of("old.key", "0.550.0", "new.key", "0.560.0"), Map.of(), "0.551.15", List.of());
        assertEquals(2, pool.all().lang().size());
        assertTrue(pool.all().lang().containsKey("new.key"));
        assertFalse(pool.applicable().lang().containsKey("new.key"));
    }

    @Test
    @DisplayName("a relay that sends no versions applies everything, exactly as before the gate")
    void unversionedPoolIsUnchanged() {
        ApprovedPool pool = ApprovedPool.gate(all(), Map.of(), Map.of(), "0.551.15", List.of());
        assertEquals(pool.all().lang(), pool.applicable().lang());
        assertEquals(pool.all().books(), pool.applicable().books());
    }

    @Test
    @DisplayName("contributors are defensively copied and never null")
    void contributorsAreSafe() {
        assertEquals(List.of(), new ApprovedPool(all(), all(), null).contributors());
        ApprovedPool pool = new ApprovedPool(all(), all(), List.of("Klara"));
        assertThrows(UnsupportedOperationException.class, () -> pool.contributors().add("Mallory"));
    }

    @Test
    @DisplayName("an empty pool has both halves present, so no caller has to null-check one")
    void emptyHasBothHalves() {
        ApprovedPool empty = ApprovedPool.empty("de_de");
        assertTrue(empty.all().isEmpty());
        assertTrue(empty.applicable().isEmpty());
        assertEquals(List.of(), empty.contributors());
    }
}
