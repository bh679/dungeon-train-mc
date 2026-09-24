package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.portal.PortalCarriageBuilder;
import games.brennan.dungeontrain.portal.PortalCorridorKind;
import games.brennan.dungeontrain.portal.PortalCorridorSize;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EditorPlotArrivalTest {

    /** A 9×7×7 carriage box at the origin — the shipped default. */
    private static final BlockPos ORIGIN = new BlockPos(0, 230, 0);
    private static final Vec3i FOOTPRINT = new Vec3i(9, 7, 7);
    /** The -X doorway's lower cell for that box: x=0, floor+1, z=width/2. */
    private static final BlockPos DOOR = new BlockPos(0, 231, 3);

    private static Predicate<BlockPos> blocked(BlockPos... cells) {
        Set<BlockPos> solid = Set.of(cells);
        return p -> !solid.contains(p) && !solid.contains(p.above());
    }

    @Test
    @DisplayName("roof landing: three blocks in front of the menu anchor, Z-centred, facing +X")
    void roof_inFrontOfMenu() {
        EditorPlotArrival a = EditorPlotArrival.inFrontOfMenu(ORIGIN, FOOTPRINT);
        // anchor x = origin + length - 1 = 8; stand 3 back → 5.5
        assertEquals(5.5, a.x());
        assertEquals(238.0, a.y());
        assertEquals(3.5, a.z());
        assertEquals(EditorPlotArrival.FACING_POSITIVE_X, a.yaw());
    }

    /**
     * The LONG portal corridor's box at the default dims — 13 long, four past the carriage. The
     * label and the landing both have to be built from this box, not the world's: built from the
     * world's, the menu anchored at x=8 while the player landed at 9.5 facing +X, 1.5 blocks in
     * front of the menu with their back to it.
     */
    private static Vec3i corridorFootprint() {
        // The production call both the label and the landing size the plot with.
        Vec3i size = new Template.Carriage(PortalCarriageBuilder.portalVariant(PortalCorridorKind.LONG))
            .plotSize(CarriageDims.DEFAULT);
        CarriageDims box = PortalCorridorSize.corridorDims(CarriageDims.DEFAULT, PortalCorridorKind.LONG);
        assertEquals(new Vec3i(box.length(), box.height(), box.width()), size,
            "test premise: the portal variant's plot is the LONG corridor's box");
        return size;
    }

    @Test
    @DisplayName("roof landing on a plot longer than the world dims sits at that plot's own +X end")
    void roof_longCorridorPlot() {
        Vec3i corridor = corridorFootprint();
        assertEquals(13, corridor.getX(), "test premise: the LONG corridor is 13 at 9×7×7");
        EditorPlotArrival a = EditorPlotArrival.inFrontOfMenu(ORIGIN, corridor);
        // anchor x = origin + 13 - 1 = 12; stand 3 back → 9.5
        assertEquals(9.5, a.x());
        assertEquals(238.0, a.y());
        assertEquals(3.5, a.z());
        assertEquals(EditorPlotArrival.FACING_POSITIVE_X, a.yaw());
    }

    @Test
    @DisplayName("label anchor built from the plot's box is STANDOFF ahead of the landing; the world-dims anchor is behind it")
    void labelAnchorMatchesLandingOnLongPlot() {
        Vec3i corridor = corridorFootprint();
        EditorPlotArrival landing = EditorPlotArrival.inFrontOfMenu(ORIGIN, corridor);

        BlockPos labelAnchor = EditorPlotLabels.anchorAbove(ORIGIN, corridor);
        assertEquals(landing.x() + EditorPlotArrival.STANDOFF, labelAnchor.getX() + 0.5);
        assertEquals(landing.z(), labelAnchor.getZ() + 0.5);

        // The bug in one line: the same plot measured with the world's dims anchors the label
        // behind a player who is facing +X.
        BlockPos worldDimsAnchor = EditorPlotLabels.anchorAbove(ORIGIN, FOOTPRINT);
        assertNotEquals(labelAnchor, worldDimsAnchor);
        assertTrue(worldDimsAnchor.getX() + 0.5 < landing.x(),
            "world-dims anchor x=" + worldDimsAnchor.getX() + " is behind landing x=" + landing.x());
    }

    @Test
    @DisplayName("roof landing clamps to the plot's own -X edge on a short plot")
    void roof_clampsOnShortPlot() {
        EditorPlotArrival a = EditorPlotArrival.inFrontOfMenu(ORIGIN, new Vec3i(1, 5, 7));
        assertEquals(0.5, a.x());
    }

    @Test
    @DisplayName("free doorway: land in it")
    void freeCell_preferredWhenFree() {
        assertEquals(DOOR, EditorPlotArrival.freeCell(ORIGIN, FOOTPRINT, DOOR, blocked()));
    }

    @Test
    @DisplayName("door block in the doorway: step one inside along the row")
    void freeCell_stepsInsideAlongRow() {
        assertEquals(DOOR.east(), EditorPlotArrival.freeCell(ORIGIN, FOOTPRINT, DOOR, blocked(DOOR)));
    }

    @Test
    @DisplayName("head-height block counts as blocked too")
    void freeCell_headBlockBlocks() {
        assertEquals(DOOR.east(), EditorPlotArrival.freeCell(ORIGIN, FOOTPRINT, DOOR, blocked(DOOR.above())));
    }

    @Test
    @DisplayName("row fully built up: nearest free interior cell by distance, never the shell")
    void freeCell_fallsBackToNearestInterior() {
        // Wall the whole door row (x 0..7 at z=3) — the fallback must leave the row.
        BlockPos[] row = new BlockPos[8];
        for (int x = 0; x < 8; x++) row[x] = new BlockPos(x, 231, 3);
        BlockPos got = EditorPlotArrival.freeCell(ORIGIN, FOOTPRINT, DOOR, blocked(row));
        // Nearest interior cells to (0,231,3) are (1,231,2) and (1,231,4) at d²=2 — either is
        // acceptable, but it must be interior: one in from every face.
        assertEquals(1, got.getX());
        assertEquals(231, got.getY());
        assertEquals(1, Math.abs(got.getZ() - 3));
    }

    @Test
    @DisplayName("sealed build: the preferred cell comes back as-is")
    void freeCell_sealedReturnsPreferred() {
        assertEquals(DOOR, EditorPlotArrival.freeCell(ORIGIN, FOOTPRINT, DOOR, p -> false));
    }

    @Test
    @DisplayName("author-cap room with a raised floor: nearest cell one up, found without scanning the box")
    void freeCell_largeRaisedFloorIsCheap() {
        // 64³ (PortalRoomLayout.AUTHOR_MAX) with the whole interior floor level solid — the door row
        // is blocked, so the fallback runs. The old path built and sorted every interior cell
        // (62×62×61 ≈ 234k) before probing one.
        Vec3i big = new Vec3i(64, 64, 64);
        BlockPos door = new BlockPos(0, 231, 32);
        AtomicInteger probes = new AtomicInteger();
        Predicate<BlockPos> raisedFloor = p -> {
            probes.incrementAndGet();
            return p.getY() >= 232;
        };
        BlockPos got = EditorPlotArrival.freeCell(ORIGIN, big, door, raisedFloor);
        // (1, 232, 32): one in from the door, one up — d² = 2, the nearest interior cell that fits.
        assertEquals(new BlockPos(1, 232, 32), got);
        assertTrue(probes.get() < 100, "probed " + probes.get() + " cells; the search should stay local");
    }

    @Test
    @DisplayName("nearest is by straight-line distance, not by shell: a closer cell in a later shell wins")
    void freeCell_nearestIsEuclideanAcrossShells() {
        // 11³ box, preferred at its interior centre, so shells 0..3 fit inside. Free cells: a corner
        // of Chebyshev shell 2 (d² = 12) and a face cell of shell 3 (d² = 9). The face cell is
        // farther by shell but nearer by distance, and must win.
        Vec3i box = new Vec3i(11, 12, 11);
        BlockPos centre = new BlockPos(5, 235, 5);
        BlockPos shell2Corner = centre.offset(2, 2, 2);
        BlockPos shell3Face = centre.offset(3, 0, 0);
        Predicate<BlockPos> fits = p -> p.equals(shell2Corner) || p.equals(shell3Face);
        assertEquals(shell3Face, EditorPlotArrival.freeCell(ORIGIN, box, centre, fits));
    }

    @Test
    @DisplayName("the result is a plain BlockPos, not the search cursor")
    void freeCell_returnsImmutable() {
        BlockPos[] row = new BlockPos[8];
        for (int x = 0; x < 8; x++) row[x] = new BlockPos(x, 231, 3);
        BlockPos got = EditorPlotArrival.freeCell(ORIGIN, FOOTPRINT, DOOR, blocked(row));
        assertEquals(BlockPos.class, got.getClass());
    }

    @Test
    @DisplayName("no interior at all: the preferred cell comes back as-is")
    void freeCell_noInteriorReturnsPreferred() {
        BlockPos door = new BlockPos(0, 231, 1);
        assertEquals(door, EditorPlotArrival.freeCell(ORIGIN, new Vec3i(2, 3, 2), door, blocked(door)));
    }

    @Test
    @DisplayName("shell search matches a brute-force nearest on random boxes and blockages")
    void freeCell_matchesBruteForce() {
        Random rng = new Random(0x5EED);
        for (int trial = 0; trial < 300; trial++) {
            Vec3i box = new Vec3i(3 + rng.nextInt(8), 4 + rng.nextInt(8), 3 + rng.nextInt(8));
            BlockPos preferred = ORIGIN.offset(rng.nextInt(box.getX()), 1 + rng.nextInt(2), rng.nextInt(box.getZ()));
            Set<BlockPos> solid = new HashSet<>();
            int fill = rng.nextInt(box.getX() * box.getY() * box.getZ());
            for (int i = 0; i < fill; i++) {
                solid.add(ORIGIN.offset(rng.nextInt(box.getX()), rng.nextInt(box.getY()), rng.nextInt(box.getZ())));
            }
            Predicate<BlockPos> fits = p -> !solid.contains(p) && !solid.contains(p.above());
            BlockPos got = EditorPlotArrival.freeCell(ORIGIN, box, preferred, fits);
            BlockPos want = reference(box, preferred, fits);
            assertEquals(want.distSqr(preferred), got.distSqr(preferred),
                "trial " + trial + " box " + box + " preferred " + preferred + ": got " + got + " want " + want);
            assertTrue(got.equals(preferred) || fits.test(got), "trial " + trial + " landed in a block: " + got);
        }
    }

    /** The pre-shell-search algorithm: preferred, its +X row, then every interior cell sorted by distance. */
    private static BlockPos reference(Vec3i box, BlockPos preferred, Predicate<BlockPos> fits) {
        if (fits.test(preferred)) return preferred;
        for (int x = preferred.getX() + 1; x <= ORIGIN.getX() + box.getX() - 2; x++) {
            BlockPos p = new BlockPos(x, preferred.getY(), preferred.getZ());
            if (fits.test(p)) return p;
        }
        BlockPos best = null;
        for (int dx = 1; dx <= box.getX() - 2; dx++) {
            for (int dz = 1; dz <= box.getZ() - 2; dz++) {
                for (int dy = 1; dy <= box.getY() - 3; dy++) {
                    BlockPos p = ORIGIN.offset(dx, dy, dz);
                    if (fits.test(p) && (best == null || p.distSqr(preferred) < best.distSqr(preferred))) best = p;
                }
            }
        }
        return best == null ? preferred : best;
    }

    // --- land() dispatch: which landing a request picks ---

    /** The centre cell of the default box: x=4, floor+1, z=3. */
    private static final BlockPos CENTRE = new BlockPos(4, 231, 3);
    private static final float YAW = 37f, PITCH = -12f;

    private static EditorPlotArrival landing(boolean onTop, EditorPlotArrival.Inside inside, BlockPos door,
                                             Predicate<BlockPos> fits) {
        return EditorPlotArrival.landing(ORIGIN, FOOTPRINT, onTop, inside, door, YAW, PITCH, fits);
    }

    @Test
    @DisplayName("land: onTop wins — the roof landing, whatever inside/door say")
    void landing_onTopIsRoof() {
        EditorPlotArrival roof = EditorPlotArrival.inFrontOfMenu(ORIGIN, FOOTPRINT);
        assertEquals(roof, landing(true, EditorPlotArrival.Inside.FRONT_DOOR, DOOR, blocked()));
        assertEquals(roof, landing(true, EditorPlotArrival.Inside.CENTRE, null, blocked()));
    }

    @Test
    @DisplayName("land: FRONT_DOOR with a door lands in the doorway facing +X")
    void landing_frontDoor() {
        EditorPlotArrival a = landing(false, EditorPlotArrival.Inside.FRONT_DOOR, DOOR, blocked());
        assertEquals(new EditorPlotArrival(0.5, 231, 3.5, EditorPlotArrival.FACING_POSITIVE_X, 0f), a);
    }

    @Test
    @DisplayName("land: FRONT_DOOR with no door (doorless plot) lands at the centre, heading kept")
    void landing_frontDoorWithoutDoorIsCentre() {
        EditorPlotArrival a = landing(false, EditorPlotArrival.Inside.FRONT_DOOR, null, blocked());
        assertEquals(new EditorPlotArrival(4.5, 231, 3.5, YAW, PITCH), a);
    }

    @Test
    @DisplayName("land: CENTRE ignores the door and keeps the heading")
    void landing_centreIgnoresDoor() {
        EditorPlotArrival a = landing(false, EditorPlotArrival.Inside.CENTRE, DOOR, blocked());
        assertEquals(new EditorPlotArrival(4.5, 231, 3.5, YAW, PITCH), a);
    }

    @Test
    @DisplayName("land: a blocked doorway steps inside along the row — the fit test is honoured")
    void landing_blockedDoorStepsInside() {
        EditorPlotArrival a = landing(false, EditorPlotArrival.Inside.FRONT_DOOR, DOOR, blocked(DOOR));
        assertEquals(new EditorPlotArrival(1.5, 231, 3.5, EditorPlotArrival.FACING_POSITIVE_X, 0f), a);
    }

    @Test
    void firstOrNull_emptyIsNull() {
        assertNull(EditorPlotArrival.firstOrNull(List.of()));
    }

    @Test
    void firstOrNull_takesTheFrontDoor() {
        assertEquals(DOOR, EditorPlotArrival.firstOrNull(List.of(DOOR, new BlockPos(8, 231, 3))));
    }
}
