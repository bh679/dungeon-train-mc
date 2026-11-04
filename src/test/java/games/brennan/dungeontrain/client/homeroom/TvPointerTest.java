package games.brennan.dungeontrain.client.homeroom;

import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TvPointerTest {

    /** A 3 wide x 2 tall screen, x 17..19, y -60..-59, on the z=-514 row, picture facing south. */
    private static final BoundingBox SCREEN = new BoundingBox(17, -60, -514, 19, -59, -514);

    @Test
    void southFacingLeftEdgeIsLowX() {
        double[] uv = TvPointer.faceFraction(Direction.SOUTH, SCREEN, new Vec3(17.0, -58.0, -513.0));
        assertNotNull(uv);
        assertEquals(0.0, uv[0], 1e-9);
        assertEquals(0.0, uv[1], 1e-9, "top of the top row");
        double[] centre = TvPointer.faceFraction(Direction.SOUTH, SCREEN, new Vec3(18.5, -59.0, -513.0));
        assertEquals(0.5, centre[0], 1e-9);
        assertEquals(0.5, centre[1], 1e-9);
    }

    @Test
    void northFacingMirrorsAcross() {
        double[] uv = TvPointer.faceFraction(Direction.NORTH, SCREEN, new Vec3(17.0, -59.0, -514.0));
        assertEquals(1.0, uv[0], 1e-9, "low x is the viewer's right when the picture faces north");
    }

    @Test
    void eastAndWestUseZ() {
        BoundingBox side = new BoundingBox(10, 5, 20, 10, 6, 22);
        assertEquals(1.0, TvPointer.faceFraction(Direction.EAST, side, new Vec3(11.0, 6.5, 20.0))[0], 1e-9);
        assertEquals(0.0, TvPointer.faceFraction(Direction.WEST, side, new Vec3(10.0, 6.5, 20.0))[0], 1e-9);
    }

    @Test
    void offTheFaceIsNull() {
        assertNull(TvPointer.faceFraction(Direction.SOUTH, SCREEN, new Vec3(16.5, -59.0, -513.0)));
        assertNull(TvPointer.faceFraction(Direction.UP, SCREEN, new Vec3(18.0, -59.0, -513.0)));
    }

    @Test
    void containFitLetterboxesTheTallerAxis() {
        // A 16:9 picture on a 3:2 screen: full width, bars top and bottom.
        int[] top = TvPointer.toGui(0.5, 0.0, 1.5, 16 / 9.0, 854, 480);
        assertEquals(TvPointer.OFF_SCREEN, top[0], "in the bar");
        int[] centre = TvPointer.toGui(0.5, 0.5, 1.5, 16 / 9.0, 854, 480);
        assertEquals(427, centre[0]);
        assertEquals(240, centre[1]);
        int[] left = TvPointer.toGui(0.0, 0.5, 1.5, 16 / 9.0, 854, 480);
        assertEquals(0, left[0]);
    }

    @Test
    void sameAspectMapsStraight() {
        int[] p = TvPointer.toGui(0.25, 0.75, 1.5, 1.5, 400, 300);
        assertEquals(100, p[0]);
        assertEquals(225, p[1]);
    }
}
