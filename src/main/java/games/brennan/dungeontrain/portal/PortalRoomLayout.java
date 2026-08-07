package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/**
 * Where a portal's pocket room sits and how big it is — the pure geometry behind both the built-in
 * room and the authored {@code portal_room} template.
 *
 * <p><b>Length is the free axis.</b> Height and width are pinned: the room has to be at least as
 * tall and wide as the corridor whose mouth opens into it, or the mouth's seal ring would not close.
 * Length is not pinned by anything — it is the distance a player walks underneath, and the whole
 * point of the portal is that this distance differs from the two carriages the same walk covers on
 * the train. So an authored room may be any length, and everything downstream reads the length off
 * the template rather than off a constant (see {@link PortalStructure}).</p>
 *
 * <p><b>Openings are on the walkway centre line.</b> {@link #roomOrigin} centres the room on
 * {@link PortalCarriageLayout#doorZ()}, which is the same line the corridor's doorways sit on. A
 * room authored with its own internal walls across that line would open onto a wall.</p>
 *
 * <p><b>Nothing here assumes one room per structure.</b> Sizes and origins are values, and
 * {@code PortalCarriageBuilder.stampRoomAt} takes an explicit origin — a second room alongside the
 * first is another call at {@code roomOrigin.offset(0, 0, ±width)}, not a rewrite.</p>
 */
public final class PortalRoomLayout {

    /** Length of the built-in room, used when no template has been authored. */
    public static final int BUILT_IN_LENGTH = 11;

    /** Interior width of the built-in room — the shell adds one wall on each side. */
    private static final int BUILT_IN_INTERIOR_WIDTH = 11;

    /** Interior height of the built-in room — the shell adds the floor row and the ceiling row. */
    private static final int BUILT_IN_INTERIOR_HEIGHT = 5;

    /**
     * Shortest room worth authoring. Below this the two corridor mouths are close enough that the
     * far one is visible from the near one, which is the one thing the baffles exist to prevent.
     */
    public static final int MIN_LENGTH = 5;

    /**
     * Longest room. Not a technical ceiling — the structure is free-standing at the world floor —
     * but a room longer than this walks further than the crossing reads as, and every block of it
     * is re-stamped each time the train drifts {@code TWIN_MAX_DRIFT}.
     */
    public static final int MAX_LENGTH = 48;

    private PortalRoomLayout() {}

    /** The built-in room's full box, shell included. */
    public static Vec3i builtInSize(CarriageDims dims) {
        return sizeOfLength(dims, BUILT_IN_LENGTH);
    }

    /** A room box of {@code length}, with the height and width the corridor pins. */
    public static Vec3i sizeOfLength(CarriageDims dims, int length) {
        return new Vec3i(clampLength(length), height(dims), width(dims));
    }

    /**
     * Full room height, shell included. At the default {@code CarriageDims(9,7,7)} this is 7, the
     * value the room was hardcoded at. The {@code max} matters for a world whose carriages are
     * taller than that: a corridor poking out through the room's ceiling would leave the mouth
     * unsealed and open the twin structure to the rock above it.
     */
    public static int height(CarriageDims dims) {
        return Math.max(BUILT_IN_INTERIOR_HEIGHT + 2, dims.height());
    }

    /**
     * Full room width, shell included. 13 at the default dims. Two blocks of room wall either side
     * of the corridor's cross-section is the minimum that still reads as stepping out into
     * somewhere else rather than into a wider corridor.
     */
    public static int width(CarriageDims dims) {
        return Math.max(BUILT_IN_INTERIOR_WIDTH + 2, dims.width() + 4);
    }

    /** Clamp an authored length into {@link #MIN_LENGTH}..{@link #MAX_LENGTH}. */
    public static int clampLength(int length) {
        return Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, length));
    }

    /**
     * Minimum corner of the room box for a structure whose entry twin is at {@code entryOrigin} —
     * one corridor along {@code +X}, and centred on the corridor's doorway line in {@code Z}.
     */
    public static BlockPos roomOrigin(BlockPos entryOrigin, CarriageDims dims,
                                      PortalCarriageLayout layout) {
        int interiorWidth = width(dims) - 2;
        int zCentre = entryOrigin.getZ() + layout.doorZ();
        int interiorMinZ = zCentre - interiorWidth / 2;
        return new BlockPos(
            entryOrigin.getX() + dims.length(),
            entryOrigin.getY(),
            interiorMinZ - 1);
    }
}
