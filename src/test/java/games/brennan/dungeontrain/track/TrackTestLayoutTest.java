package games.brennan.dungeontrain.track;

import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins where a Tracks-category Test the Carriage puts everything: that the pieces sit where the
 * world would put them relative to each other, and that the box Back sweeps covers all of it.
 */
final class TrackTestLayoutTest {

    private static final int SPACING = TrackGenerator.computeSpacing(TrackTestLayout.COLUMN_HEIGHT);

    private static TrackTestLayout layout(int length, int height, int width) {
        return new TrackTestLayout(length, (length + 1) / 2, height, width, SPACING,
            TrackGenerator.computeThickness(SPACING));
    }

    private static final List<TrackTestLayout> LAYOUTS = List.of(
        layout(9, 7, 5), layout(4, 4, 3), layout(32, 24, 9));

    @Test
    @DisplayName("The layout's copies of the tile, tunnel and entrance sizes match the real ones")
    void mirroredConstants() {
        assertEquals(TrackPlacer.TILE_LENGTH, TrackTestLayout.TILE_LENGTH);
        assertEquals(TunnelPlacer.LENGTH, TrackTestLayout.TUNNEL_PIECE);
        assertEquals(TunnelPlacer.HEIGHT, TrackTestLayout.TUNNEL_HEIGHT);
        assertEquals(TunnelPlacer.WIDTH, TrackTestLayout.TUNNEL_WIDTH);
        assertEquals(PillarAdjunct.STAIRS_ENTRANCE.ySize() - 2, TrackTestLayout.ENTRANCE_RISE);
    }

    @Test
    @DisplayName("The carriage stands between its pads on open line, before any column's arch or the tunnel")
    void carriageOnOpenLine() {
        for (TrackTestLayout l : LAYOUTS) {
            assertTrue(l.backPadX() > 0, "track behind the back pad");
            assertEquals(l.backPadX() + l.halfPad(), l.carriageX());
            assertEquals(l.carriageX() + l.carriageLength(), l.frontPadX());
            assertTrue(l.frontPadX() + l.halfPad() <= l.tunnelX(), "carriage clear of the tunnel");
            assertEquals(0, l.openLength() % TrackTestLayout.TILE_LENGTH, "open line on the tile grid");
            assertEquals(TrackTestLayout.bedY() + 2, TrackTestLayout.trainY(), "carriage on the rails");
        }
    }

    @Test
    @DisplayName("Columns stand on the generator's spacing, with their arches inside the open line")
    void columnsInTheOpen() {
        int reach = TrackGenerator.archProfile(TrackTestLayout.COLUMN_HEIGHT).length;
        for (TrackTestLayout l : LAYOUTS) {
            List<Integer> centres = l.columnCentres();
            assertEquals(TrackTestLayout.COLUMNS, centres.size());
            for (int i = 1; i < centres.size(); i++) {
                assertEquals(SPACING, centres.get(i) - centres.get(i - 1));
            }
            assertTrue(l.columnMinX(centres.get(0)) - reach >= 0, "first arch inside the stretch");
            assertTrue(l.columnMaxX(centres.get(centres.size() - 1)) + reach < l.tunnelX(),
                "last arch clear of the tunnel");
            assertEquals(centres.get(1), l.stairsColumn(), "staircase beside the middle column");
        }
    }

    @Test
    @DisplayName("The tunnel is portal, sections, mirrored portal, back to back, ending the stretch")
    void tunnelPieces() {
        for (TrackTestLayout l : LAYOUTS) {
            List<TrackTestLayout.TunnelPiece> pieces = l.tunnelPieces();
            assertEquals(TrackTestLayout.TUNNEL_SECTIONS + 2, pieces.size());
            assertEquals(l.tunnelX(), pieces.get(0).x());
            for (int i = 1; i < pieces.size(); i++) {
                assertEquals(TrackTestLayout.TUNNEL_PIECE, pieces.get(i).x() - pieces.get(i - 1).x());
            }
            TrackTestLayout.TunnelPiece first = pieces.get(0);
            TrackTestLayout.TunnelPiece last = pieces.get(pieces.size() - 1);
            assertTrue(first.portal() && !first.mirrored());
            assertTrue(last.portal() && last.mirrored());
            for (TrackTestLayout.TunnelPiece p : pieces.subList(1, pieces.size() - 1)) assertFalse(p.portal());
            assertEquals(l.stretchLength(), last.x() + TrackTestLayout.TUNNEL_PIECE);
            assertEquals(2 * l.stretchLength(), l.sceneLength());
        }
    }

    @Test
    @DisplayName("The down-stairs shaft rises through the first section, clear of both portals")
    void downStairsInASection() {
        for (TrackTestLayout l : LAYOUTS) {
            int section = l.tunnelPieces().get(1).x();
            int shaftMin = TrackGenerator.downStairsOriginX(l.downStairsCentre());
            int shaftMax = shaftMin + TrackGenerator.shaftFootprintX() - 1;
            // The entrance is one wider each side than the shaft.
            assertTrue(shaftMin - 1 >= section);
            assertTrue(shaftMax + 1 < section + TrackTestLayout.TUNNEL_PIECE);
        }
    }

    @Test
    @DisplayName("The swept box reaches every block the scene stamps")
    void boxCoversEverything() {
        for (TrackTestLayout l : LAYOUTS) {
            TrackGeometry g = new TrackGeometry(TrackTestLayout.bedY(), TrackTestLayout.bedY() + 1,
                0, l.trackWidth() - 1);
            TunnelGeometry tg = TunnelGeometry.from(g);
            int tunnelMinZ = tg.wallMinZ() + 1;
            assertEquals(TrackTestLayout.TUNNEL_Z, tunnelMinZ);
            assertTrue(tunnelMinZ >= TrackTestLayout.minZ());
            assertTrue(tunnelMinZ + TunnelPlacer.WIDTH - 1 <= l.maxZ());
            // Down-stairs on −Z with its entrance one wider; pillar stairs on +Z.
            assertTrue(TrackGenerator.downStairsOriginZ(true, g) - 1 >= TrackTestLayout.minZ());
            assertTrue(TrackGenerator.upStairsOriginZ(false, g) + TrackGenerator.shaftFootprintZ() - 1 <= l.maxZ());
            // Up to the entrance's roof on the tunnel, or the carriage's, whichever is higher.
            assertTrue(l.topY() >= TrackTestLayout.surfaceY() - 2 + PillarAdjunct.STAIRS_ENTRANCE.ySize() - 1);
            assertTrue(l.topY() >= TrackTestLayout.trainY() + l.carriageHeight() - 1);
            assertEquals(TrackTestLayout.bedY() + TunnelPlacer.HEIGHT, TrackTestLayout.surfaceY());
        }
    }
}
