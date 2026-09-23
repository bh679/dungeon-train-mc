package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the {@link SphereField}: seed-stable rolls, bounded geometry, density tracking the
 * knob, surface-anchored lift, and — the property the carve pass relies on — that a chunk's
 * candidate list never misses a sphere touching one of its blocks. The world-facing wrapper
 * ({@code SpheresBand}) needs a {@code ServerLevel} and is covered in-game.
 */
final class SphereFieldTest {

    private static final long SEED = 0x5EED5EED5EEDL;
    private static final int SURFACE = 64;

    private static SphereField field(double density) {
        return new SphereField(new SphereField.Params(SEED, 64, density, 6, 40, 0, 200, 0.65), (x, z) -> SURFACE);
    }

    @Test
    @DisplayName("rolls are deterministic in (seed, cell) and differ across seeds")
    void deterministic() {
        SphereField a = field(0.5);
        SphereField b = field(0.5);
        SphereField other = new SphereField(new SphereField.Params(SEED + 1, 64, 0.5, 6, 40, 0, 200, 0.65), (x, z) -> SURFACE);
        int same = 0, n = 0;
        for (int cx = -10; cx < 10; cx++) {
            for (int cz = -10; cz < 10; cz++) {
                for (int cy = 0; cy <= 3; cy++) {
                    assertEquals(a.sphereInCell(cx, cy, cz), b.sphereInCell(cx, cy, cz));
                    n++;
                    if (java.util.Objects.equals(a.sphereInCell(cx, cy, cz), other.sphereInCell(cx, cy, cz))) same++;
                }
            }
        }
        // Two seeds agree only where both rolled "no sphere" (≈25% at density 0.5) — never on a sphere.
        assertTrue(same < n * 0.4, "seeds should decorrelate: " + same + "/" + n + " identical cells");
    }

    @Test
    @DisplayName("every sphere stays inside its cell's X/Z, the centre-Y range, and the radius range; lift anchors 65% of the diameter below the surface")
    void bounds() {
        SphereField f = field(1.0);
        for (int cx = -8; cx < 8; cx++) {
            for (int cz = -8; cz < 8; cz++) {
                for (int cy = 0; cy <= 3; cy++) {
                    SphereField.Sphere s = f.sphereInCell(cx, cy, cz);
                    assertNotNull(s, "density 1 → every cell rolls a sphere");
                    assertTrue(s.cx() >= cx * 64 && s.cx() < cx * 64 + 64, "cx in cell");
                    assertTrue(s.cz() >= cz * 64 && s.cz() < cz * 64 + 64, "cz in cell");
                    assertTrue(s.cy() >= 0 && s.cy() <= 200, "cy in [centerMinY, centerMaxY]: " + s.cy());
                    assertTrue(s.cy() >= cy * 64 && s.cy() < cy * 64 + 64, "cy in its Y layer");
                    assertTrue(s.r() >= 6 && s.r() <= 40, "radius in range: " + s.r());
                    // sourceCenterY = surface − round(r·(2·0.65 − 1)) = surface − round(0.3·r)
                    int sourceCenter = s.cy() - s.dy();
                    assertEquals(SURFACE - (int) Math.round(s.r() * 0.3), sourceCenter, "surface-anchored source centre");
                }
            }
        }
    }

    @Test
    @DisplayName("presence tracks the density knob; density 0 rolls nothing")
    void densityTracksKnob() {
        SphereField f = field(0.15);
        int n = 0, present = 0;
        for (int cx = -40; cx < 40; cx++) {
            for (int cz = -40; cz < 40; cz++) {
                for (int cy = 0; cy <= 3; cy++) {
                    n++;
                    if (f.sphereInCell(cx, cy, cz) != null) present++;
                }
            }
        }
        double frac = (double) present / n;
        assertTrue(Math.abs(frac - 0.15) < 0.02, "presence " + frac + " should be ≈0.15");
        SphereField none = field(0.0);
        for (int cx = 0; cx < 20; cx++) assertNull(none.sphereInCell(cx, 1, cx));
    }

    @Test
    @DisplayName("radius roll is biased small: the median radius sits well below the midpoint")
    void radiusBiasedSmall() {
        SphereField f = field(1.0);
        int small = 0, n = 0;
        for (int cx = -30; cx < 30; cx++) {
            for (int cz = -30; cz < 30; cz++) {
                SphereField.Sphere s = f.sphereInCell(cx, 1, cz);
                n++;
                if (s.r() < 23) small++;                        // midpoint of [6, 40]
            }
        }
        assertTrue(small > n * 0.6, "expected most spheres below the midpoint radius, got " + small + "/" + n);
    }

    @Test
    @DisplayName("candidatesFor never misses: every block inside any sphere is owned by a candidate of its chunk, and candidates only list spheres touching the chunk")
    void candidatesComplete() {
        SphereField f = field(0.5);
        // Ground truth: brute-force every sphere in a generous cell window around the test region.
        Set<SphereField.Sphere> all = new HashSet<>();
        for (int cx = -4; cx <= 4; cx++) {
            for (int cz = -4; cz <= 4; cz++) {
                for (int cy = 0; cy <= 3; cy++) {
                    SphereField.Sphere s = f.sphereInCell(cx, cy, cz);
                    if (s != null) all.add(s);
                }
            }
        }
        assertTrue(all.size() > 20, "test region should hold plenty of spheres: " + all.size());
        int checked = 0;
        for (int chunkX = -4; chunkX <= 4; chunkX++) {
            for (int chunkZ = -4; chunkZ <= 4; chunkZ++) {
                List<SphereField.Sphere> cand = f.candidatesFor(chunkX, chunkZ);
                int minX = chunkX << 4, minZ = chunkZ << 4;
                for (SphereField.Sphere s : cand) {
                    boolean touches = false;
                    for (int x = minX; x < minX + 16 && !touches; x++)
                        for (int z = minZ; z < minZ + 16 && !touches; z++) touches = s.touchesColumn(x, z);
                    assertTrue(touches, "candidate must touch the chunk: " + s);
                }
                for (int x = minX; x < minX + 16; x += 3) {
                    for (int z = minZ; z < minZ + 16; z += 3) {
                        for (int y = -40; y <= 240; y += 4) {
                            SphereField.Sphere truth = SphereField.bestAt(List.copyOf(all), x, y, z);
                            SphereField.Sphere got = SphereField.bestAt(cand, x, y, z);
                            assertEquals(truth, got, "owner mismatch at (" + x + "," + y + "," + z + ")");
                            checked++;
                        }
                    }
                }
            }
        }
        assertTrue(checked > 10_000);
    }

    @Test
    @DisplayName("Sphere geometry: contains/touchesColumn/sourceY behave")
    void sphereGeometry() {
        SphereField.Sphere s = new SphereField.Sphere(10, 100, -5, 8, 30);
        assertTrue(s.contains(10, 100, -5));
        assertTrue(s.contains(18, 100, -5));
        assertTrue(!s.contains(19, 100, -5));
        assertTrue(s.touchesColumn(10, 3));
        assertTrue(!s.touchesColumn(10, 4));
        assertEquals(70, s.sourceY(100));
        assertEquals(0.0, s.normDistSq(10, 100, -5), 1e-12);
        assertEquals(1.0, s.normDistSq(18, 100, -5), 1e-12);
    }
}
