package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The map-readout rewrites: shift what they recognise, pass everything else through untouched. */
class MapReadoutTextTest {

    private static final DebugScreenDepthLines.Shift ROOM = new DebugScreenDepthLines.Shift(-1200, 150, 37);

    @Test
    @DisplayName("Xaero cursor line: every figure moves, both Y figures by the Y shift")
    void shiftsTheFullCursorLine() {
        assertEquals("X: -1188 Y: 214 (220) Z: 32", MapReadoutText.xaeroCursor("X: 12 Y: 64 (70) Z: -5", ROOM));
    }

    @Test
    @DisplayName("Xaero cursor line without its optional Y parts")
    void shiftsTheShortCursorLines() {
        assertEquals("X: -1188 Z: 32", MapReadoutText.xaeroCursor("X: 12 Z: -5", ROOM));
        assertEquals("X: -1188 Y: 214 Z: 32", MapReadoutText.xaeroCursor("X: 12 Y: 64 Z: -5", ROOM));
    }

    @Test
    @DisplayName("any other world-map line is returned as the same object")
    void leavesOtherLinesAlone() {
        String biome = "Plains";
        String zoom = "1.5x";
        assertSame(biome, MapReadoutText.xaeroCursor(biome, ROOM));
        assertSame(zoom, MapReadoutText.xaeroCursor(zoom, ROOM));
    }

    @Test
    @DisplayName("no shift is the truth, unchanged")
    void noShiftIsIdentity() {
        String line = "X: 12 Y: 64 Z: -5";
        assertSame(line, MapReadoutText.xaeroCursor(line, DebugScreenDepthLines.Shift.NONE));
    }

    @Test
    @DisplayName("a Far Lands X past an int saturates rather than wrapping to a small number")
    void addSaturates() {
        assertEquals(Integer.MAX_VALUE, MapReadoutText.add(100, 5_000_000_000L));
        assertEquals(Integer.MIN_VALUE, MapReadoutText.add(-100, -5_000_000_000L));
        assertEquals(12_550_921, MapReadoutText.add(100, 12_550_821L));
    }

    @Test
    @DisplayName("JourneyMap's vertical section follows the Y, negatives included")
    void sectionOfY() {
        assertEquals(4, MapReadoutText.section(64));
        assertEquals(-1, MapReadoutText.section(-1));
        assertEquals(-7, MapReadoutText.section(-103));
    }

    @Test
    @DisplayName("a carriage plot X or Z comes in by 20.5 million")
    void fromPlot() {
        assertEquals(-18_973, MapReadoutText.fromPlot(20_481_027));
        assertEquals(-4_632, MapReadoutText.fromPlot(20_495_368));
        assertEquals(20_000_000 - 20_500_000, MapReadoutText.fromPlot(20_000_000));
    }
}
