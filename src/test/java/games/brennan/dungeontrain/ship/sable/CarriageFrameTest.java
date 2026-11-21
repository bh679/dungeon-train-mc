package games.brennan.dungeontrain.ship.sable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CarriageFrameTest {

    /** A carriage plot sitting where the dev-server log put one: world (6412, 79, 3.5) ↔ plot (20481031.4, 126, 20485128.5). */
    private static final Vec3 OFFSET = new Vec3(20_481_031.4 - 6412.0, 126.0 - 79.0, 20_485_128.5 - 3.5);
    private static final UnaryOperator<Vec3> TO_PLOT = v -> v.add(OFFSET);
    private static final UnaryOperator<Vec3> TO_WORLD = v -> v.subtract(OFFSET);

    @Test
    void feetOnTheDeckNameTheAirAboveIt() {
        // Dev's feet on the deck at world y 79 → plot y 126: the cell above the deck block (125).
        assertEquals(new BlockPos(20_481_031, 126, 20_485_128),
                CarriageFrame.localBlockAt(6412.0, 79.0, 3.5, TO_PLOT));
    }

    @Test
    void aTransformThatLandsAHairUnderTheBlockTopStillNamesTheAirAbove() {
        UnaryOperator<Vec3> lossy = v -> TO_PLOT.apply(v).add(0, -0.0001, 0);
        assertEquals(126, CarriageFrame.localBlockAt(6412.0, 79.0, 3.5, lossy).getY());
    }

    @Test
    void halfBlockFeetStayInTheirOwnCell() {
        // Standing on a slab top (feet x.5) is inside the slab's cell, as vanilla's getBlockY is.
        assertEquals(126, CarriageFrame.localBlockAt(6412.0, 79.5, 3.5, TO_PLOT).getY());
    }

    @Test
    void strafeOffsetsMoveTheProbeInWorldAxes() {
        BlockPos here = CarriageFrame.localBlockAt(6412.0, 79.0, 3.5, TO_PLOT);
        BlockPos back = CarriageFrame.localBlockAt(6412.0 + 1.2, 79.0, 3.5 - 0.3, TO_PLOT);
        assertEquals(here.east(), back);
    }

    @Test
    void worldPointRoundTripsThroughThePlot() {
        Vec3 world = new Vec3(6420.25, 79.0, 2.75);
        Vec3 plot = TO_PLOT.apply(world);
        Vec3 back = CarriageFrame.worldPointOf(plot.x, plot.y, plot.z, TO_WORLD);
        assertEquals(world.x, back.x, 1e-6);
        assertEquals(world.y, back.y, 1e-9);
        assertEquals(world.z, back.z, 1e-6);
    }
}
