package games.brennan.dungeontrain.client.skybox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the sunk zone's sky plane is laid: chunk-aligned runs of sunk columns across the view, and
 * nothing outside the zone — the plane over a non-sunk column would cut real peaks out of the sky.
 */
final class SunkLidSkyPlaneTest {

    private static final int REACH = 160;

    @Test
    @DisplayName("no zone in view: no plane")
    void noZone() {
        assertTrue(SunkLidSkyPlane.spans(1000, REACH, x -> false).isEmpty());
    }

    @Test
    @DisplayName("zone covering the whole view: one span past both ends of the reach")
    void fullCover() {
        List<SunkLidSkyPlane.Span> spans = SunkLidSkyPlane.spans(1000, REACH, x -> true);
        assertEquals(1, spans.size());
        assertTrue(spans.get(0).minX() <= 1000 - REACH);
        assertTrue(spans.get(0).maxX() >= 1000 + REACH);
    }

    @Test
    @DisplayName("zone edge in view: the span stops on the chunk boundary where the zone ends")
    void edge() {
        List<SunkLidSkyPlane.Span> spans = SunkLidSkyPlane.spans(1000, REACH, x -> x < 1024);
        assertEquals(1, spans.size());
        assertEquals(1024, spans.get(0).maxX());
        assertTrue(spans.get(0).minX() <= 1000 - REACH);
    }

    @Test
    @DisplayName("two separate stretches: two spans, the gap left open")
    void twoRuns() {
        List<SunkLidSkyPlane.Span> spans = SunkLidSkyPlane.spans(0, REACH, x -> x < -32 || x >= 64);
        assertEquals(2, spans.size());
        assertEquals(-32, spans.get(0).maxX());
        assertEquals(64, spans.get(1).minX());
    }
}
