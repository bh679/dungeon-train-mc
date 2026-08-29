package games.brennan.dungeontrain.client.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PanelSpaceMapping} — the conversion between an editor panel's own
 * coordinates and screen pixels.
 *
 * <p>This is the piece worth pinning because it fails <em>silently</em>. The panels' layout and
 * hit-testing are shared verbatim between the world-space and screen-space paths, so the only way
 * the two can disagree about where a row is, is if this mapping and the transform the renderer
 * pushes are not exact inverses. Nothing crashes when they aren't: rows just highlight in one
 * place and act in another, off by an amount that grows with distance from the panel's centre.</p>
 *
 * <p>Headless on purpose — the arithmetic needs no client, and the render pass it feeds cannot be
 * unit-tested at all, so isolating the part that can be is the whole point of the split.</p>
 */
final class PanelSpaceMappingTest {

    private static final double EPS = 1.0e-9;

    /** A 1920x1080 window, the X menu's hotbar reserve, and a panel that fits comfortably. */
    private static PanelSpaceMapping fitting() {
        return PanelSpaceMapping.fit(1920, 1080, 48, 2.6, 3.9, 1.0 / 0.012);
    }

    @Test
    @DisplayName("screen->local->screen round-trips exactly")
    void roundTripsThroughLocal() {
        PanelSpaceMapping m = fitting();
        for (double x : new double[] {0, 17, 640, 960, 1903}) {
            assertEquals(x, m.screenX(m.localX(x)), EPS, "x=" + x);
        }
        for (double y : new double[] {0, 23, 400, 516, 1031}) {
            assertEquals(y, m.screenY(m.localY(y)), EPS, "y=" + y);
        }
    }

    @Test
    @DisplayName("the panel centre is the local origin")
    void centreIsOrigin() {
        PanelSpaceMapping m = fitting();
        assertEquals(0.0, m.localX(m.centreX()), EPS);
        assertEquals(0.0, m.localY(m.centreY()), EPS);
    }

    @Test
    @DisplayName("local y grows upward while screen y grows downward")
    void yIsFlipped() {
        PanelSpaceMapping m = fitting();
        // A point above the centre on screen (smaller pixel y) must be positive in panel units.
        assertTrue(m.localY(m.centreY() - 100) > 0, "above centre should be +y");
        assertTrue(m.localY(m.centreY() + 100) < 0, "below centre should be -y");
        // ...and x is not flipped.
        assertTrue(m.localX(m.centreX() + 100) > 0, "right of centre should be +x");
    }

    @Test
    @DisplayName("a panel that fits keeps its preferred scale")
    void keepsPreferredScaleWhenItFits() {
        double preferred = 1.0 / 0.012;
        assertEquals(preferred, fitting().pxPerUnit(), EPS);
    }

    @Test
    @DisplayName("an oversized panel is scaled down to fit, never clipped")
    void shrinksToFit() {
        // 40 units wide is far more than 1920px can hold at the preferred ~83px per unit.
        PanelSpaceMapping m = PanelSpaceMapping.fit(1920, 1080, 48, 40.0, 3.0, 1.0 / 0.012);
        assertTrue(m.pxPerUnit() < 1.0 / 0.012, "should have shrunk");
        double halfWidthPx = 20.0 * m.pxPerUnit();
        assertTrue(m.centreX() - halfWidthPx >= PanelSpaceMapping.MARGIN - EPS,
            "left edge must stay inside the margin");
        assertTrue(m.centreX() + halfWidthPx <= 1920 - PanelSpaceMapping.MARGIN + EPS,
            "right edge must stay inside the margin");
    }

    @Test
    @DisplayName("the vertical budget stops short of the hotbar")
    void reservesTheHotbar() {
        int reserve = 48;
        PanelSpaceMapping m = PanelSpaceMapping.fit(1920, 1080, reserve, 2.0, 60.0, 1.0 / 0.012);
        double halfHeightPx = 30.0 * m.pxPerUnit();
        assertTrue(m.centreY() + halfHeightPx <= 1080 - reserve - PanelSpaceMapping.MARGIN + EPS,
            "bottom edge must clear the hotbar");
    }

    @Test
    @DisplayName("a degenerate panel or window does not divide by zero")
    void survivesDegenerateInput() {
        PanelSpaceMapping m = PanelSpaceMapping.fit(0, 0, 48, 0.0, 0.0, 1.0 / 0.012);
        assertTrue(Double.isFinite(m.pxPerUnit()) && m.pxPerUnit() > 0);
        assertTrue(Double.isFinite(m.localX(10)) && Double.isFinite(m.localY(10)));
    }
}
