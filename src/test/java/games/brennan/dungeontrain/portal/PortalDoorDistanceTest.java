package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Door Distance: the gap that stands an open room's corridors out on its plain. The geometry it
 * moves feeds the frames a player is mapped into, so zero must be exactly the old geometry and any
 * other value must move both twins symmetrically, the room's box untouched in size.
 */
class PortalDoorDistanceTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;
    private static final int CORRIDOR = PortalCorridorSize.corridorLength(DIMS, PortalCorridorKind.LONG);
    private static final BlockPos ORIGIN = new BlockPos(200, -60, -30);
    private static final Vec3i CHUNK = new Vec3i(16, 32, 16);

    private static PortalStructure structure(PortalRoomMode mode, int gap) {
        PortalRoomSettings settings = PortalRoomSettings.DEFAULT.withMode(mode)
            .withDoorDistance(new PortalRoomDoorDistance(gap));
        return new PortalStructure(ORIGIN, "default", CHUNK, settings, null, null, null,
            PortalCorridorKind.LONG);
    }

    @Test
    @DisplayName("no gap is exactly the geometry every pair had before Door Distance")
    void zeroGap_isTheOldGeometry() {
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS, PortalCorridorKind.LONG);
        PortalStructure s = structure(PortalRoomMode.ENDLESS_OPEN, 0);
        assertEquals(0, s.doorGap());
        assertEquals(CORRIDOR + 16, s.exitTwinOffsetX(DIMS));
        assertEquals(ORIGIN.getX() + CORRIDOR, s.roomOrigin(DIMS, layout).getX());
        assertEquals(CORRIDOR + 16 + CORRIDOR, s.spanX(DIMS));
    }

    @Test
    @DisplayName("a gap moves the room out by it and the exit twin by twice it — the same on both sides")
    void gap_movesBothCorridorsSymmetrically() {
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS, PortalCorridorKind.LONG);
        for (PortalRoomMode mode : new PortalRoomMode[] {PortalRoomMode.ENDLESS_OPEN, PortalRoomMode.CHUNK_ENDLESS_OPEN}) {
            PortalStructure s = structure(mode, 16);
            assertEquals(16, s.doorGap(), mode.name());
            int entryEnd = s.origin().getX() + CORRIDOR - 1;
            int roomStart = s.roomOrigin(DIMS, layout).getX();
            int roomEnd = roomStart + s.roomLength() - 1;
            int exitStart = s.exitOrigin(DIMS).getX();
            assertEquals(16, roomStart - entryEnd - 1, "plain between the entry mouth and the room");
            assertEquals(16, exitStart - roomEnd - 1, "plain between the room and the exit mouth");
            assertEquals(16, s.roomLength(), "the box keeps its size");
        }
    }

    @Test
    @DisplayName("a walled room ignores a stored gap")
    void walledRoom_hasNoGap() {
        for (PortalRoomMode mode : new PortalRoomMode[] {PortalRoomMode.BEDROCK, PortalRoomMode.VOID,
                PortalRoomMode.ENDLESS_REPETITION, PortalRoomMode.CHUNK_VOID}) {
            PortalStructure s = structure(mode, 16);
            assertEquals(0, s.doorGap(), mode.name());
            assertEquals(CORRIDOR + 16, s.exitTwinOffsetX(DIMS), mode.name());
        }
    }

    @Test
    @DisplayName("the gap round-trips through the tag, and no gap is never written")
    void tag_roundTrips() {
        PortalRoomSettings open = PortalRoomSettings.DEFAULT.withMode(PortalRoomMode.ENDLESS_OPEN);
        assertEquals("endless_open", open.toTag());
        PortalRoomSettings gapped = open.withDoorDistance(new PortalRoomDoorDistance(9));
        assertEquals(9, PortalRoomSettings.parse(gapped.toTag()).doorDistance().value());
        assertEquals(gapped.toTag(), PortalRoomSettings.parse(gapped.toTag()).toTag());
        // A room switched away from Endless Open does not keep a segment nothing reads.
        assertFalse(gapped.withMode(PortalRoomMode.BEDROCK).toTag().contains("/"));
    }

    @Test
    @DisplayName("a distance is clamped to 0..MAX and parses totally")
    void distance_clampsAndParses() {
        assertEquals(0, new PortalRoomDoorDistance(-4).value());
        assertEquals(PortalRoomDoorDistance.MAX, new PortalRoomDoorDistance(9999).value());
        assertEquals(PortalRoomDoorDistance.DEFAULT, PortalRoomDoorDistance.parse("x"));
        assertEquals(PortalRoomDoorDistance.DEFAULT, PortalRoomDoorDistance.parse(null));
        assertEquals(12, PortalRoomDoorDistance.parse(" 12 ").value());
    }

    @Test
    @DisplayName("a corridor out on the plain has no seal ring in its mask")
    void gappedCorridor_hasNoSeal() {
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(DIMS, PortalCorridorKind.LONG);
        assertEquals(2, PortalCorridorMask.forCorridor(structure(PortalRoomMode.ENDLESS_OPEN, 4), DIMS, layout,
            3, PortalCarriageRole.ENTRY, true).boxes().size());
        assertEquals(3, PortalCorridorMask.forCorridor(structure(PortalRoomMode.ENDLESS_OPEN, 0), DIMS, layout,
            3, PortalCarriageRole.ENTRY, true).boxes().size());
    }

    @Test
    @DisplayName("Endless open chunk: generates terrain, sweeps, fogs, lays a plain, never authored")
    void chunkEndlessOpenMode() {
        PortalRoomMode m = PortalRoomMode.CHUNK_ENDLESS_OPEN;
        assertTrue(m.generatesTerrain());
        assertTrue(m.clearsSurroundings());
        assertTrue(m.fogs());
        assertTrue(m.laysGroundPlain());
        assertTrue(m.opensDoorDistance());
        assertFalse(m.sealsRoomBox());
        assertFalse(m.tiles());
        assertFalse(m.drawsBorder());
        assertFalse(m.authored());
        assertSame(m, PortalRoomMode.parse(m.id()));
        assertFalse(PortalRoomMode.ENDLESS_OPEN.laysGroundPlain());
    }

    @Test
    @DisplayName("the plain row is the halo's bottom row, cut into chunk columns that cover it exactly")
    void groundRow_columns() {
        BoundingBox halo = new BoundingBox(-37, -64, 5, 40, -10, 70);
        BoundingBox row = PortalGroundPlain.groundRow(halo, -64);
        assertEquals(-64, row.minY());
        assertEquals(-64, row.maxY());
        List<BoundingBox> columns = PortalGroundPlain.columnsOf(row);
        long cells = columns.stream().mapToLong(b -> (long) b.getXSpan() * b.getZSpan()).sum();
        assertEquals((long) row.getXSpan() * row.getZSpan(), cells);
        for (BoundingBox b : columns) {
            assertEquals(b.minX() >> 4, b.maxX() >> 4);
            assertEquals(b.minZ() >> 4, b.maxZ() >> 4);
        }
    }
}
