package games.brennan.dungeontrain.client.tributeroom;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How far into a tube the view goes black. */
class TributeTvVisitTubeTest {

    // Room x 100..110 with the TV in it; entrance tube 84..99 to the west, exit tube 111..126 to the east.
    private static final BoundingBox SCREEN = new BoundingBox(104, 2, 100, 106, 3, 100);
    private static final List<BoundingBox> TUBES = List.of(
        new BoundingBox(84, 0, 96, 99, 6, 104),
        new BoundingBox(111, 0, 96, 126, 6, 104));

    @Test
    @DisplayName("In the room there is no darkness at all")
    void roomIsClear() {
        assertEquals(0f, TributeTvVisit.tubeDepth(TUBES, SCREEN, 105.5, 100.5));
        assertEquals(0f, TributeTvVisit.tubeDepth(TUBES, SCREEN, 105.5, 120.0));
    }

    @Test
    @DisplayName("The mouth of either tube is clear and its far wall is black")
    void mouthClearFarWallBlack() {
        assertEquals(0f, TributeTvVisit.tubeDepth(TUBES, SCREEN, 100.0, 100.5), 1e-6);
        assertEquals(0f, TributeTvVisit.tubeDepth(TUBES, SCREEN, 111.0, 100.5), 1e-6);
        assertEquals(1f, TributeTvVisit.tubeDepth(TUBES, SCREEN, 84.0, 100.5), 1e-6);
        assertEquals(1f, TributeTvVisit.tubeDepth(TUBES, SCREEN, 126.99, 100.5), 1e-3);
    }

    @Test
    @DisplayName("Halfway down a tube is half dark, whichever way it points")
    void halfwayIsHalf() {
        assertEquals(0.5f, TributeTvVisit.tubeDepth(TUBES, SCREEN, 92.0, 100.5), 1e-6);
        assertEquals(0.5f, TributeTvVisit.tubeDepth(TUBES, SCREEN, 119.0, 100.5), 1e-6);
    }

    @Test
    @DisplayName("Without a TV box nothing is known, so nothing darkens")
    void noScreenNoDarkness() {
        assertEquals(0f, TributeTvVisit.tubeDepth(TUBES, null, 92.0, 100.5));
    }
}
