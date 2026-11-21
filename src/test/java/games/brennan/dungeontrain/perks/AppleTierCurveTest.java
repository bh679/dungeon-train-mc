package games.brennan.dungeontrain.perks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AppleTierCurveTest {

    private static final double EPS = 1e-6;

    @Test
    @DisplayName("the Celestial Bite curve passes through its points and is flat outside them")
    void biteAnchors() {
        assertEquals(0, AppleTierCurve.BITE.at(0), EPS);
        assertEquals(0, AppleTierCurve.BITE.at(5), EPS);
        assertEquals(13.8, AppleTierCurve.BITE.at(41), EPS);
        assertEquals(25, AppleTierCurve.BITE.at(100), EPS);
        assertEquals(25, AppleTierCurve.BITE.at(1000), EPS);
    }

    @Test
    @DisplayName("the Violet Core curve passes through its points and is flat outside them")
    void coreAnchors() {
        assertEquals(0, AppleTierCurve.CORE.at(25), EPS);
        assertEquals(10, AppleTierCurve.CORE.at(150), EPS);
        assertEquals(10, AppleTierCurve.CORE.at(1000), EPS);
    }

    @Test
    @DisplayName("between its points the curve matches the editor's readout")
    void midpoints() {
        assertEquals(6.6, AppleTierCurve.BITE.at(25), 0.05);
        assertEquals(17.3, AppleTierCurve.BITE.at(50), 0.05);
        assertEquals(5.2, AppleTierCurve.CORE.at(100), 0.05);
        assertEquals(0.8, AppleTierCurve.CORE.at(50), 0.05);
    }

    @Test
    @DisplayName("basis points round the per-cent value")
    void basisPoints() {
        assertEquals(2500, AppleTierCurve.BITE.basisPointsAt(100));
        assertEquals(1000, AppleTierCurve.CORE.basisPointsAt(200));
        assertTrue(AppleTierCurve.CORE.basisPointsAt(24) == 0);
    }

    @Test
    @DisplayName("a segment whose handle leaves its x range is refused")
    void rejectsBackwardsHandle() {
        AppleTierCurve.Point a = new AppleTierCurve.Point(10, 0);
        AppleTierCurve.Point b = new AppleTierCurve.Point(20, 5);
        assertThrows(IllegalArgumentException.class, () -> new AppleTierCurve(List.of(
            new AppleTierCurve.Segment(a, new AppleTierCurve.Point(25, 0), b, b))));
        assertThrows(IllegalArgumentException.class, () -> new AppleTierCurve(List.of()));
    }
}
