package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.template.FlipOptions;
import games.brennan.dungeontrain.train.ContentsFlip.Flip;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-stamp random flip: the roll itself, and the coordinate maths every pass shares. */
final class ContentsFlipTest {

    private static final Vec3i SIZE = new Vec3i(10, 4, 5);

    @Test
    @DisplayName("the roll is pure — the blocks pass and the deferred entity pass agree")
    void rollIsDeterministic() {
        for (int i = 0; i < 50; i++) {
            Flip a = ContentsFlip.roll("maze", FlipOptions.DEFAULT, 1234L, i);
            Flip b = ContentsFlip.roll("maze", FlipOptions.DEFAULT, 1234L, i);
            assertEquals(a, b);
        }
    }

    @Test
    @DisplayName("a disabled axis never flips; no axes enabled is never a flip at all")
    void disabledAxesNeverFlip() {
        FlipOptions xOnly = FlipOptions.DEFAULT;
        for (int i = 0; i < 200; i++) {
            Flip f = ContentsFlip.roll("books", xOnly, 99L, i);
            assertFalse(f.y(), "y is off by default");
            assertFalse(f.z(), "z is off by default");
            assertSame(Flip.NONE, ContentsFlip.roll("books", FlipOptions.NONE, 99L, i));
        }
    }

    @Test
    @DisplayName("an enabled axis flips about half the time across carriages")
    void enabledAxisIsRoughlyEven() {
        int flipped = 0;
        int n = 400;
        for (int i = 0; i < n; i++) {
            if (ContentsFlip.roll("shop", FlipOptions.DEFAULT, 7L, i).x()) flipped++;
        }
        assertTrue(flipped > n / 4 && flipped < 3 * n / 4,
            "expected a roughly even split, got " + flipped + "/" + n);
    }

    @Test
    @DisplayName("axes roll independently of each other")
    void axesAreIndependent() {
        FlipOptions all = new FlipOptions(true, true, true, false);
        boolean sawDisagreement = false;
        for (int i = 0; i < 200 && !sawDisagreement; i++) {
            Flip f = ContentsFlip.roll("craft", all, 5L, i);
            if (f.x() != f.z() || f.x() != f.y()) sawDisagreement = true;
        }
        assertTrue(sawDisagreement, "x, y and z must not move as one");
    }

    @Test
    @DisplayName("different templates in the same carriage roll differently")
    void idSaltsTheRoll() {
        boolean sawDifference = false;
        for (int i = 0; i < 200 && !sawDifference; i++) {
            if (ContentsFlip.roll("maze", FlipOptions.DEFAULT, 3L, i).x()
                    != ContentsFlip.roll("books", FlipOptions.DEFAULT, 3L, i).x()) {
                sawDifference = true;
            }
        }
        assertTrue(sawDifference);
    }

    @Test
    @DisplayName("mapLocal mirrors within the box and is its own inverse")
    void mapLocalIsAnInvolution() {
        Flip flip = new Flip(true, false, true);
        BlockPos local = new BlockPos(1, 2, 0);
        BlockPos moved = ContentsFlip.mapLocal(local, SIZE, flip);
        assertEquals(new BlockPos(8, 2, 4), moved);
        assertEquals(local, ContentsFlip.mapLocal(moved, SIZE, flip));
        assertSame(local, ContentsFlip.mapLocal(local, SIZE, Flip.NONE));
    }

    @Test
    @DisplayName("every cell of the box maps to a distinct cell still inside it")
    void mapLocalStaysInsideTheBox() {
        Flip flip = new Flip(true, true, true);
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        for (int x = 0; x < SIZE.getX(); x++) {
            for (int y = 0; y < SIZE.getY(); y++) {
                for (int z = 0; z < SIZE.getZ(); z++) {
                    BlockPos moved = ContentsFlip.mapLocal(new BlockPos(x, y, z), SIZE, flip);
                    assertTrue(moved.getX() >= 0 && moved.getX() < SIZE.getX());
                    assertTrue(moved.getY() >= 0 && moved.getY() < SIZE.getY());
                    assertTrue(moved.getZ() >= 0 && moved.getZ() < SIZE.getZ());
                    assertTrue(seen.add(moved), "mapping must be a bijection");
                }
            }
        }
        assertEquals(SIZE.getX() * SIZE.getY() * SIZE.getZ(), seen.size());
    }

