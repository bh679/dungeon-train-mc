package games.brennan.dungeontrain.portal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a dimensional carriage does at its walls — id round-trip, total parsing, and the per-mode rules. */
final class DimensionalCarriageModeTest {

    @Test
    @DisplayName("every mode round-trips through its id")
    void idRoundTrip() {
        for (DimensionalCarriageMode m : DimensionalCarriageMode.values()) {
            assertSame(m, DimensionalCarriageMode.parse(m.id()), m.id());
        }
    }

    @Test
    @DisplayName("ids are unique and lower-case — they are on-disk and command-line tokens")
    void idsAreUniqueTokens() {
        Set<String> seen = new HashSet<>();
        for (DimensionalCarriageMode m : DimensionalCarriageMode.values()) {
            assertTrue(seen.add(m.id()), "duplicate id " + m.id());
            assertEquals(m.id().toLowerCase(java.util.Locale.ROOT), m.id());
        }
    }

    @Test
    @DisplayName("parse is total: null, blank and nonsense all fall back to the default")
    void parseIsTotal() {
        assertSame(DimensionalCarriageMode.DEFAULT, DimensionalCarriageMode.parse(null));
        assertSame(DimensionalCarriageMode.DEFAULT, DimensionalCarriageMode.parse(""));
        assertSame(DimensionalCarriageMode.DEFAULT, DimensionalCarriageMode.parse("   "));
        // A hand-edited typo must stamp a normal sealed room, not fail the pair's stamp.
        assertSame(DimensionalCarriageMode.DEFAULT, DimensionalCarriageMode.parse("endles_open"));
    }

    @Test
    @DisplayName("parse tolerates case and surrounding whitespace")
    void parseNormalises() {
        assertSame(DimensionalCarriageMode.ENDLESS_OPEN, DimensionalCarriageMode.parse("  Endless_Open "));
        assertSame(DimensionalCarriageMode.BEDROCK_LOCK, DimensionalCarriageMode.parse("BEDROCK_LOCK"));
    }

    @Test
    @DisplayName("the default is the sealed mode — an unset variant behaves as it did before modes existed")
    void defaultIsBedrockLock() {
        assertSame(DimensionalCarriageMode.BEDROCK_LOCK, DimensionalCarriageMode.DEFAULT);
        assertFalse(DimensionalCarriageMode.BEDROCK_LOCK.tiles());
    }

    @Test
    @DisplayName("both endless modes tile; only Endless Repetition carries the whole room")
    void tilingRules() {
        assertTrue(DimensionalCarriageMode.ENDLESS_REPETITION.tiles());
        assertTrue(DimensionalCarriageMode.ENDLESS_OPEN.tiles());
        assertTrue(DimensionalCarriageMode.ENDLESS_REPETITION.tilesWholeRoom());
        assertFalse(DimensionalCarriageMode.ENDLESS_OPEN.tilesWholeRoom());
    }

    @Test
    @DisplayName("Endless Open is the one mode that leaves outer faces open — that is what 'open' means")
    void onlyEndlessOpenLeavesFacesOpen() {
        assertFalse(DimensionalCarriageMode.ENDLESS_OPEN.closesOuterFaces());
        assertTrue(DimensionalCarriageMode.ENDLESS_REPETITION.closesOuterFaces());
        assertTrue(DimensionalCarriageMode.BEDROCK_LOCK.closesOuterFaces());
    }

    @Test
    @DisplayName("Bedrockless neither tiles nor seals — it is the sealed mode with the seal removed")
    void bedrocklessRepeatsNothing() {
        assertSame(DimensionalCarriageMode.BEDROCKLESS, DimensionalCarriageMode.parse("bedrockless"));
        assertFalse(DimensionalCarriageMode.BEDROCKLESS.tiles());
        assertFalse(DimensionalCarriageMode.BEDROCKLESS.tilesWholeRoom());
        assertTrue(DimensionalCarriageMode.BEDROCKLESS.clearsSurroundings());
    }

    @Test
    @DisplayName("clearing the surroundings is Bedrockless alone — no other mode writes or sweeps a boundary")
    void onlyBedrocklessClears() {
        for (DimensionalCarriageMode m : DimensionalCarriageMode.values()) {
            assertEquals(m == DimensionalCarriageMode.BEDROCKLESS, m.clearsSurroundings(), m.id());
        }
    }

    /**
     * Stated per mode rather than as {@code tiles() || BEDROCKLESS}, which would only restate the
     * implementation. Fog used to be gated on {@code tiles()}, and the point of {@code fogs()} being
     * its own question is that a mode added later has to answer it deliberately — a test that derives
     * the answer would let the next one through.
     */
    @Test
    @DisplayName("every mode with an edge to hide fogs; Bedrock Lock, which has none, does not")
    void fogging() {
        assertFalse(DimensionalCarriageMode.BEDROCK_LOCK.fogs());
        assertTrue(DimensionalCarriageMode.ENDLESS_REPETITION.fogs());
        assertTrue(DimensionalCarriageMode.ENDLESS_OPEN.fogs());
        assertTrue(DimensionalCarriageMode.BEDROCKLESS.fogs());
        assertEquals(4, DimensionalCarriageMode.values().length,
            "a new mode must decide for itself whether it fogs — add it to this test");
    }

    @Test
    @DisplayName("next() cycles through every mode and returns to where it started")
    void nextCycles() {
        DimensionalCarriageMode start = DimensionalCarriageMode.DEFAULT;
        Set<DimensionalCarriageMode> visited = new HashSet<>();
        DimensionalCarriageMode m = start;
        for (int i = 0; i < DimensionalCarriageMode.values().length; i++) {
            assertTrue(visited.add(m), "next() revisited " + m + " before covering every mode");
            assertNotEquals(m, m.next());
            m = m.next();
        }
        assertSame(start, m);
        assertEquals(DimensionalCarriageMode.values().length, visited.size());
    }
}
