package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The box a player has to be inside for a test dimensional carriage to keep its room copies —
 * the geometry behind {@code PortalTestTicker}'s window, on its own so it can be swept by a test.
 *
 * <p><b>Tighter than {@code PortalCarriageEvents.structureBox}, and deliberately so.</b> That box
 * answers "is anyone in here, so do not re-stamp"; this one answers "is the author still walking
 * through the window", and a player past its edge is the signal to drain. It is the structure and
 * its standing copies plus a block of tolerance, with none of the live box's room slack.</p>
 *
 * <h2>Y is read off the room, not off the corridor lane</h2>
 * <p>They are the same row only while the door sits at the room's own floor. A door-height offset
 * stands the room's floor <i>below</i> the lane ({@link PortalRoomLayout#roomOrigin}), and an exit
 * door placed apart from its entry door stands one corridor below the other
 * ({@link PortalStructure#exitOrigin}). A window that started at the lane called an author standing
 * on the floor of their own room "outside" — and outside is the drain, so every copy came down
 * around them while they walked through it. Same correction, and the same reason, as the one
 * {@code structureBox} and {@link PortalCarriageBuilder#footprintOf} already carry.</p>
 */
public final class PortalTestWindow {

    /** Blocks of tolerance past the structure's own extent, so its outer face is inside. */
    private static final int PAD = 1;

    private PortalTestWindow() {}

    /**
     * The window box for {@code structure}, in world coordinates, inclusive.
     *
     * <p>Unions three things: the corridor span in X, the tiled rectangle the room has actually
     * grown, and — for Y, where an exit corridor can stand off the lane — the bounds of the corridor
     * masks themselves, which is what places them.</p>
     */
    public static BoundingBox occupancyBox(PortalStructure structure, CarriageDims dims,
                                           PortalCarriageLayout layout) {
        BlockPos origin = structure.origin();
        BlockPos roomOrigin = structure.roomOrigin(dims, layout);
        Vec3i roomSize = structure.roomSize();

        int minX = Math.min(origin.getX() - PAD, structure.tiledMinX(dims, layout) - PAD);
        int maxX = Math.max(origin.getX() + structure.spanX(dims) + PAD,
            structure.tiledMaxX(dims, layout) + PAD + 1);
        int minZ = Math.min(origin.getZ() - PAD, structure.tiledMinZ(dims, layout) - PAD);
        int maxZ = Math.max(origin.getZ() + dims.width() + PAD,
            structure.tiledMaxZ(dims, layout) + PAD + 1);

        // The lower of the room's floor and the lane, and the higher of the room's ceiling and the
        // corridor's — a room with no door offset makes both pairs the same figure, which is what
        // keeps the box the one it has always been for nearly every room.
        int minY = Math.min(origin.getY(), roomOrigin.getY()) - PAD;
        int maxY = Math.max(origin.getY() + dims.height(), roomOrigin.getY() + roomSize.getY())
            + PAD + 1;

        // Read off the masks that place the corridors, so this cannot disagree with them about
        // where a displaced exit — or an extra corridor standing beside another tile — actually is.
        BoundingBox corridors = PortalCarriageBuilder.allCorridorMask(structure, dims).bounds();
        if (corridors != null) {
            minX = Math.min(minX, corridors.minX() - PAD);
            maxX = Math.max(maxX, corridors.maxX() + PAD + 1);
            minZ = Math.min(minZ, corridors.minZ() - PAD);
            maxZ = Math.max(maxZ, corridors.maxZ() + PAD + 1);
            minY = Math.min(minY, corridors.minY() - PAD);
            maxY = Math.max(maxY, corridors.maxY() + PAD + 1);
        }

        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * True when a position is inside {@code box}.
     *
     * <p>Takes doubles because what it is asked about is a player's position rather than a block:
     * {@link BoundingBox#isInside} floors nothing and would read a player at {@code y = maxY + 0.5}
     * as outside a box whose top row they are standing in.</p>
     */
    public static boolean contains(BoundingBox box, double x, double y, double z) {
        return x >= box.minX() && x <= box.maxX()
            && y >= box.minY() && y <= box.maxY()
            && z >= box.minZ() && z <= box.maxZ();
    }
}
