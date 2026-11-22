package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalRoomBorderFace.Piece;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The corridor mouth cut out of a World Border room's west and east faces. */
final class PortalRoomBorderFaceTest {

    private static long area(List<Piece> pieces) {
        long sum = 0;
        for (Piece p : pieces) sum += (long) (p.along1() - p.along0()) * (p.top() - p.bottom());
        return sum;
    }

    @Test
    void noHoleLeavesTheWallWhole() {
        assertEquals(List.of(new Piece(0, 11, -64, 320)),
            PortalRoomBorderFace.split(0, 11, -64, 320, 0, 0, 0, 0));
    }

    @Test
    void centredHoleLeavesFourPiecesCoveringTheRest() {
        List<Piece> pieces = PortalRoomBorderFace.split(0, 11, -64, 320, 3, 8, 10, 17);
        assertEquals(4, pieces.size());
        assertEquals(11L * 384 - 5L * 7, area(pieces));
    }

    @Test
    void holeOnTheFloorHasNothingBelowIt() {
        List<Piece> pieces = PortalRoomBorderFace.split(0, 11, 10, 40, 3, 8, 10, 17);
        assertEquals(3, pieces.size());
        assertEquals(11L * 30 - 5L * 7, area(pieces));
        for (Piece p : pieces) assertEquals(true, p.bottom() >= 10);
    }

    @Test
    void holeWiderThanTheWallIsClamped() {
        List<Piece> pieces = PortalRoomBorderFace.split(0, 11, 0, 100, -5, 20, 10, 17);
        assertEquals(List.of(new Piece(0, 11, 0, 10), new Piece(0, 11, 17, 100)), pieces);
    }

    @Test
    void holeOffTheWallLeavesItWhole() {
        assertEquals(List.of(new Piece(0, 11, 0, 100)),
            PortalRoomBorderFace.split(0, 11, 0, 100, 20, 25, 10, 17));
    }
}
