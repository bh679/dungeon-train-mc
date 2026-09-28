package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the {@link SpheresBand} helpers that need no {@code ServerLevel}: the zone filter
 * that keeps a sphere only when its whole X extent lies inside the band's fade + core.
 */
final class SpheresBandTest {

    /** Spheres fade [4490,4690), core [4690,5090) — the layout WorldGenCycleTest#spheresBand pins. */
    private static final WorldGenCycle C = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200,
            100, 40, 200, 50, 200, 150, 0, 500, 200, 300, 0.12, 0.5, 400, 200, 100, 0);

    private static SphereField.Sphere at(int cx, int r) {
        return new SphereField.Sphere(cx, 100, 0, r, 20);
    }

    @Test
    @DisplayName("insideBand keeps spheres wholly within fade+core and drops any that cross either edge")
    void zoneFilter() {
        SphereField.Sphere leadGap = at(4450, 10);        // entirely before the fade
        SphereField.Sphere straddleIn = at(4495, 10);     // [4485,4505] crosses the fade start
        SphereField.Sphere fade = at(4600, 30);           // [4570,4630] inside the fade
        SphereField.Sphere crossing = at(4690, 20);       // fade → core, both in-zone
        SphereField.Sphere core = at(4900, 40);           // deep in the core
        SphereField.Sphere straddleOut = at(5085, 10);    // [5075,5095] crosses the hard far edge
        SphereField.Sphere after = at(5200, 10);          // next period's leading gap
        List<SphereField.Sphere> kept = SpheresBand.insideBand(C,
                List.of(leadGap, straddleIn, fade, crossing, core, straddleOut, after));
        assertEquals(List.of(fade, crossing, core), kept);
        assertTrue(SpheresBand.insideBand(C, List.of()).isEmpty());
        assertTrue(SpheresBand.insideBand(C, List.of(leadGap)).isEmpty());
        // Disabled band (base cycle): nothing survives.
        WorldGenCycle off = new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0, 0, 0, 0);
        assertTrue(SpheresBand.insideBand(off, List.of(core)).isEmpty());
    }
}
