package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Where a carriage box's two end doorways stand, given nothing but the box itself.
 *
 * <p>The carriage counterpart of
 * {@link games.brennan.dungeontrain.portal.PortalRoomDoorCells}, and written to the same contract:
 * pure — no level, no registry — a stable order, and an empty list rather than a throw for a box
 * that cannot hold a doorway. Its caller is the editor overlay, which asks the question for a plot
 * whose template may not have been read yet.</p>
 *
 * <h2>Derived from the writers, not chosen</h2>
 * <ul>
 *   <li><b>X: the two end caps.</b> {@code x == 0} and {@code x == length - 1} — the columns
 *       {@link CarriagePlacer#stateAt} treats as the end walls, and the two placements
 *       {@link CarriagePartKind#DOORS} stamps a door part at.</li>
 *   <li><b>Z: the doorway centre line.</b> {@code width / 2}, which is the {@code doorZ}
 *       {@link CarriagePlacer#placeAt} passes into {@code stateAt} and the line
 *       {@link games.brennan.dungeontrain.portal.PortalCarriageLayout#doorZ()} opens a corridor
 *       on.</li>
 *   <li><b>Y: two blocks above the floor.</b> {@code stateAt}'s door gap is {@code dy == 1 || dy
 *       == 2} — the floor row itself is {@code dy == 0} — so the door's lower cell is
 *       {@code origin.y + 1}.</li>
 * </ul>
 *
 * <h2>Which end is which</h2>
 * <p>The train travels {@code +X}, so a player walking the train enters a carriage through its
 * {@code -X} end and leaves through its {@code +X} one. {@link #doorBases} returns them in that
 * order — entrance first — which is the order
 * {@link games.brennan.dungeontrain.portal.PortalRoomDoorCells#doorBases} uses for a pocket room's
 * two mouths, so one overlay can label all of them from one rule.</p>
 */
public final class CarriageDoorCells {

    /** How many cells a single doorway occupies — {@code stateAt}'s two-block gap. */
    public static final int CELLS_PER_DOOR = 2;

    /** Doorways per carriage box: one at each end. */
    public static final int DOORS_PER_CARRIAGE = 2;

    private CarriageDoorCells() {}

    /**
     * The <b>lower</b> cell of each end doorway for a carriage box whose minimum corner is
     * {@code origin}, entrance ({@code -X}) end first.
     *
     * <p>Only the lower cell: a doorway is one object two blocks tall, and the caller draws the
     * upper half from the block above — the same shape
     * {@link games.brennan.dungeontrain.portal.PortalRoomDoorCells#doorBases} hands back.</p>
     *
     * <p>{@code box} is the <i>plot's own</i> dims, not the world's. A portal-corridor variant's
     * plot is longer than a carriage ({@code CarriageEditor.plotDims}), and passing the world dims
     * would put its far doorway several blocks short of the end it is actually cut in.</p>
     */
    public static List<BlockPos> doorBases(BlockPos origin, CarriageDims box) {
        if (origin == null || box == null) return List.of();
        int doorZ = box.width() / 2;
        int doorY = origin.getY() + 1;
        return List.of(
            new BlockPos(origin.getX(), doorY, origin.getZ() + doorZ),
            new BlockPos(origin.getX() + box.length() - 1, doorY, origin.getZ() + doorZ)
        );
    }
}
