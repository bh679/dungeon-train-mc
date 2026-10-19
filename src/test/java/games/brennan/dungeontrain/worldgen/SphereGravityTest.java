package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SphereGravityTest {

    private static final double EPS = 1e-9;

    @Test
    @DisplayName("the largest sphere pulls at vanilla gravity at its surface; small ones proportionally less, floored")
    void surfaceScalesWithSize() {
        assertEquals(SphereGravity.VANILLA_G, SphereGravity.surfacePull(40, 1.0), EPS);
        assertEquals(SphereGravity.VANILLA_G * 0.5, SphereGravity.surfacePull(20, 1.0), EPS);
        assertEquals(SphereGravity.VANILLA_G * SphereGravity.MIN_SIZE, SphereGravity.surfacePull(2, 1.0), EPS);
        assertEquals(SphereGravity.VANILLA_G, SphereGravity.surfacePull(80, 1.0), EPS, "capped at full size");
        assertEquals(SphereGravity.VANILLA_G * 2, SphereGravity.surfacePull(40, 2.0), EPS);
    }

    @Test
    @DisplayName("outside: inverse-square, fading to exactly 0 at the reach; inside: the full surface pull")
    void falloff() {
        int r = 20;
        double g = SphereGravity.surfacePull(r, 1.0);
        assertEquals(g, SphereGravity.pullAt(r, r, 1.0, 2.0), EPS);
        assertEquals(g * (20.0 / 28) * (20.0 / 28), SphereGravity.pullAt(28, r, 1.0, 2.0), EPS, "before the fade");
        assertTrue(SphereGravity.pullAt(35, r, 1.0, 2.0) < g * (20.0 / 35) * (20.0 / 35), "fading");
        assertEquals(0.0, SphereGravity.pullAt(40, r, 1.0, 2.0), EPS);
        assertEquals(0.0, SphereGravity.pullAt(400, r, 1.0, 2.0), EPS);
        assertEquals(g, SphereGravity.pullAt(10, r, 1.0, 2.0), EPS);
        assertEquals(g, SphereGravity.pullAt(0, r, 1.0, 2.0), EPS);
        assertEquals(0.0, SphereGravity.pullAt(80, r, 1.0, 4.0), EPS, "4x reach ends at 4r");
        assertTrue(SphereGravity.pullAt(60, r, 1.0, 4.0) > 0, "4x reach still pulls at 3r");
        double prev = Double.MAX_VALUE;
        for (double d = r; d <= 2 * r; d += 0.25) {
            double a = SphereGravity.pullAt(d, r, 1.0, 2.0);
            assertTrue(a <= prev + EPS, "monotone outside at " + d);
            prev = a;
        }
    }

    @Test
    @DisplayName("pull points at the centre, sums across spheres, and stops once moving toward the centre fast enough")
    void pullVector() {
        SphereField.Sphere s = new SphereField.Sphere(100, 100, 100, 40, 0);
        SphereGravity.Pull p = SphereGravity.pull(List.of(s), 100.5, 150.5, 100.5, 0, 0, 0, false, 1.0, 2.0);
        assertEquals(0, p.x(), EPS);
        assertEquals(0, p.z(), EPS);
        assertTrue(p.y() < 0, "sphere is below → pulled down");
        assertEquals(SphereGravity.pullAt(50, 40, 1.0, 2.0), p.length(), EPS);

        SphereField.Sphere twin = new SphereField.Sphere(100, 200, 100, 40, 0);
        SphereGravity.Pull both = SphereGravity.pull(List.of(s, twin), 100.5, 150.5, 100.5, 0, 0, 0, false, 1.0, 2.0);
        assertEquals(0, both.length(), EPS, "equal and opposite cancel");

        SphereGravity.Pull capped = SphereGravity.pull(List.of(s), 100.5, 150.5, 100.5, 0, -0.7, 0, false, 1.0, 2.0);
        assertTrue(capped.isZero(), "already falling in fast");
        SphereGravity.Pull away = SphereGravity.pull(List.of(s), 100.5, 150.5, 100.5, 0, 0.7, 0, false, 1.0, 2.0);
        assertEquals(p, away, "moving away is still pulled");
    }

    @Test
    @DisplayName("standing on a sphere (or in a tunnel through it) stops its pull; jumping brings it back at full strength; others still pull")
    void standingAndInside() {
        SphereField.Sphere s = new SphereField.Sphere(100, 100, 100, 40, 0);
        assertTrue(SphereGravity.pull(List.of(s), 100.5, 141.5, 100.5, 0, 0, 0, true, 1.0, 4.0).isZero(), "standing on top");
        assertTrue(!SphereGravity.pull(List.of(s), 100.5, 141.5, 100.5, 0, 0, 0, false, 1.0, 4.0).isZero(), "airborne just above");
        assertTrue(!SphereGravity.pull(List.of(s), 100.5, 150.5, 100.5, 0, 0, 0, true, 1.0, 4.0).isZero(),
                "on other ground well clear of the sphere (a train roof) still pulled");
        assertTrue(SphereGravity.pull(List.of(s), 100.5, 112.5, 100.5, 0, 0, 0, true, 1.0, 4.0).isZero(),
                "standing on the cap, well inside the radius");
        SphereGravity.Pull jump = SphereGravity.pull(List.of(s), 100.5, 113.5, 100.5, 0, 0.4, 0, false, 1.0, 4.0);
        assertEquals(-SphereGravity.surfacePull(40, 1.0), jump.y(), EPS, "jumping off the cap: full pull, straight down");
        SphereField.Sphere other = new SphereField.Sphere(100, 100, 200, 40, 0);
        SphereGravity.Pull p = SphereGravity.pull(List.of(s, other), 100.5, 141.5, 100.5, 0, 0, 0, true, 1.0, 4.0);
        assertTrue(p.z() > 0, "the other sphere keeps pulling");
    }

    @Test
    @DisplayName("lookup reach covers the largest sphere's pull")
    void lookupReach() {
        assertEquals(40.0, SphereGravity.lookupReach(40, 2.0), EPS);
        assertEquals(0.0, SphereGravity.lookupReach(40, 1.0), EPS);
    }
}
