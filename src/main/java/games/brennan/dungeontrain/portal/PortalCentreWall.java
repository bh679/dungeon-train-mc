package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;

/**
 * The doorway column through the wall that stands between a portal pair's two corridors — the two
 * blocks separating the entry corridor's far door from the exit corridor's near door.
 *
 * <p>Each corridor overruns into the cart between the pair, leaving
 * {@link PortalCorridorSize#centreWallWidth} columns of it standing at the exact centre of the group
 * ({@link PortalCarriageBuilder#stampMiddle}). At the default carriage length that is a single block,
 * and the two doors are one block apart through it:</p>
 *
 * <pre>
 *   … ENTRY corridor │D│ ▓ │D│ EXIT corridor …
 *                     ^   ^
 *                far door │ near door
 *                    the centre wall
 * </pre>
 *
 * <p><b>What this column is for.</b> A working portal never needs it: the entry corridor swaps you
 * into the twin before you reach its far door, and the exit corridor swaps you out before you reach
 * its near one, so the wall is sealed space by construction. A <b>severed</b> pair
 * ({@link PortalSever}) has no such swap left, and without an opening the group becomes three
 * carriages of dead end — walk in the entry corridor's near door and there is nowhere to go but back.
 * Opening the column turns a broken portal into an ordinary walk-through carriage: entry corridor,
 * through both dummy doors, out of the exit corridor onto the train.</p>
 *
 * <p><b>Black concrete when it is closed.</b> The rest of the wall is the corridor's own stone brick
 * shell. These two cells are the one place a player can meet the seam between the pair's two halves,
 * and reading as a deliberate plate rather than more masonry is what makes the opening legible when
 * it appears.</p>
 *
 * <p>Coordinates are <b>middle-slot local</b> — {@code 0,0,0} is the minimum corner of the cart's own
 * carriage slot, the origin {@code stampMiddle} is handed — not corridor local. The two frames differ
 * by the overrun, which is exactly what {@link #minX} carries.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 */
public final class PortalCentreWall {

    /**
     * Local Y of the lowest cell of the doorway column.
     *
     * <p>One above the floor, which is {@link PortalCarriageLayout#floorY} — {@code dy 0} is the
     * floor block itself and stays solid, or the walk-through would open onto a hole.</p>
     */
    public static final int MIN_Y = 1;

    /**
     * Local Y of the highest cell — a two-block opening, the height of the doors either side of it.
     *
     * <p>Matches {@link PortalCarriageLayout#isDoorwayCell}, which leaves {@code floorY + 1} and
     * {@code floorY + 2} clear in each end plane. A shorter opening would not be walkable and a
     * taller one would show daylight over the doors.</p>
     */
    public static final int MAX_Y = 2;

    private PortalCentreWall() {}

    /** Lowest middle-slot-local X of the centre wall: the far edge of the entry corridor's overrun. */
    public static int minX(CarriageDims dims) {
        return PortalCorridorSize.overrun(dims);
    }

    /** One past the highest local X of the centre wall. */
    public static int maxXExclusive(CarriageDims dims) {
        return minX(dims) + PortalCorridorSize.centreWallWidth(dims);
    }

    /**
     * Local Z of the doorway column — the walkway centre line, the same one the corridors put their
     * doors on ({@link PortalCarriageLayout#doorZ}). Read off the same expression rather than the
     * layout record so this does not need one built to answer.
     */
    public static int doorZ(CarriageDims dims) {
        return dims.width() / 2;
    }

    /**
     * True if a middle-slot-local cell is part of the doorway column through the centre wall.
     *
     * <p>Cells outside the wall's own X range answer {@code false} — they belong to one of the two
     * corridors, which are stamped separately and must not be touched from here.</p>
     */
    public static boolean isDoorwayColumn(CarriageDims dims, int dx, int dy, int dz) {
        return dx >= minX(dims) && dx < maxXExclusive(dims)
            && dy >= MIN_Y && dy <= MAX_Y
            && dz == doorZ(dims);
    }

    /**
     * The doorway column as {@code {dx, dy, dz}} triples, in {@code x} then {@code y} order.
     *
     * <p>For callers that have to <i>write</i> the cells rather than classify one — the live opening
     * {@link PortalSever} does when a pair is severed under somebody standing in it, which has no box
     * to walk and only these cells to change.</p>
     */
    public static int[][] doorwayCells(CarriageDims dims) {
        int from = minX(dims);
        int to = maxXExclusive(dims);
        int z = doorZ(dims);
        int rows = MAX_Y - MIN_Y + 1;

        int[][] cells = new int[(to - from) * rows][];
        int i = 0;
        for (int dx = from; dx < to; dx++) {
            for (int dy = MIN_Y; dy <= MAX_Y; dy++) {
                cells[i++] = new int[] {dx, dy, z};
            }
        }
        return cells;
    }

    /**
     * The same cells in <b>corridor</b> local X — the frame a live {@code PortalPairIndex.Entry}
     * addresses blocks in, for whichever of the pair's two corridors is asking.
     *
     * <p>The X values are deliberately <i>outside</i> the corridor's own bounds: the wall is one block
     * past an {@link PortalCarriageRole#ENTRY} corridor's far end, and one block before an
     * {@link PortalCarriageRole#EXIT} corridor's origin (so its X values are negative). That is the
     * point — these blocks belong to the cart, not to either corridor, and nothing else can reach
     * them from a pairing. {@code PortalPairIndex.Entry.plotPosOf} converts a local cell through the
     * ship's transform without bounds-checking it, so it answers for these just as well as for cells
     * inside; {@code localOfPlot}, which does bound, keeps ignoring them — which is why opening this
     * column is not mirrored into the twin.</p>
     */
    public static int[][] doorwayCellsFromCorridor(CarriageDims dims, PortalCarriageRole role) {
        // The cart's own slot begins one carriage length past the entry corridor's origin, and the
        // exit corridor's origin is a further length on, pulled back by its overrun
        // (PortalCorridorSize.originOffsetX).
        int shift = role == PortalCarriageRole.ENTRY
            ? dims.length()
            : PortalCorridorSize.overrun(dims) - dims.length();

        int[][] cells = doorwayCells(dims);
        for (int[] cell : cells) {
            cell[0] += shift;
        }
        return cells;
    }
}
