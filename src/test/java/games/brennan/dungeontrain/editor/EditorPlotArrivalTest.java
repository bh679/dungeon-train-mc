package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