    @Test
    @DisplayName("the shifted origin lands a vanilla-mirrored box exactly back on the interior")
    void originShiftMatchesVanillaMirror() {
        // Vanilla maps a mirrored cell to -c about a zero pivot (StructureTemplate.transform), so the
        // stamp origin has to carry the box width back. Even-length axes are the case an integer
        // rotation pivot could not have centred, which is why the shift exists at all.
        Flip flip = new Flip(true, false, true);
        BlockPos origin = new BlockPos(100, 64, 200);
        BlockPos stampOrigin = ContentsFlip.originFor(origin, SIZE, flip);
        for (int x = 0; x < SIZE.getX(); x++) {
            for (int z = 0; z < SIZE.getZ(); z++) {
                BlockPos vanilla = stampOrigin.offset(-x, 0, -z);           // what placeInWorld writes
                BlockPos ours = origin.offset(ContentsFlip.mapLocal(new BlockPos(x, 0, z), SIZE, flip));
                assertEquals(ours, vanilla);
            }
        }
        assertSame(origin, ContentsFlip.originFor(origin, SIZE, Flip.NONE));
        // A vertical-only flip is not a vanilla mirror at all, so it must not move the origin.
        assertSame(origin, ContentsFlip.originFor(origin, SIZE, new Flip(false, true, false)));
    }

    @Test
    @DisplayName("an entity keeps its position within its cell when the cell mirrors")
    void entityCoordsMirrorInContinuousSpace() {
        // Cell 0 spans [0,1) and mirrors to cell 9 spanning [9,10): a body at 0.5 lands at 9.5.
        assertEquals(9.5, ContentsFlip.mapLocalCoord(0.5, 10, true), 1e-9);
        assertEquals(0.5, ContentsFlip.mapLocalCoord(9.5, 10, true), 1e-9);
        assertEquals(0.5, ContentsFlip.mapLocalCoord(0.5, 10, false), 1e-9);
    }

    @Test
    @DisplayName("the debug label names the flipped axes, or none")
    void labelNamesTheAxes() {
        assertEquals("none", ContentsFlip.label(Flip.NONE));
        assertEquals("none", ContentsFlip.label(null));
        assertEquals("X", ContentsFlip.label(new Flip(true, false, false)));
        assertEquals("Y", ContentsFlip.label(new Flip(false, true, false)));
        assertEquals("Z", ContentsFlip.label(new Flip(false, false, true)));
        assertEquals("X+Z", ContentsFlip.label(new Flip(true, false, true)));
        assertEquals("X+Y+Z", ContentsFlip.label(new Flip(true, true, true)));
    }

    @Test
    @DisplayName("yaw mirrors with the axes: X negates it, Z reflects it about 180")
    void yawMirrors() {
        assertEquals(-90.0f, ContentsFlip.reflectYaw(90.0f, new Flip(true, false, false)), 1e-4);
        // Wrapped to [-180, 180), so due north comes back as -180 rather than 180 — the same heading.
        assertEquals(-180.0f, ContentsFlip.reflectYaw(0.0f, new Flip(false, false, true)), 1e-4);
        assertEquals(90.0f, ContentsFlip.reflectYaw(90.0f, new Flip(false, false, true)), 1e-4);
        assertEquals(0.0f, ContentsFlip.reflectYaw(0.0f, Flip.NONE), 1e-4);
        // Both axes is a 180° turn, matching the CLOCKWISE_180 the block stamp uses for that case.
        assertEquals(-180.0f, ContentsFlip.reflectYaw(0.0f, new Flip(true, false, true)), 1e-4);
    }
}
