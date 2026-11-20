package games.brennan.dungeontrain.event;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecentExplosionsTest {

    private static final UnaryOperator<Vec3> IDENTITY = v -> v;
    private static final Vec3 BODY = new Vec3(0, 64, 0);

    @Test
    void picksTheNearestBlastThatReaches() {
        RecentExplosions.Blast near = new RecentExplosions.Blast(Level.OVERWORLD, new Vec3(1, 64, 0), 4f, 100);
        RecentExplosions.Blast far = new RecentExplosions.Blast(Level.OVERWORLD, new Vec3(5, 64, 0), 4f, 100);
        RecentExplosions.Blast picked = RecentExplosions.nearest(List.of(far, near), Level.OVERWORLD, BODY, 100, IDENTITY);
        assertEquals(near.centre(), picked.centre());
    }

    @Test
    void ignoresOtherDimensionsStaleAndOutOfReach() {
        List<RecentExplosions.Blast> blasts = List.of(
            new RecentExplosions.Blast(Level.NETHER, new Vec3(1, 64, 0), 4f, 100),
            new RecentExplosions.Blast(Level.OVERWORLD, new Vec3(1, 64, 0), 4f, 100 - RecentExplosions.KEEP_TICKS - 1),
            new RecentExplosions.Blast(Level.OVERWORLD, new Vec3(9, 64, 0), 4f, 100));
        assertNull(RecentExplosions.nearest(blasts, Level.OVERWORLD, BODY, 100, IDENTITY));
    }

    @Test
    void mapsACarriagePlotCentreIntoTheWorld() {
        // A bed blast inside a carriage plot reports shipyard coordinates, far from the body.
        Vec3 plot = new Vec3(20_000_000, 64, 20_000_000);
        RecentExplosions.Blast inPlot = new RecentExplosions.Blast(Level.OVERWORLD, plot, 5f, 100);
        UnaryOperator<Vec3> toWorld = v -> v.subtract(plot).add(2, 64, 0);
        RecentExplosions.Blast picked = RecentExplosions.nearest(List.of(inPlot), Level.OVERWORLD, BODY, 100, toWorld);
        assertEquals(new Vec3(2, 64, 0), picked.centre());
    }
}
