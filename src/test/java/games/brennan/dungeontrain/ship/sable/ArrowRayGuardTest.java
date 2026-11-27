package games.brennan.dungeontrain.ship.sable;

import games.brennan.dungeontrain.ship.sable.ArrowRayGuard.Verdict;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArrowRayGuardTest {

    /** Plot origin like the player's log: carriage blocks near x=20,483,062, z=20,505,605. */
    private static final double PLOT_X = 20_483_062;
    private static final double PLOT_Z = 20_505_605;
    /** Where that plot sits in the world. */
    private static final double WORLD_X = 82_015;
    private static final double WORLD_Z = 0;

    /** Positions inside the plot (x past 20M) map to world space by a pure translation; others stay put. */
    private static final UnaryOperator<Vec3> PLOT_PROJECTOR = pos -> pos.x > 20_000_000
            ? new Vec3(pos.x - PLOT_X + WORLD_X, pos.y - 47, pos.z - PLOT_Z + WORLD_Z)
            : pos;

    @Test
    @DisplayName("a normal world-space shot is left alone")
    void normalShot() {
        Vec3 from = new Vec3(82_030, 80, 2);
        assertEquals(Verdict.OK, ArrowRayGuard.verdict(from, new Vec3(3.0, -0.2, 0.5), PLOT_PROJECTOR));
    }

    @Test
    @DisplayName("an arrow moving inside one carriage plot is left alone")
    void sameSubLevel() {
        Vec3 from = new Vec3(PLOT_X + 10, 127, PLOT_Z + 3);
        assertEquals(Verdict.OK, ArrowRayGuard.verdict(from, new Vec3(-1.5, -0.1, 0.0), PLOT_PROJECTOR));
    }

    @Test
    @DisplayName("a delta of millions of blocks (Sable's onHitBlock on a plot-space arrow) is caught")
    void absurdDelta() {
        Vec3 from = new Vec3(40_900_000, 127, 41_000_000);
        Vec3 delta = new Vec3(-4_000_000, 0, -4_100_000);
        assertEquals(Verdict.ABSURD_DELTA, ArrowRayGuard.verdict(from, delta, PLOT_PROJECTOR));
    }

    @Test
    @DisplayName("a short raw ray whose ends project into different spaces is caught")
    void crossSpace() {
        // From just inside the plot to just outside it: the raw ray is 2 blocks, the world ray ~20M.
        UnaryOperator<Vec3> edgeProjector = pos -> pos.x >= PLOT_X
                ? new Vec3(pos.x - PLOT_X + WORLD_X, pos.y, pos.z - PLOT_Z)
                : pos;
        Vec3 from = new Vec3(PLOT_X + 0.5, 127, PLOT_Z);
        assertEquals(Verdict.CROSS_SPACE, ArrowRayGuard.verdict(from, new Vec3(-2, 0, 0), edgeProjector));
    }

    @Test
    @DisplayName("the limit is exactly MAX_STEP_BLOCKS")
    void thresholdEdges() {
        Vec3 from = new Vec3(0, 80, 0);
        double max = ArrowRayGuard.MAX_STEP_BLOCKS;
        assertEquals(Verdict.OK, ArrowRayGuard.verdict(from, new Vec3(max, 0, 0), UnaryOperator.identity()));
        assertEquals(Verdict.ABSURD_DELTA,
                ArrowRayGuard.verdict(from, new Vec3(max + 0.01, 0, 0), UnaryOperator.identity()));
    }

    @Test
    @DisplayName("a NaN delta is caught instead of reaching the raycast")
    void nanDelta() {
        Vec3 from = new Vec3(0, 80, 0);
        assertEquals(Verdict.ABSURD_DELTA,
                ArrowRayGuard.verdict(from, new Vec3(Double.NaN, 0, 0), UnaryOperator.identity()));
    }
}
