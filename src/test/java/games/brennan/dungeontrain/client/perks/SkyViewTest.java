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
        assertEquals(v.zoom() * 1.25, z.zoom(), 1e-9);
        assertEquals(sx, z.toSkyX(mx, 960), 1e-6);
        assertEquals(sy, z.toSkyY(my, 540), 1e-6);
    }

    @Test
    @DisplayName("zoom never goes below fit or above the maximum")
    void zoomClamp() {
        SkyView v = SkyView.fit(960, 540);
        assertEquals(v.zoom(), v.zoomedAt(0.5, 480, 270, 960, 540).zoom(), 1e-9);
        SkyView big = v;
        for (int i = 0; i < 20; i++) big = big.zoomedAt(1.25, 480, 270, 960, 540);
        assertEquals(SkyView.MAX_ZOOM, big.zoom(), 1e-9);
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
