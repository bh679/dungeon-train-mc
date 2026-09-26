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

    // ---- dimension mix ------------------------------------------------------

    /** A mixer that picks by roll among all three sources and a fixed structure chance; End surface as given. */
    private static SphereField.Mixer mixer(double structureChance, int endSurface) {
        return new SphereField.Mixer() {
            @Override public SphereSource sourceAt(int cx, double u) {
                return SphereSource.values()[(int) (u * 3)];
            }
            @Override public double structureChanceAt(int cx) { return structureChance; }
            @Override public int endSurfaceY(int x, int z) { return endSurface; }
        };
    }

    private static SphereField mixed(double structureChance, int endSurface) {
        return new SphereField(new SphereField.Params(SEED, 64, 1.0, 6, 40, 0, 200, 0.65), (x, z) -> SURFACE,
                mixer(structureChance, endSurface));
    }

    @Test
    @DisplayName("with a mixer, sources are deterministic, all three appear, and the default field stays overworld-only")
    void mixedSources() {
        SphereField a = mixed(0.0, 60), b = mixed(0.0, 60), plain = field(1.0);
        java.util.EnumMap<SphereSource, Integer> seen = new java.util.EnumMap<>(SphereSource.class);
        for (int cx = -8; cx < 8; cx++) {
            for (int cz = -8; cz < 8; cz++) {
                SphereField.Sphere s = a.sphereInCell(cx, 1, cz);
                assertEquals(s, b.sphereInCell(cx, 1, cz));
                seen.merge(s.source(), 1, Integer::sum);
                assertEquals(SphereSource.OVERWORLD, plain.sphereInCell(cx, 1, cz).source());
                org.junit.jupiter.api.Assertions.assertFalse(plain.sphereInCell(cx, 1, cz).offline());
            }
        }
        assertEquals(3, seen.size(), "all three sources should roll: " + seen);
    }

    /** An overworld-only mixer with a fixed exit taper. */
    private static SphereField tapered(double density, double taper) {
        return new SphereField(new SphereField.Params(SEED, 64, density, 6, 40, 0, 200, 0.65), (x, z) -> SURFACE,
                new SphereField.Mixer() {
                    @Override public SphereSource sourceAt(int cx, double u) { return SphereSource.OVERWORLD; }
                    @Override public double structureChanceAt(int cx) { return 0.0; }
                    @Override public int endSurfaceY(int x, int z) { return SphereField.NO_SURFACE; }
                    @Override public double taperAt(int cx) { return taper; }
                });
    }

    @Test
    @DisplayName("exit taper: 1 leaves every sphere identical, 0.5 rolls fewer and smaller ones, 0 rolls none")
    void exitTaper() {
        SphereField plain = field(0.5), full = tapered(0.5, 1.0), half = tapered(0.5, 0.5), none = tapered(0.5, 0.0);
        int plainCount = 0, halfCount = 0;
        for (int cx = -20; cx < 20; cx++) {
            for (int cz = -20; cz < 20; cz++) {
                SphereField.Sphere p = plain.sphereInCell(cx, 1, cz);
                assertEquals(p, full.sphereInCell(cx, 1, cz));
                org.junit.jupiter.api.Assertions.assertNull(none.sphereInCell(cx, 1, cz));
                SphereField.Sphere h = half.sphereInCell(cx, 1, cz);
                if (p != null) plainCount++;
                if (h != null) {
                    halfCount++;
                    org.junit.jupiter.api.Assertions.assertNotNull(p, "a tapered sphere only rolls where the untapered one does");
                    assertEquals(p.cx(), h.cx());
                    assertTrue(h.r() < p.r() || h.r() <= SphereField.MIN_TAPERED_RADIUS,
                            "tapered radius " + h.r() + " vs " + p.r());
                    assertTrue(h.r() >= Math.min(p.r(), SphereField.MIN_TAPERED_RADIUS));
                }
            }
        }
        assertTrue(halfCount > 0 && halfCount < plainCount, "half taper keeps some: " + halfCount + " of " + plainCount);
    }

    @Test
    @DisplayName("End spheres anchor to the island surface; over the End void they turn Nether; Nether centres sit in [40,100]")
    void foreignAnchors() {
        SphereField islands = mixed(0.0, 70);
        SphereField voidEnd = mixed(0.0, SphereField.NO_SURFACE);
        for (int cx = -8; cx < 8; cx++) {
            for (int cz = -8; cz < 8; cz++) {
                SphereField.Sphere s = islands.sphereInCell(cx, 1, cz);
                int sourceCentre = s.sourceY(s.cy());
                if (s.source() == SphereSource.END) {
                    assertEquals(70 - (int) Math.round(s.r() * 0.3), sourceCentre);
                    assertTrue(s.offline());
                }
                if (s.source() == SphereSource.NETHER) {
                    assertTrue(sourceCentre >= 40 && sourceCentre <= 100, "nether centre " + sourceCentre);
                }
                org.junit.jupiter.api.Assertions.assertNotEquals(SphereSource.END, voidEnd.sphereInCell(cx, 1, cz).source());
            }
        }
    }

    @Test
    @DisplayName("the structure roll tracks the mixer's chance, and a structure makes even an overworld sphere offline")
    void structureRoll() {
        SphereField f = mixed(0.4, 60);
        int structures = 0, n = 0;
        for (int cx = -20; cx < 20; cx++) {
            for (int cz = -20; cz < 20; cz++) {
                SphereField.Sphere s = f.sphereInCell(cx, 1, cz);
                n++;
                if (s.structure()) {
                    structures++;
                    assertTrue(s.offline());
                }
            }
        }
        double rate = (double) structures / n;
        assertTrue(rate > 0.33 && rate < 0.47, "structure rate " + rate);
        org.junit.jupiter.api.Assertions.assertFalse(mixed(0.0, 60).sphereInCell(0, 1, 0).structure());
    }

    @Test
    @DisplayName("sphere ids are distinct across cells")
    void ids() {
        SphereField f = mixed(0.0, 60);
        Set<Long> ids = new HashSet<>();
        int n = 0;
        for (int cx = -10; cx < 10; cx++) {
            for (int cz = -10; cz < 10; cz++) {
                for (int cy = 0; cy <= 3; cy++) {
                    ids.add(f.sphereInCell(cx, cy, cz).id());
                    n++;
                }
            }
        }
        assertEquals(n, ids.size());
    }
}
