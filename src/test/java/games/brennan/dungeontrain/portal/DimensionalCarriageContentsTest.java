package games.brennan.dungeontrain.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a contents template lands inside a dimensional carriage.
 *
 * <p>This is the whole of the placement rule, and it decides whether a template is written into a
 * live world at all — a wrong anchor puts furniture inside a wall, and a wrong acceptance test
 * stamps a template past the room's edge into the rock around it. Pure integer geometry, so it is
 * checked here rather than in-game.</p>
 */
class DimensionalCarriageContentsTest {

    /** The built-in room's interior at the default carriage dims: 11×7×13 minus a shell row each way. */
    private static final Vec3i ROOM = new Vec3i(9, 5, 11);

    /** An ordinary carriage's contents box at the default dims: 9×7×7 minus its shell. */
    private static final Vec3i CARRIAGE = new Vec3i(7, 5, 5);

    // ---- the tag ----

    @Test
    @DisplayName("Parsing is total — a hand-edited typo leaves the room unfurnished, not broken")
    void parseIsTotal() {
        assertSame(DimensionalCarriageContents.OFF, DimensionalCarriageContents.DEFAULT);
        assertSame(DimensionalCarriageContents.DEFAULT, DimensionalCarriageContents.parse(null));
        assertSame(DimensionalCarriageContents.DEFAULT, DimensionalCarriageContents.parse(""));
        assertSame(DimensionalCarriageContents.DEFAULT, DimensionalCarriageContents.parse("  "));
        assertSame(DimensionalCarriageContents.DEFAULT, DimensionalCarriageContents.parse("phit"));
        assertSame(DimensionalCarriageContents.FIT, DimensionalCarriageContents.parse("FIT"));
        assertSame(DimensionalCarriageContents.TILE, DimensionalCarriageContents.parse(" tile "));
    }

    @Test
    @DisplayName("Every value round-trips through its id, and the cycle button reaches all of them")
    void idsRoundTripAndCycleIsComplete() {
        for (DimensionalCarriageContents c : DimensionalCarriageContents.values()) {
            assertSame(c, DimensionalCarriageContents.parse(c.id()), c.name());
        }
        DimensionalCarriageContents at = DimensionalCarriageContents.OFF;
        for (int i = 0; i < DimensionalCarriageContents.values().length; i++) at = at.next();
        assertSame(DimensionalCarriageContents.OFF, at, "cycling the full length must return to the start");
    }

    @Test
    @DisplayName("Only Off leaves a room unfurnished")
    void furnishes() {
        assertTrue(!DimensionalCarriageContents.OFF.furnishes());
        for (DimensionalCarriageContents c : DimensionalCarriageContents.values()) {
            if (c != DimensionalCarriageContents.OFF) assertTrue(c.furnishes(), c.name());
        }
    }

    // ---- Off ----

    @Test
    @DisplayName("Off places nothing, whatever would have fitted")
    void offPlacesNothing() {
        assertEquals(List.of(), DimensionalCarriageContents.OFF.anchorsIn(ROOM, CARRIAGE));
        assertEquals(List.of(), DimensionalCarriageContents.OFF.anchorsIn(ROOM, ROOM));
    }

    // ---- Fit ----

    @Test
    @DisplayName("Fit centres a carriage-sized template on the room floor")
    void fitCentresOnTheFloor() {
        List<BlockPos> anchors = DimensionalCarriageContents.FIT.anchorsIn(ROOM, CARRIAGE);
        // 9-7 = 2 spare along X, 11-5 = 6 along Z; half of each, and never lifted off the floor.
        assertEquals(List.of(new BlockPos(1, 0, 3)), anchors);
    }

    @Test
    @DisplayName("Fit never lifts a furnishing off the floor, even in a much taller room")
    void fitNeverCentresInY() {
        List<BlockPos> anchors = DimensionalCarriageContents.FIT.anchorsIn(new Vec3i(9, 10, 11), CARRIAGE);
        assertEquals(1, anchors.size());
        assertEquals(0, anchors.get(0).getY());
    }

    @Test
    @DisplayName("Fit takes a template of exactly the interior, at the origin")
    void fitTakesAnExactTemplate() {
        assertEquals(List.of(BlockPos.ZERO), DimensionalCarriageContents.FIT.anchorsIn(ROOM, ROOM));
    }

