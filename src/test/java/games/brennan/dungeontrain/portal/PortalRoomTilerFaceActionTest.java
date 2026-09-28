package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalRoomTiler.FaceAction;
import games.brennan.dungeontrain.portal.PortalRoomTiling.Tile;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the tiler does to one face of one copy.
 *
 * <p>The whole face rule, as a decision table. It is worth pinning here rather than only in-game
 * because each of the three operations is destructive in its own direction — a carve deletes the wall
 * between two copies, a close fills an authored opening, and an open strips the face away — so a
 * wrong answer is a room that cannot be walked through or a wall the author built and never sees
 * again.</p>
 */
class PortalRoomTilerFaceActionTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;
    private static final BlockPos ORIGIN = new BlockPos(200, -60, -30);

    private static PortalStructure structure(PortalRoomMode mode, PortalRoomDoorWall walls) {
        return new PortalStructure(ORIGIN, "default", PortalRoomLayout.builtInSize(DIMS),
            new PortalRoomSettings(mode, PortalRoomCopies.DYNAMIC, PortalRoomContents.DEFAULT, null,
                PortalRoomBooks.DEFAULT, PortalRoomSky.NONE, walls),
            PortalRoomTiling.base());
    }

    @Test
    @DisplayName("Merged: a face with a neighbour is carved, one without is closed")
    void mergedCarvesAndCloses() {
        PortalStructure s = structure(PortalRoomMode.ENDLESS_REPETITION, PortalRoomDoorWall.SEALED);
        assertEquals(FaceAction.CARVE,
            PortalRoomTiler.faceAction(s, new Tile(1, 0), 1, /*hasNeighbour*/ true));
        assertEquals(FaceAction.CLOSE,
            PortalRoomTiler.faceAction(s, new Tile(1, 0), 1, /*hasNeighbour*/ false));
    }

    @Test
    @DisplayName("Endless Open closes nothing — a face without a neighbour is taken away instead")
    void endlessOpenOpensItsOuterFaces() {
        PortalStructure s = structure(PortalRoomMode.ENDLESS_OPEN, PortalRoomDoorWall.SEALED);
        assertEquals(FaceAction.CARVE,
            PortalRoomTiler.faceAction(s, new Tile(1, 0), 1, true));
        assertEquals(FaceAction.OPEN,
            PortalRoomTiler.faceAction(s, new Tile(1, 0), 1, false));
    }

    @Test
    @DisplayName("A tag with no Room Walls segment is Kept: nothing is carved or closed by default")
    void defaultTouchesNothing() {
        PortalStructure s = new PortalStructure(ORIGIN, "default", PortalRoomLayout.builtInSize(DIMS),
            PortalRoomSettings.parse("endless_repetition/dynamic"), PortalRoomTiling.base());
        assertEquals(FaceAction.NONE, PortalRoomTiler.faceAction(s, new Tile(1, 0), 1, true));
        assertEquals(FaceAction.NONE, PortalRoomTiler.faceAction(s, new Tile(1, 0), 1, false));
        // Endless Open is untouched by the default: its faces are still opened.
        PortalStructure open = new PortalStructure(ORIGIN, "default", PortalRoomLayout.builtInSize(DIMS),
            PortalRoomSettings.parse("endless_open"), PortalRoomTiling.base());
        assertEquals(FaceAction.OPEN, PortalRoomTiler.faceAction(open, new Tile(1, 0), 1, false));
    }

    @Test
    @DisplayName("Kept: every face is left exactly as the stamp wrote it, neighbour or not")
    void keptTouchesNothing() {
        PortalStructure s = structure(PortalRoomMode.ENDLESS_REPETITION, PortalRoomDoorWall.REPEATED);
        // Both answers, because the two failure modes are opposite: carving deletes the author's wall
        // between copies, closing fills the opening they left in it. Kept means neither.
        assertEquals(FaceAction.NONE, PortalRoomTiler.faceAction(s, new Tile(1, 0), 1, true));
        assertEquals(FaceAction.NONE, PortalRoomTiler.faceAction(s, new Tile(1, 0), 1, false));
        assertEquals(FaceAction.NONE, PortalRoomTiler.faceAction(s, Tile.BASE, -1, true));
        assertEquals(FaceAction.NONE, PortalRoomTiler.faceAction(s, Tile.BASE, -1, false));
    }

    @Test
    @DisplayName("Kept means nothing under Endless Open, which has no walls to keep")
    void keptIsInertWhereItCannotApply() {
        // effectiveDoorWall neutralises a value left over from a mode change, so the face rule here
        // is Endless Open's own — otherwise a room switched from Repetition would stop stripping its
        // faces and stand up walls the mode says it does not have.
        PortalStructure s = structure(PortalRoomMode.ENDLESS_OPEN, PortalRoomDoorWall.REPEATED);
        assertEquals(FaceAction.CARVE, PortalRoomTiler.faceAction(s, new Tile(1, 0), 1, true));
        assertEquals(FaceAction.OPEN, PortalRoomTiler.faceAction(s, new Tile(1, 0), 1, false));
    }
}
