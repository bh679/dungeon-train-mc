package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkyViewTest {

    @Test
    @DisplayName("the fit zoom is the design doc's table: height minus the header over 800")
    void fitZoom() {
        assertEquals((540 - SkyView.HEADER) / 800.0, SkyView.fitZoom(960, 540), 1e-9);
        assertEquals((360 - SkyView.HEADER) / 800.0, SkyView.fitZoom(640, 360), 1e-9);
        assertEquals((240 - SkyView.HEADER) / 800.0, SkyView.fitZoom(426, 240), 1e-9);
        assertTrue(SkyView.fitZoom(960, 540) < 1.0, "names hide at fit zoom");
    }

    @Test
    @DisplayName("the fit view puts the Engine at the middle of the sky area")
    void fitCentres() {
        SkyView v = SkyView.fit(960, 540);
        assertEquals(480, v.toScreenX(PerkTree.CENTRE, 960), 1e-9);
        assertEquals(SkyView.HEADER + (540 - SkyView.HEADER) / 2.0, v.toScreenY(PerkTree.CENTRE, 540), 1e-9);
    }

    @Test
    @DisplayName("zooming around the cursor keeps the sky point under it still")
    void zoomAroundCursor() {
        SkyView v = SkyView.fit(960, 540);
        double mx = 700, my = 300;
        double sx = v.toSkyX(mx, 960), sy = v.toSkyY(my, 540);
        SkyView z = v.zoomedAt(1.25, mx, my, 960, 540);
        assertEquals(SkyView.zoomSteps(960, 540)[1], z.zoom(), 1e-9);
        assertEquals(sx, z.toSkyX(mx, 960), 1e-6);
        assertEquals(sy, z.toSkyY(my, 540), 1e-6);
    }

    @Test
    @DisplayName("zoom steps between exactly two levels: out and close")
    void twoSteps() {
        SkyView v = SkyView.fit(960, 540);
        double fit = v.zoom(), close = (fit + SkyView.MAX_ZOOM) / 2;
        assertEquals(fit, v.zoomedAt(0.8, 480, 270, 960, 540).zoom(), 1e-9, "already fully out");
        SkyView in = v.zoomedAt(1.25, 480, 270, 960, 540);
        assertEquals(close, in.zoom(), 1e-9);
        assertEquals(close, in.zoomedAt(1.25, 480, 270, 960, 540).zoom(), 1e-9, "already fully in");
        assertEquals(fit, in.zoomedAt(0.8, 480, 270, 960, 540).zoom(), 1e-9);
        assertEquals(2, SkyView.zoomSteps(426, 240).length);
    }

    @Test
    @DisplayName("panning stops with a quarter of the sky still on screen")
    void panClamp() {
        SkyView v = new SkyView(1.0, PerkTree.CENTRE, PerkTree.CENTRE).panned(-5000, -5000, 640, 360);
        assertTrue(v.centreX() <= 800 - 200 + 320, "right edge clamp");
        assertTrue(v.centreY() <= 800 - 200 + 168, "bottom edge clamp");
        SkyView w = new SkyView(1.0, PerkTree.CENTRE, PerkTree.CENTRE).panned(5000, 5000, 640, 360);
        assertTrue(w.centreX() >= 200 - 320, "left edge clamp");
    }
}