    @Test
    @DisplayName("Fit rejects a template too big on any single axis")
    void fitRejectsOversize() {
        assertEquals(List.of(), DimensionalCarriageContents.FIT.anchorsIn(ROOM, new Vec3i(10, 5, 5)));
        assertEquals(List.of(), DimensionalCarriageContents.FIT.anchorsIn(ROOM, new Vec3i(7, 6, 5)));
        assertEquals(List.of(), DimensionalCarriageContents.FIT.anchorsIn(ROOM, new Vec3i(7, 5, 12)));
    }

    // ---- Exact ----

    @Test
    @DisplayName("Exact takes only a template authored at the interior size")
    void exactDemandsAnExactMatch() {
        assertEquals(List.of(BlockPos.ZERO), DimensionalCarriageContents.EXACT.anchorsIn(ROOM, ROOM));
        assertEquals(List.of(), DimensionalCarriageContents.EXACT.anchorsIn(ROOM, CARRIAGE));
        assertEquals(List.of(), DimensionalCarriageContents.EXACT.anchorsIn(ROOM, new Vec3i(9, 5, 10)));
    }

    // ---- Tile ----

    @Test
    @DisplayName("Tile lays whole copies only, with the leftover split between the two edges")
    void tileLaysWholeCopiesCentred() {
        // 9/7 = 1 along X, 11/5 = 2 along Z. Used: 7 and 10; spare 2 and 1, halved to 1 and 0.
        List<BlockPos> anchors = DimensionalCarriageContents.TILE.anchorsIn(ROOM, CARRIAGE);
        assertEquals(List.of(new BlockPos(1, 0, 0), new BlockPos(1, 0, 5)), anchors);
    }

    @Test
    @DisplayName("Tile fills an exact multiple with no margin and no gaps")
    void tileFillsAnExactMultiple() {
        List<BlockPos> anchors =
            DimensionalCarriageContents.TILE.anchorsIn(new Vec3i(14, 5, 10), CARRIAGE);
        assertEquals(4, anchors.size());
        assertTrue(anchors.contains(new BlockPos(0, 0, 0)));
        assertTrue(anchors.contains(new BlockPos(7, 0, 0)));
        assertTrue(anchors.contains(new BlockPos(0, 0, 5)));
        assertTrue(anchors.contains(new BlockPos(7, 0, 5)));
    }

    @Test
    @DisplayName("Every tile copy stays inside the interior it is filling")
    void tileNeverOverrunsTheRoom() {
        for (int length = 5; length <= 46; length++) {
            for (int width = 5; width <= 46; width++) {
                Vec3i interior = new Vec3i(length, 5, width);
                for (BlockPos anchor : DimensionalCarriageContents.TILE.anchorsIn(interior, CARRIAGE)) {
                    String at = interior + " @ " + anchor;
                    assertTrue(anchor.getX() >= 0 && anchor.getZ() >= 0, at);
                    assertTrue(anchor.getX() + CARRIAGE.getX() <= interior.getX(), at);
                    assertTrue(anchor.getZ() + CARRIAGE.getZ() <= interior.getZ(), at);
                }
            }
        }
    }

    @Test
    @DisplayName("Tile rejects an oversize template outright rather than placing a partial copy")
    void tileRejectsOversize() {
        assertEquals(List.of(), DimensionalCarriageContents.TILE.anchorsIn(ROOM, new Vec3i(10, 5, 5)));
    }

    // ---- degenerate boxes ----

    @Test
    @DisplayName("A room with no interior volume is never stamped into")
    void noVolumeYieldsNoAnchors() {
        for (DimensionalCarriageContents c : DimensionalCarriageContents.values()) {
            assertEquals(List.of(), c.anchorsIn(new Vec3i(0, 5, 11), CARRIAGE), c.name());
            assertEquals(List.of(), c.anchorsIn(new Vec3i(9, -1, 11), CARRIAGE), c.name());
            assertEquals(List.of(), c.anchorsIn(ROOM, new Vec3i(7, 0, 5)), c.name());
            assertEquals(List.of(), c.anchorsIn(null, CARRIAGE), c.name());
            assertEquals(List.of(), c.anchorsIn(ROOM, null), c.name());
        }
    }
}
