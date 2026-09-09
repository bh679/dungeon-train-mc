package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pure tests for the TrainPhase bitmask + token helpers (phaseAt is covered by in-game tests). */
final class TrainPhaseTest {

    @Test
    @DisplayName("ALL_MASK has every phase bit set")
    void allMask() {
        assertEquals(0b111111, TrainPhase.ALL_MASK);
        assertEquals(EnumSet.allOf(TrainPhase.class), TrainPhase.fromMask(TrainPhase.ALL_MASK));
    }

    @Test
    @DisplayName("toMask / fromMask round-trip")
    void maskRoundTrip() {
        EnumSet<TrainPhase> set = EnumSet.of(TrainPhase.NETHER, TrainPhase.END);
        int mask = TrainPhase.toMask(set);
        assertEquals(TrainPhase.NETHER.bit() | TrainPhase.END.bit(), mask);
        assertEquals(set, TrainPhase.fromMask(mask));
    }

    @Test
    @DisplayName("byToken accepts lowercase names and the 'ow' alias")
    void byToken() {
        assertEquals(TrainPhase.OVERWORLD, TrainPhase.byToken("overworld"));
        assertEquals(TrainPhase.OVERWORLD, TrainPhase.byToken("ow"));
        assertEquals(TrainPhase.NETHER, TrainPhase.byToken("NETHER"));
        assertEquals(TrainPhase.VOID, TrainPhase.byToken("void"));
        assertEquals(TrainPhase.END, TrainPhase.byToken("end"));
        assertEquals(TrainPhase.UPSIDE_DOWN, TrainPhase.byToken("upside_down"));
        assertEquals(TrainPhase.UPSIDE_DOWN, TrainPhase.byToken("ud"));
        assertEquals(TrainPhase.UPSIDE_DOWN, TrainPhase.byToken("upsidedown"));
        assertEquals(TrainPhase.CHUNCKS, TrainPhase.byToken("chuncks"));
        assertEquals(TrainPhase.CHUNCKS, TrainPhase.byToken("CHUNCKS"));
        assertNull(TrainPhase.byToken("nonsense"));
        assertNull(TrainPhase.byToken(null));
    }

    @Test
    @DisplayName("letter() yields O N V E U C in ordinal order")
    void letters() {
        StringBuilder sb = new StringBuilder();
        for (TrainPhase p : TrainPhase.values()) sb.append(p.letter());
        assertEquals("ONVEUC", sb.toString());
    }

    @Test
    @DisplayName("displayName() is non-blank and distinct for every phase")
    void displayNames() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (TrainPhase p : TrainPhase.values()) {
            assertFalse(p.displayName().isBlank(), p.name());
            assertTrue(seen.add(p.displayName()), "duplicate label " + p.displayName());
        }
        assertEquals("Upside Down", TrainPhase.UPSIDE_DOWN.displayName());
    }
}
