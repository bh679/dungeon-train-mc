package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.DimensionalCarriageTiling.Tile;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which block a corridor's seal plane copies.
 *
 * <p>The plane outside a corridor's mouth used to be laid in the built-in room's polished blackstone,
 * which read as a slab of somebody else's palette dropped into an authored room — fifty blocks of it
 * per corridor, and an endless room scatters corridors. It is now built from the room's own end wall,
 * the same answer {@link DimensionalCarriageTiler#closeFace} gives at the tiling boundary.</p>
 *
 * <p>The write itself needs a live level and is not tested here. The <b>choice of cell</b> is pure
 * integer geometry and is the part that can silently go wrong — one column out and a mouth copies the
 * room's interior, or the basement, instead of its wall.</p>
 */
class PortalSealFillSourceTest {

    private static final CarriageDims DIMS = CarriageDims.DEFAULT;
    private static final BlockPos ORIGIN = new BlockPos(200, -60, -30);
    private static final Vec3i SIZE = DimensionalCarriageLayout.builtInSize(DIMS);
    private static final PortalCarriageLayout LAYOUT = PortalCarriageBuilder.layoutFor(DIMS, PortalCorridorKind.LONG);

    private static PortalStructure structure(Tile exitTile) {
        return new PortalStructure(ORIGIN, "labrynth", SIZE,
            DimensionalCarriageSettings.DEFAULT.withMode(DimensionalCarriageMode.ENDLESS_REPETITION),
            DimensionalCarriageTiling.base(), PortalExitCopies.NONE, exitTile);
    }

    private static BlockPos sourceFor(PortalStructure shadow, PortalStructure base,
                                      PortalCarriageRole role, int y, int z) {
        return PortalCarriageBuilder.sealFillSource(
            base.roomOrigin(DIMS, LAYOUT), shadow.roomOrigin(DIMS, LAYOUT), SIZE, role, y, z);
    }

    @Test
    @DisplayName("Each mouth copies the room's own end column on its side")
    void eachRoleReadsItsOwnEndColumn() {
        PortalStructure s = structure(Tile.BASE);
        BlockPos room = s.roomOrigin(DIMS, LAYOUT);
        int y = room.getY() + 3;
        int z = room.getZ() + 2;

        // An entry mouth is at the room's low-X end, an exit mouth at its high-X end — so the wall
        // each one carries on is the room's own end column on that side.
        assertEquals(room.getX(), sourceFor(s, s, PortalCarriageRole.ENTRY, y, z).getX());
        assertEquals(room.getX() + SIZE.getX() - 1,
            sourceFor(s, s, PortalCarriageRole.EXIT, y, z).getX());
    }

    @Test
    @DisplayName("The source is the block the seal plane stands one step outside of")
    void sourceIsOneStepInsideTheSealPlane() {
        PortalStructure s = structure(Tile.BASE);
        BlockPos room = s.roomOrigin(DIMS, LAYOUT);
        int y = room.getY() + 2;
        int z = room.getZ() + 4;

        // The two planes stampCorridorHalf actually writes, restated here so an off-by-one in either
        // place has to argue with the other.
        int entryPlaneX = s.origin().getX() + LAYOUT.length() - 1;
        int exitPlaneX = s.exitOrigin(DIMS).getX();

        assertEquals(entryPlaneX + 1, sourceFor(s, s, PortalCarriageRole.ENTRY, y, z).getX(),
            "an entry mouth reads the column just inside it, along +X");
        assertEquals(exitPlaneX - 1, sourceFor(s, s, PortalCarriageRole.EXIT, y, z).getX(),
            "an exit mouth reads the column just inside it, along -X");
    }

    @Test
    @DisplayName("Height and lateral position are preserved, so a varied wall is copied faithfully")
    void yAndZCarryStraightAcross() {
        PortalStructure s = structure(Tile.BASE);
        BlockPos room = s.roomOrigin(DIMS, LAYOUT);

        for (int y = room.getY(); y < room.getY() + SIZE.getY(); y++) {
            for (int z = room.getZ(); z < room.getZ() + SIZE.getZ(); z++) {
                BlockPos src = sourceFor(s, s, PortalCarriageRole.ENTRY, y, z);
                assertEquals(y, src.getY(), "y at " + y + "," + z);
                assertEquals(z, src.getZ(), "z at " + y + "," + z);
            }
        }
        // In particular the floor row reads the floor, which is the fallback tier's whole basis.
        assertEquals(room.getY(),
            sourceFor(s, s, PortalCarriageRole.ENTRY, room.getY(), room.getZ() + 1).getY());
    }

    @Test
    @DisplayName("A copy's mouth reads the BASE room, not the tile it stands beside")
    void aCopyReadsTheBaseRoom() {
        PortalStructure base = structure(Tile.BASE);
        BlockPos room = base.roomOrigin(DIMS, LAYOUT);

        for (Tile tile : new Tile[]{new Tile(1, 0), new Tile(0, -4), new Tile(-3, 2), new Tile(5, 5)}) {
            PortalStructure shadow = base.shadowAt(tile);
            int y = room.getY() + 2;
            // A cell of the copy's plane, offset by that tile exactly as the shadow's room is.
            int z = room.getZ() + 3 + tile.z() * base.roomWidth();

            BlockPos src = sourceFor(shadow, base, PortalCarriageRole.EXIT, y, z);
            // Back onto the base room's column: the same X whatever tile the copy is on, because the
            // base room is the one copy guaranteed to be standing when a corridor is laid.
            assertEquals(room.getX() + SIZE.getX() - 1, src.getX(), tile.toString());
            assertEquals(y, src.getY(), tile.toString());
            assertEquals(room.getZ() + 3, src.getZ(), tile.toString());
        }
    }

    @Test
    @DisplayName("A moved exit reads the base room too — its own tile is not stamped yet")
    void aMovedExitReadsTheBaseRoom() {
        Tile exitTile = new Tile(0, -2);
        PortalStructure moved = structure(exitTile);
        PortalStructure shadow = moved.exitShadow();
        BlockPos room = moved.roomOrigin(DIMS, LAYOUT);
        BlockPos shadowRoom = shadow.roomOrigin(DIMS, LAYOUT);

        // The case the base-room rule exists for: stampPairStructure lays the base room and THEN the
        // corridors, so a pair that stood its exit beside another tile seals against a room the
        // tiling has not reached. Reading beside it would copy basement rock.
        int y = shadowRoom.getY() + 1;
        int z = shadowRoom.getZ() + 5;
        BlockPos src = sourceFor(shadow, moved, PortalCarriageRole.EXIT, y, z);

        assertEquals(room.getX() + SIZE.getX() - 1, src.getX());
        assertEquals(room.getZ() + 5, src.getZ());
        // Which is a different column from the one beside the seal, and that is the point.
        assertEquals(exitTile.z() * moved.roomWidth(), z - src.getZ());
    }
}
