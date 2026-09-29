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
    @DisplayName("For a track tile the train stands on open line, with track behind it and clear of the tunnel")
    void tileTrainOnOpenLine() {
        for (TrackTestLayout l : LAYOUTS) {
            TrackTestPiece tile = TrackTestPiece.TILE;
            assertTrue(l.backPadX(tile) > 0, "track behind the back pad");
            assertTrue(l.backPadX(tile) + l.trainLength() <= l.tunnelX(), "train clear of the tunnel");
            assertEquals(0, l.openLength() % TrackTestLayout.TILE_LENGTH, "open line on the tile grid");
            assertEquals(TrackTestLayout.bedY() + 2, TrackTestLayout.trainY(), "carriage on the rails");
        }
    }

    @Test
    @DisplayName("Every piece's train is pads-carriage-pads, inside the stretch")
    void trainInsideStretch() {
        for (TrackTestLayout l : LAYOUTS) {
            for (TrackTestPiece p : TrackTestPiece.values()) {
                assertEquals(l.backPadX(p) + l.halfPad(), l.carriageX(p));
                assertEquals(l.carriageX(p) + l.carriageLength(), l.frontPadX(p));
                assertTrue(l.backPadX(p) >= 0, p.name());
                assertTrue(l.frontPadX(p) + l.halfPad() <= l.stretchLength(), p.name());
            }
        }
    }

    @Test
    @DisplayName("The train stands on the section under test, so the author starts there")
    void trainOnTheTestedSection() {
        TrackTestLayout l = layout(9, 7, 7);
        int trainEnd = l.trainLength();
        for (TrackTestPiece p : List.of(TrackTestPiece.PILLAR_BOTTOM, TrackTestPiece.PILLAR_MIDDLE,
                TrackTestPiece.PILLAR_TOP, TrackTestPiece.STAIRS)) {
            // Over the middle column, the one the staircase stands beside.
            assertTrue(l.backPadX(p) <= l.stairsColumn() && l.stairsColumn() < l.backPadX(p) + trainEnd, p.name());
        }
        // A portal: straddling the tunnel's mouth.
        int portal = l.backPadX(TrackTestPiece.TUNNEL_PORTAL);
        assertTrue(portal < l.tunnelX() && l.tunnelX() < portal + trainEnd);
        // A section and a staircase entrance: wholly inside the tunnel.
        for (TrackTestPiece p : List.of(TrackTestPiece.TUNNEL_SECTION, TrackTestPiece.STAIRS_ENTRANCE)) {
            assertTrue(l.backPadX(p) >= l.tunnelX() + TrackTestLayout.TUNNEL_PIECE, p.name());
            assertTrue(l.backPadX(p) + trainEnd <= l.tunnelX() + TrackTestLayout.tunnelLength()
                - TrackTestLayout.TUNNEL_PIECE, p.name());
        }
        // The staircase entrance's train is under its shaft.
        int shaft = l.downStairsCentre();
        int entrance = l.backPadX(TrackTestPiece.STAIRS_ENTRANCE);
        assertTrue(entrance <= shaft && shaft < entrance + trainEnd);
    }

    @Test
    @DisplayName("A default carriage fits under the tunnel ceiling")
    void carriageFitsInTheTunnel() {
        TrackGeometry g = new TrackGeometry(TrackTestLayout.bedY(), TrackTestLayout.bedY() + 1, 0, 6);
        int ceiling = TunnelGeometry.from(g).ceilingY();
        assertTrue(TrackTestLayout.trainY() + 7 - 1 < ceiling);
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
    @DisplayName("The down-stairs shaft rises through the sections, clear of both portals")
    void downStairsInTheSections() {
        for (TrackTestLayout l : LAYOUTS) {
            int sectionsStart = l.tunnelPieces().get(1).x();
            int sectionsEnd = sectionsStart + TrackTestLayout.TUNNEL_SECTIONS * TrackTestLayout.TUNNEL_PIECE;
            int shaftMin = TrackGenerator.downStairsOriginX(l.downStairsCentre());
            int shaftMax = shaftMin + TrackGenerator.shaftFootprintX() - 1;
            // The entrance is one wider each side than the shaft.
            assertTrue(shaftMin - 1 >= sectionsStart);
            assertTrue(shaftMax + 1 < sectionsEnd);
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
