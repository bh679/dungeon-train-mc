package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalRoomTiling.Tile;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Closes whatever a room copy's own stamp left open in a corridor mouth's seal plane.
 *
 * <h2>Why a copy writes that plane at all</h2>
 * <p>A seal plane stands exactly one column outside the base room box, which is exactly the wall
 * plane of the copy standing at tile {@code (±1, 0)} — see
 * {@link PortalCorridorMask#forStructure(PortalStructure, CarriageDims, PortalCarriageLayout, int, boolean)}.
 * While it was masked out of every write, those two copies never laid the wall that touches the
 * portal carriage: what stood there was the mouth's mirror fill, taken from the base room's
 * <i>opposite</i> end column and flattened to full blocks, so the authored wall did not repeat and a
 * {@link PortalRoomCopies.Kind#DYNAMIC} copy wore the base room's roll rather than its own.</p>
 *
 * <p>Under {@link PortalRoomMode#ENDLESS_REPETITION} the copy is now handed that plane
 * ({@code PortalRoomTiler.stampTile} stamps through a seal-less mask) and lays its own wall into it,
 * stairs, slabs and all.</p>
 *
 * <h2>What this then has to guarantee</h2>
 * <p><b>No air.</b> The seal is the only thing between the room and the basement when the copy beyond
 * it cannot be stamped — the budget is spent, or its chunks are not loaded — and unlike
 * {@code PortalRoomTiler.closeFace} this plane may not be left open. An author's room may have air in
 * its end wall (a doorway drawn for the corridor, an open-sided room like {@code distantenemies}), so
 * every cell the stamp left as air is filled here by exactly the rule the mouth itself uses:
 * {@code PortalCarriageBuilder.sealFillFor}, anchored on <b>this copy's</b> room rather than the base
 * one, so a Dynamic copy is closed with its own blocks.</p>
 *
 * <p><b>Nothing the corridor owns is touched.</b> The doorway and the corridor's own blocks sit in
 * the corridor and plug boxes, which stay masked in every path — only the ring around the mouth is
 * ever released. This walks the seal plane through that same seal-less mask, so the door cannot be
 * bricked up here any more than it can be stamped over.</p>
 */
public final class PortalRoomSealRepair {

    private PortalRoomSealRepair() {}

    /**
     * Fill any air {@code tile}'s stamp left in a seal plane of its own.
     *
     * <p>A no-op for a tile no mouth stands against, which is all but two of them — the test is a box
     * intersection per corridor, and a structure owns two plus one per standing extra corridor.</p>
     */
    public static void repair(ServerLevel level, CarriageDims dims, PortalStructure structure,
                              Tile tile) {
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());
        BlockPos tileOrigin = structure.tileOrigin(dims, layout, tile);
        Vec3i size = structure.roomSize();
        BoundingBox tileBox = new BoundingBox(
            tileOrigin.getX(), tileOrigin.getY(), tileOrigin.getZ(),
            tileOrigin.getX() + size.getX() - 1,
            tileOrigin.getY() + size.getY() - 1,
            tileOrigin.getZ() + size.getZ() - 1);

        // Everything a corridor owns EXCEPT the seals — the cells this pass must skip. Read once:
        // it is the same mask the stamp was made through, so the two cannot disagree about which
        // cells belong to the copy and which to the door.
        PortalCorridorMask corridors =
            PortalCarriageBuilder.allCorridorMask(structure, dims, /*withSeals*/ false);

        sealAgainst(level, dims, layout, structure, PortalCarriageRole.ENTRY,
            tileOrigin, tileBox, corridors);
        sealAgainst(level, dims, layout, structure.exitShadow(), PortalCarriageRole.EXIT,
            tileOrigin, tileBox, corridors);
        for (PortalExitSites.Site site : structure.exitCopies().sites()) {
            sealAgainst(level, dims, layout, structure.shadowAt(site.tile()), site.role(),
                tileOrigin, tileBox, corridors);
        }
    }

    /**
     * One corridor's mouth, when its seal plane falls inside {@code tileBox}.
     *
     * <p>{@code shadow} is the frame the corridor was stamped and masked from — the pair's own
     * structure for an entry, {@link PortalStructure#exitShadow} for an exit, a site's anchor tile
     * for an extra corridor — so the plane is computed here from exactly the geometry that laid
     * it.</p>
     */
    private static void sealAgainst(ServerLevel level, CarriageDims dims,
                                    PortalCarriageLayout layout, PortalStructure shadow,
                                    PortalCarriageRole role, BlockPos tileOrigin,
                                    BoundingBox tileBox, PortalCorridorMask corridors) {
        BlockPos corridorOrigin = role == PortalCarriageRole.ENTRY
            ? shadow.origin()
            : shadow.exitOrigin(dims);
        BlockPos room = shadow.roomOrigin(dims, layout);
        Vec3i size = shadow.roomSize();
        int planeX = PortalCorridorMask.sealPlaneX(corridorOrigin, layout, role);
        BoundingBox plane = PortalCorridorMask.seal(planeX, room.getY(), room.getZ(),
            room.getY() + size.getY() - 1, room.getZ() + size.getZ() - 1);
        if (!plane.intersects(tileBox)) return;

        int floorY = room.getY();
        int z0 = Math.max(plane.minZ(), tileBox.minZ());
        int z1 = Math.min(plane.maxZ(), tileBox.maxZ());
        int y0 = Math.max(plane.minY(), tileBox.minY());
        int y1 = Math.min(plane.maxY(), tileBox.maxY());

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = z0; z <= z1; z++) {
            for (int y = y0; y <= y1; y++) {
                pos.set(planeX, y, z);
                if (corridors.covers(pos)) continue;
                if (!level.getBlockState(pos).isAir()) continue;
                // Anchored on the copy's own room, so the wall it is closed with is the wall this
                // copy is built from — which is the whole point of handing it the plane.
                BlockState fill = PortalCarriageBuilder.sealFillFor(level, tileOrigin, room, size,
                    role, y, z, floorY);
                level.setBlock(pos, fill, Block.UPDATE_ALL);
            }
        }
    }
}
